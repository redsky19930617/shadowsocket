/*
 * Shadowsocks - A shadowsocks client for Android
 * Copyright (C) 2014 <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */
package com.github.shadowsocks

import java.util
import java.io.{OutputStream, InputStream, ByteArrayInputStream, ByteArrayOutputStream, IOException, FileOutputStream}

import android.app.backup.BackupManager
import android.app.{Activity, AlertDialog, ProgressDialog}
import android.content._
import android.content.pm.{PackageInfo, PackageManager}
import android.content.res.AssetManager
import android.graphics.{Bitmap, Color, Typeface}
import android.net.{Uri, VpnService}
import android.os._
import android.preference._
import android.util.{DisplayMetrics, Log}
import android.view._
import android.webkit.{WebView, WebViewClient}
import android.widget._
import com.github.shadowsocks.aidl.{IShadowsocksService, IShadowsocksServiceCallback}
import com.github.shadowsocks.database._
import com.github.shadowsocks.preferences.{PasswordEditTextPreference, ProfileEditTextPreference, SummaryEditTextPreference}
import com.github.shadowsocks.utils._
import com.google.android.gms.ads.{AdRequest, AdSize, AdView}
import com.google.android.gms.analytics.HitBuilders
import com.google.zxing.integration.android.IntentIntegrator
import com.nostra13.universalimageloader.core.download.BaseImageDownloader
import de.keyboardsurfer.android.widget.crouton.{Configuration, Crouton, Style}
import net.simonvt.menudrawer.MenuDrawer

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.ops._
import scala.ref.WeakReference

class ProfileIconDownloader(context: Context, connectTimeout: Int, readTimeout: Int)
  extends BaseImageDownloader(context, connectTimeout, readTimeout) {

  def this(context: Context) {
    this(context, 0, 0)
  }

  override def getStreamFromOtherSource(imageUri: String, extra: AnyRef): InputStream = {
    val text = imageUri.substring(Scheme.PROFILE.length)
    val size = Utils.dpToPx(context, 16).toInt
    val idx = text.getBytes.last % 6
    val color = Seq(Color.MAGENTA, Color.GREEN, Color.YELLOW, Color.BLUE, Color.DKGRAY, Color.CYAN)(
      idx)
    val bitmap = Utils.getBitmap(text, size, size, color)

    val os = new ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
    new ByteArrayInputStream(os.toByteArray)
  }
}

object Typefaces {
  def get(c: Context, assetPath: String): Typeface = {
    cache synchronized {
      if (!cache.containsKey(assetPath)) {
        try {
          val t: Typeface = Typeface.createFromAsset(c.getAssets, assetPath)
          cache.put(assetPath, t)
        } catch {
          case e: Exception =>
            Log.e(TAG, "Could not get typeface '" + assetPath + "' because " + e.getMessage)
            return null
        }
      }
      return cache.get(assetPath)
    }
  }

  private final val TAG = "Typefaces"
  private final val cache = new util.Hashtable[String, Typeface]
}

object Shadowsocks {

  // Constants
  val TAG = "Shadowsocks"
  val REQUEST_CONNECT = 1

  val PREFS_NAME = "Shadowsocks"
  val PROXY_PREFS = Array(Key.profileName, Key.proxy, Key.remotePort, Key.localPort, Key.sitekey,
    Key.encMethod)
  val FEATRUE_PREFS = Array(Key.isGFWList, Key.isGlobalProxy, Key.proxyedApps, 
    Key.isUdpDns, Key.isAutoConnect)

  val EXECUTABLES = Array(Executable.PDNSD, Executable.REDSOCKS, Executable.IPTABLES)

  // Helper functions
  def updateListPreference(pref: Preference, value: String) {
    pref.setSummary(value)
    pref.asInstanceOf[ListPreference].setValue(value)
  }

  def updatePasswordEditTextPreference(pref: Preference, value: String) {
    pref.setSummary(value)
    pref.asInstanceOf[PasswordEditTextPreference].setText(value)
  }

  def updateSummaryEditTextPreference(pref: Preference, value: String) {
    pref.setSummary(value)
    pref.asInstanceOf[SummaryEditTextPreference].setText(value)
  }

  def updateProfileEditTextPreference(pref: Preference, value: String) {
    pref.asInstanceOf[ProfileEditTextPreference].resetSummary(value)
    pref.asInstanceOf[ProfileEditTextPreference].setText(value)
  }

  def updateCheckBoxPreference(pref: Preference, value: Boolean) {
    pref.asInstanceOf[CheckBoxPreference].setChecked(value)
  }

  def updatePreference(pref: Preference, name: String, profile: Profile) {
    name match {
      case Key.profileName => updateProfileEditTextPreference(pref, profile.name)
      case Key.proxy => updateSummaryEditTextPreference(pref, profile.host)
      case Key.remotePort => updateSummaryEditTextPreference(pref, profile.remotePort.toString)
      case Key.localPort => updateSummaryEditTextPreference(pref, profile.localPort.toString)
      case Key.sitekey => updatePasswordEditTextPreference(pref, profile.password)
      case Key.encMethod => updateListPreference(pref, profile.method)
      case Key.isGFWList => updateCheckBoxPreference(pref, profile.chnroute)
      case Key.isGlobalProxy => updateCheckBoxPreference(pref, profile.global)
      case Key.isUdpDns => updateCheckBoxPreference(pref, profile.udpdns)
      case _ =>
    }
  }
}

class Shadowsocks
  extends PreferenceActivity
  with CompoundButton.OnCheckedChangeListener
  with MenuAdapter.MenuListener {

  // Flags
  val MSG_CRASH_RECOVER: Int = 1
  val STATE_MENUDRAWER = "com.github.shadowsocks.menuDrawer"
  val STATE_ACTIVE_VIEW_ID = "com.github.shadowsocks.activeViewId"
  var singlePane: Int = -1

  // Variables
  var switchButton: Switch = null
  var progressDialog: ProgressDialog = null
  var state = State.INIT
  var prepared = false
  var currentProfile = new Profile
  var vpnEnabled = -1

  // Services
  var currentServiceName = classOf[ShadowsocksNatService].getName
  var bgService: IShadowsocksService = null
  val callback = new IShadowsocksServiceCallback.Stub {
    override def stateChanged(state: Int, msg: String) {
      onStateChanged(state, msg)
    }
  }
  val connection = new ServiceConnection {
    override def onServiceConnected(name: ComponentName, service: IBinder) {
      // Initialize the background service
      bgService = IShadowsocksService.Stub.asInterface(service)
      try {
        bgService.registerCallback(callback)
      } catch {
        case ignored: RemoteException => // Nothing
      }
      // Update the UI
      if (switchButton != null) switchButton.setEnabled(true)
      if (State.isAvailable(bgService.getState)) {
        Crouton.cancelAllCroutons()
        setPreferenceEnabled(enabled = true)
      } else {
        changeSwitch(checked = true)
        setPreferenceEnabled(enabled = false)
      }
      state = bgService.getState
      // set the listener
      switchButton.setOnCheckedChangeListener(Shadowsocks.this)
    }

    override def onServiceDisconnected(name: ComponentName) {
      if (switchButton != null) switchButton.setEnabled(false)
      try {
        if (bgService != null) bgService.unregisterCallback(callback)
      } catch {
        case ignored: RemoteException => // Nothing
      }
      bgService = null
    }
  }

  private lazy val settings = PreferenceManager.getDefaultSharedPreferences(this)
  private lazy val status = getSharedPreferences(Key.status, Context.MODE_PRIVATE)
  private lazy val preferenceReceiver = new PreferenceBroadcastReceiver
  private lazy val drawer = MenuDrawer.attach(this)
  private lazy val menuAdapter = new MenuAdapter(this, getMenuList)
  private lazy val listView = new ListView(this)
  private lazy val profileManager =
    new ProfileManager(settings, getApplication.asInstanceOf[ShadowsocksApplication].dbHelper)

  private lazy val application = getApplication.asInstanceOf[ShadowsocksApplication]

  var handler: Handler = null

  def isSinglePane: Boolean = {
    if (singlePane == -1) {
      val metrics = new DisplayMetrics()
      getWindowManager.getDefaultDisplay.getMetrics(metrics)
      val widthPixels = metrics.widthPixels
      val scaleFactor = metrics.density
      val widthDp = widthPixels / scaleFactor

      singlePane = if (widthDp <= 720) 1 else 0
    }
    singlePane == 1
  }

  private def changeSwitch(checked: Boolean) {
    switchButton.setOnCheckedChangeListener(null)
    switchButton.setChecked(checked)
    if (switchButton.isEnabled) {
      switchButton.setEnabled(false)
      handler.postDelayed(new Runnable {
        override def run() {
          switchButton.setEnabled(true)
        }
      }, 1000)
    }
    switchButton.setOnCheckedChangeListener(this)
  }

  private def showProgress(msg: String): Handler = {
    clearDialog()
    progressDialog = ProgressDialog.show(this, "", msg, true, false)
    new Handler {
      override def handleMessage(msg: Message) {
        clearDialog()
      }
    }
  }

  private def copyAssets(path: String) {
    val assetManager: AssetManager = getAssets
    var files: Array[String] = null
    try {
      files = assetManager.list(path)
    } catch {
      case e: IOException =>
        Log.e(Shadowsocks.TAG, e.getMessage)
    }
    if (files != null) {
      for (file <- files) {
        var in: InputStream = null
        var out: OutputStream = null
        try {
          if (path.length > 0) {
            in = assetManager.open(path + "/" + file)
          } else {
            in = assetManager.open(file)
          }
          out = new FileOutputStream(Path.BASE + file)
          copyFile(in, out)
          in.close()
          in = null
          out.flush()
          out.close()
          out = null
        } catch {
          case ex: Exception =>
            Log.e(Shadowsocks.TAG, ex.getMessage)
        }
      }
    }
  }

  private def copyFile(in: InputStream, out: OutputStream) {
    val buffer: Array[Byte] = new Array[Byte](1024)
    var read: Int = 0
    while ( {
      read = in.read(buffer)
      read
    } != -1) {
      out.write(buffer, 0, read)
    }
  }

  private def crash_recovery() {
    val ab = new ArrayBuffer[String]

    ab.append("kill -9 `cat /data/data/com.github.shadowsocks/pdnsd.pid`")
    ab.append("kill -9 `cat /data/data/com.github.shadowsocks/ss-local.pid`")
    ab.append("kill -9 `cat /data/data/com.github.shadowsocks/ss-tunnel.pid`")
    ab.append("kill -9 `cat /data/data/com.github.shadowsocks/tun2socks.pid`")
    ab.append("rm /data/data/com.github.shadowsocks/pdnsd.conf")
    ab.append("rm /data/data/com.github.shadowsocks/pdnsd.cache")

    Console.runCommand(ab.toArray)

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      ab.clear()
      ab.append("kill -9 `cat /data/data/com.github.shadowsocks/redsocks.pid`")
      ab.append("rm /data/data/com.github.shadowsocks/redsocks.conf")
      ab.append(Utils.getIptables + " -t nat -F OUTPUT")

      Console.runRootCommand(ab.toArray)
    }
  }

  private def getVersionName: String = {
    var version: String = null
    try {
      val pi: PackageInfo = getPackageManager.getPackageInfo(getPackageName, 0)
      version = pi.versionName
    } catch {
      case e: PackageManager.NameNotFoundException =>
        version = "Package name not found"
    }
    version
  }

  def isTextEmpty(s: String, msg: String): Boolean = {
    if (s == null || s.length <= 0) {
      Crouton.makeText(this, msg, Style.ALERT).show()
      return true
    }
    false
  }

  def cancelStart() {
    handler.postDelayed(new Runnable {
      override def run() {
        clearDialog()
        changeSwitch(checked = false)
      }
    }, 1000)
  }

  def prepareStartService() {
    showProgress(getString(R.string.connecting))
    spawn {
      if (isVpnEnabled) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
          startActivityForResult(intent, Shadowsocks.REQUEST_CONNECT)
        } else {
          onActivityResult(Shadowsocks.REQUEST_CONNECT, Activity.RESULT_OK, null)
        }
      } else {
        if (!serviceStart) {
          cancelStart()
        }
      }
    }
  }

  def onCheckedChanged(compoundButton: CompoundButton, checked: Boolean) {
    if (compoundButton eq switchButton) {
      checked match {
        case true =>
          prepareStartService()
        case false =>
          serviceStop()
      }
      if (switchButton.isEnabled) {
        switchButton.setEnabled(false)
        handler.postDelayed(new Runnable {
          override def run() {
            switchButton.setEnabled(true)
          }
        }, 1000)
      }
    }
  }

  def getLayoutView(view: ViewParent): LinearLayout = {
    view match {
      case layout: LinearLayout => layout
      case _ => if (view != null) getLayoutView(view.getParent) else null
    }
  }

  def initAdView() {
    if (settings.getString(Key.proxy, "") == "198.199.101.152") {
      val layoutView = {
        if (Build.VERSION.SDK_INT > 10) {
          drawer.getContentContainer.getChildAt(0)
        } else {
          getLayoutView(drawer.getContentContainer.getParent)
        }
      }
      if (layoutView != null) {
        val adView = new AdView(this)
        adView.setAdUnitId("ca-app-pub-9097031975646651/7760346322")
        adView.setAdSize(AdSize.SMART_BANNER)
        layoutView.asInstanceOf[ViewGroup].addView(adView, 0)
        adView.loadAd(new AdRequest.Builder().build())
      }
    }
  }

  override def setContentView(layoutResId: Int) {
    drawer.setContentView(layoutResId)
    initAdView()
    onContentChanged()
  }

  override def onCreate(savedInstanceState: Bundle) {

    super.onCreate(savedInstanceState)

    handler = new Handler()

    addPreferencesFromResource(R.xml.pref_all)

    // Initialize the profile
    currentProfile = {
      profileManager.getProfile(settings.getInt(Key.profileId, -1)) getOrElse currentProfile
    }

    // Update the profile
    if (!status.getBoolean(getVersionName, false)) {
      val h = showProgress(getString(R.string.initializing))
      status.edit.putBoolean(getVersionName, true).apply()
      spawn {
        reset()
        currentProfile = profileManager.create()
        h.sendEmptyMessage(0)
      }
    }

    // Initialize drawer
    menuAdapter.setActiveId(settings.getInt(Key.profileId, -1))
    menuAdapter.setListener(this)
    listView.setAdapter(menuAdapter)
    drawer.setMenuView(listView)
    // The drawable that replaces the up indicator in the action bar
    drawer.setSlideDrawable(R.drawable.ic_drawer)
    // Whether the previous drawable should be shown
    drawer.setDrawerIndicatorEnabled(true)
    if (!isSinglePane) {
      drawer.openMenu(false)
    }

    // Initialize action bar
    val switchLayout = getLayoutInflater
      .inflate(R.layout.layout_switch, null)
      .asInstanceOf[RelativeLayout]
    val title: TextView = switchLayout.findViewById(R.id.title).asInstanceOf[TextView]
    val tf: Typeface = Typefaces.get(this, "fonts/Iceland.ttf")
    if (tf != null) title.setTypeface(tf)
    switchButton = switchLayout.findViewById(R.id.switchButton).asInstanceOf[Switch]
    getActionBar.setCustomView(switchLayout)
    getActionBar.setDisplayShowTitleEnabled(false)
    getActionBar.setDisplayShowCustomEnabled(true)
    getActionBar.setIcon(R.drawable.ic_stat_shadowsocks)

    // Register broadcast receiver
    registerReceiver(preferenceReceiver, new IntentFilter(Action.UPDATE_PREFS))

    // Bind to the service
    spawn {
      val isRoot = Build.VERSION.SDK_INT != Build.VERSION_CODES.LOLLIPOP && Console.isRoot
      handler.post(new Runnable {
        override def run() {
          status.edit.putBoolean(Key.isRoot, isRoot).commit()
          attachService()
        }
      })
    }
  }

  def attachService() {
    if (bgService == null) {
      val s = if (!isVpnEnabled) classOf[ShadowsocksNatService] else classOf[ShadowsocksVpnService]
      val intent = new Intent(this, s)
      intent.setAction(Action.SERVICE)
      bindService(intent, connection, Context.BIND_AUTO_CREATE)
      startService(new Intent(this, s))
    }
  }

  def deattachService() {
    if (bgService != null) {
      try {
        bgService.unregisterCallback(callback)
      } catch {
        case ignored: RemoteException => // Nothing
      }
      bgService = null
      unbindService(connection)
    }
  }

  override def onRestoreInstanceState(inState: Bundle) {
    super.onRestoreInstanceState(inState)
    drawer.restoreState(inState.getParcelable(STATE_MENUDRAWER))
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(STATE_MENUDRAWER, drawer.saveState())
    outState.putInt(STATE_ACTIVE_VIEW_ID, currentProfile.id)
  }

  override def onBackPressed() {
    val drawerState = drawer.getDrawerState
    if (drawerState == MenuDrawer.STATE_OPEN || drawerState == MenuDrawer.STATE_OPENING) {
      drawer.closeMenu()
      return
    }
    super.onBackPressed()
  }

  override def onActiveViewChanged(v: View, pos: Int) {
    drawer.setActiveView(v, pos)
  }

  def newProfile(id: Int) {

    val builder = new AlertDialog.Builder(this)
    builder
      .setTitle(R.string.add_profile)
      .setItems(R.array.add_profile_methods, new DialogInterface.OnClickListener() {
      def onClick(dialog: DialogInterface, which: Int) {
        which match {
          case 0 =>
            dialog.dismiss()
            val h = showProgress(getString(R.string.loading))
            h.postDelayed(new Runnable() {
              def run() {
                val integrator = new IntentIntegrator(Shadowsocks.this)
                integrator.initiateScan()
                h.sendEmptyMessage(0)
              }
            }, 600)
          case 1 =>
            dialog.dismiss()
            addProfile(id)
          case _ =>
        }
      }
    })
    builder.create().show()
  }

  def addProfile(profile: Profile) {
    drawer.closeMenu(true)

    val h = showProgress(getString(R.string.loading))

    handler.postDelayed(new Runnable {
      def run() {
        currentProfile = profile
        profileManager.createOrUpdateProfile(currentProfile)
        profileManager.reload(currentProfile.id)
        menuAdapter.updateList(getMenuList, currentProfile.id)

        updatePreferenceScreen()

        h.sendEmptyMessage(0)
      }
    }, 600)
  }

  def addProfile(id: Int) {
    drawer.closeMenu(true)

    val h = showProgress(getString(R.string.loading))

    handler.postDelayed(new Runnable {
      def run() {
        currentProfile = profileManager.reload(id)
        profileManager.save()
        menuAdapter.updateList(getMenuList, currentProfile.id)

        updatePreferenceScreen()

        h.sendEmptyMessage(0)
      }
    }, 600)
  }

  def updateProfile(id: Int) {
    drawer.closeMenu(true)

    val h = showProgress(getString(R.string.loading))

    handler.postDelayed(new Runnable {
      def run() {
        currentProfile = profileManager.reload(id)
        menuAdapter.setActiveId(id)
        menuAdapter.notifyDataSetChanged()

        updatePreferenceScreen()

        h.sendEmptyMessage(0)
      }
    }, 600)
  }

  def delProfile(id: Int): Boolean = {
    drawer.closeMenu(true)

    val profile = profileManager.getProfile(id)

    if (!profile.isDefined) return false

    new AlertDialog.Builder(this)
      .setMessage(String.format(getString(R.string.remove_profile), profile.get.name))
      .setCancelable(false)
      .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
      override def onClick(dialog: DialogInterface, i: Int) = dialog.cancel()
    })
      .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      override def onClick(dialog: DialogInterface, i: Int) {
        profileManager.delProfile(id)
        val profileId = {
          val profiles = profileManager.getAllProfiles.getOrElse(List[Profile]())
          if (profiles.isEmpty) -1 else profiles(0).id
        }
        currentProfile = profileManager.load(profileId)
        menuAdapter.updateList(getMenuList, currentProfile.id)

        sendBroadcast(new Intent(Action.UPDATE_FRAGMENT))

        dialog.dismiss()
      }
    })
      .create()
      .show()

    true
  }

  def getProfileList: List[Item] = {
    val list = profileManager.getAllProfiles getOrElse List[Profile]()
    list.map(p => new Item(p.id, p.name, -1, updateProfile, delProfile))
  }

  def getMenuList: List[Any] = {

    val buf = new ListBuffer[Any]()

    buf += new Category(getString(R.string.profiles))

    buf ++= getProfileList

    buf +=
      new Item(-400, getString(R.string.add_profile), android.R.drawable.ic_menu_add, newProfile)

    buf += new Category(getString(R.string.settings))

    buf += new Item(-100, getString(R.string.recovery), android.R.drawable.ic_menu_revert, _ => {
      // send event
      application.tracker.send(new HitBuilders.EventBuilder()
        .setCategory(Shadowsocks.TAG)
        .setAction("reset")
        .setLabel(getVersionName)
        .build())
      recovery()
    })

    buf +=
      new Item(-200, getString(R.string.flush_dnscache), android.R.drawable.ic_menu_delete, _ => {
        // send event
        application.tracker.send(new HitBuilders.EventBuilder()
          .setCategory(Shadowsocks.TAG)
          .setAction("flush_dnscache")
          .setLabel(getVersionName)
          .build())
        flushDnsCache()
      })

    buf += new Item(-300, getString(R.string.about), android.R.drawable.ic_menu_info_details, _ => {
      // send event
      application.tracker.send(new HitBuilders.EventBuilder()
        .setCategory(Shadowsocks.TAG)
        .setAction("about")
        .setLabel(getVersionName)
        .build())
      showAbout()
    })

    buf.toList
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case android.R.id.home =>
        drawer.toggleMenu()
        return true
    }
    super.onOptionsItemSelected(item)
  }

  protected override def onPause() {
    super.onPause()
    switchButton.setOnCheckedChangeListener(null)
    prepared = false
  }

  protected override def onResume() {
    super.onResume()
    if (bgService != null) {
      bgService.getState match {
        case State.CONNECTED =>
          changeSwitch(checked = true)
        case State.CONNECTING =>
          changeSwitch(checked = true)
        case _ =>
          changeSwitch(checked = false)
      }
      state = bgService.getState
      // set the listener
      switchButton.setOnCheckedChangeListener(Shadowsocks.this)
    }
    ConfigUtils.refresh(this)
  }

  private def setPreferenceEnabled(enabled: Boolean) {
    for (name <- Shadowsocks.PROXY_PREFS) {
      val pref = findPreference(name)
      if (pref != null) {
        pref.setEnabled(enabled)
      }
    }
    for (name <- Shadowsocks.FEATRUE_PREFS) {
      val pref = findPreference(name)
      if (pref != null) {
        if (Seq(Key.isGlobalProxy, Key.proxyedApps)
          .contains(name)) {
          pref.setEnabled(enabled && (!isVpnEnabled || Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP))
        } else {
          pref.setEnabled(enabled)
        }
      }
    }
  }

  private def updatePreferenceScreen() {
    val profile = currentProfile
    for (name <- Shadowsocks.PROXY_PREFS) {
      val pref = findPreference(name)
      Shadowsocks.updatePreference(pref, name, profile)
    }
    for (name <- Shadowsocks.FEATRUE_PREFS) {
      val pref = findPreference(name)
      Shadowsocks.updatePreference(pref, name, profile)
    }
  }

  override def onStart() {
    super.onStart()
  }

  override def onStop() {
    super.onStop()
    clearDialog()
  }

  override def onDestroy() {
    super.onDestroy()
    deattachService()
    Crouton.cancelAllCroutons()
    unregisterReceiver(preferenceReceiver)
    new BackupManager(this).dataChanged()
    if (handler != null) {
      handler.removeCallbacksAndMessages(null)
      handler = null
    }
  }

  def copyToSystem() {
    val ab = new ArrayBuffer[String]
    ab.append("mount -o rw,remount -t yaffs2 /dev/block/mtdblock3 /system")
    for (executable <- Shadowsocks.EXECUTABLES) {
      ab.append("cp %s%s /system/bin/".format(Path.BASE, executable))
      ab.append("chmod 755 /system/bin/" + executable)
      ab.append("chown root:shell /system/bin/" + executable)
    }
    ab.append("mount -o ro,remount -t yaffs2 /dev/block/mtdblock3 /system")
    Console.runRootCommand(ab.toArray)

  }

  def reset() {

    crash_recovery()

    copyAssets(System.getABI)

    val ab = new ArrayBuffer[String]
    for (executable <- Shadowsocks.EXECUTABLES) {
      ab.append("chmod 755 " + Path.BASE + executable)
    }
    Console.runCommand(ab.toArray)

  }

  private def recovery() {
    val h = showProgress(getString(R.string.recovering))
    serviceStop()
    spawn {
      reset()
      h.sendEmptyMessage(0)
    }
  }

  private def flushDnsCache() {
    val h = showProgress(getString(R.string.flushing))
    spawn {
      Utils.toggleAirplaneMode(getBaseContext)
      h.sendEmptyMessage(0)
    }
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
    val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
    if (scanResult != null) {
      Parser.parse(scanResult.getContents) match {
        case Some(profile) => addProfile(profile)
        case _ => // ignore
      }
    } else {
      resultCode match {
        case Activity.RESULT_OK =>
          prepared = true
          if (!serviceStart) {
            cancelStart()
          }
        case _ =>
          cancelStart()
          Log.e(Shadowsocks.TAG, "Failed to start VpnService")
      }
    }
  }

  def isVpnEnabled: Boolean = {
    if (vpnEnabled < 0) {
      vpnEnabled = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP
        || !status.getBoolean(Key.isRoot, false)) {
        1
      } else {
        0
      }
    }
    if (vpnEnabled == 1) true else false
  }

  def serviceStop() {
    if (bgService != null) bgService.stop()
  }

  def checkText(key: String): Boolean = {
    val text = settings.getString(key, "")
    !isTextEmpty(text, getString(R.string.proxy_empty))
  }

  def checkNumber(key: String, low: Boolean): Boolean = {
    val text = settings.getString(key, "")
    if (isTextEmpty(text, getString(R.string.port_empty))) return false
    try {
      val port: Int = Integer.valueOf(text)
      if (!low && port <= 1024) {
        Crouton.makeText(this, R.string.port_alert, Style.ALERT).show()
        return false
      }
    } catch {
      case ex: Exception =>
        Crouton.makeText(this, R.string.port_alert, Style.ALERT).show()
        return false
    }
    true
  }

  /** Called when connect button is clicked. */
  def serviceStart: Boolean = {

    if (!checkText(Key.proxy)) return false
    if (!checkText(Key.sitekey)) return false
    if (!checkNumber(Key.localPort, low = false)) return false
    if (!checkNumber(Key.remotePort, low = true)) return false

    if (bgService == null) return false

    bgService.start(ConfigUtils.load(settings))

    if (isVpnEnabled) {
      changeSwitch(checked = false)
    }
    true
  }

  private def showAbout() {

    val web = new WebView(this)
    web.loadUrl("file:///android_asset/pages/about.html")
    web.setWebViewClient(new WebViewClient() {
      override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
      }
    })

    var versionName = ""
    try {
      versionName = getPackageManager.getPackageInfo(getPackageName, 0).versionName
    } catch {
      case ex: PackageManager.NameNotFoundException =>
        versionName = ""
    }

    new AlertDialog.Builder(this)
      .setTitle(getString(R.string.about_title).format(versionName))
      .setCancelable(false)
      .setNegativeButton(getString(R.string.ok_iknow), new DialogInterface.OnClickListener() {
      override def onClick(dialog: DialogInterface, id: Int) {
        dialog.cancel()
      }
    })
      .setView(web)
      .create()
      .show()
  }

  def clearDialog() {
    if (progressDialog != null) {
      progressDialog.dismiss()
      progressDialog = null
    }
  }

  def onStateChanged(s: Int, m: String) {
    handler.post(new Runnable {
      override def run() {
        if (state != s) {
          state = s
          state match {
            case State.CONNECTING =>
              if (progressDialog == null) {
                progressDialog = ProgressDialog
                  .show(Shadowsocks.this, "", getString(R.string.connecting), true, true)
              }
              setPreferenceEnabled(enabled = false)
            case State.CONNECTED =>
              clearDialog()
              changeSwitch(checked = true)
              setPreferenceEnabled(enabled = false)
            case State.STOPPED =>
              clearDialog()
              changeSwitch(checked = false)
              Crouton.cancelAllCroutons()
              if (m != null) {
                val style = new Style.Builder().setBackgroundColorValue(Style.holoRedLight).build()
                val config = new Configuration.Builder()
                  .setDuration(Configuration.DURATION_LONG)
                  .build()
                Crouton
                  .makeText(Shadowsocks.this, getString(R.string.vpn_error).format(m), style)
                  .setConfiguration(config)
                  .show()
              }
              setPreferenceEnabled(enabled = true)
          }
        }
      }
    })
  }

  class PreferenceBroadcastReceiver extends BroadcastReceiver {
    override def onReceive(context: Context, intent: Intent) {
      currentProfile = profileManager.save()
      menuAdapter.updateList(getMenuList, currentProfile.id)
    }
  }

}

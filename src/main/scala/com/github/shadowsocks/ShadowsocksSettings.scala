package com.github.shadowsocks

import java.lang.System.currentTimeMillis
import java.net.{HttpURLConnection, URL}
import java.util.Locale

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.{DialogInterface, Intent, SharedPreferences}
import android.net.Uri
import android.os.{Build, Bundle}
import android.preference.{Preference, PreferenceFragment, SwitchPreference}
import android.support.v7.app.AlertDialog
import android.webkit.{WebView, WebViewClient}
import com.github.shadowsocks.utils.CloseUtils._
import com.github.shadowsocks.utils.Key

// TODO: Move related logic here
class ShadowsocksSettings extends PreferenceFragment with OnSharedPreferenceChangeListener {
  private def activity = getActivity.asInstanceOf[Shadowsocks]
  lazy val natSwitch = findPreference(Key.isNAT).asInstanceOf[SwitchPreference]
  var stat: Preference = _

  private var isProxyApps: SwitchPreference = _
  private var testCount: Int = _

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    addPreferencesFromResource(R.xml.pref_all)
    getPreferenceManager.getSharedPreferences.registerOnSharedPreferenceChangeListener(this)

    stat = findPreference(Key.stat)
    stat.setOnPreferenceClickListener(_ => {
      val id = synchronized {
        testCount += 1
        activity.connectionTestResult = getString(R.string.connection_test_testing)
        activity.updateTraffic()
        testCount
      }
      ThrowableFuture {
        // Based on: https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/connectivity/NetworkMonitor.java#640
        autoDisconnect(new URL("https", "www.google.com", "/generate_204").openConnection()
          .asInstanceOf[HttpURLConnection]) { conn =>
          conn.setConnectTimeout(5 * 1000)
          conn.setReadTimeout(5 * 1000)
          conn.setInstanceFollowRedirects(false)
          conn.setUseCaches(false)
          if (testCount == id) {
            var result: String = null
            try {
              val start = currentTimeMillis
              conn.getInputStream
              val elapsed = currentTimeMillis - start
              val code = conn.getResponseCode
              if (code == 204 || code == 200 && conn.getContentLength == 0)
                result = getString(R.string.connection_test_available, elapsed: java.lang.Long)
              else throw new Exception(getString(R.string.connection_test_error_status_code, code: Integer))
            } catch {
              case e: Exception => result = getString(R.string.connection_test_error, e.getMessage)
            }
            synchronized(if (testCount == id) {
              activity.connectionTestResult = result
              activity.handler.post(activity.updateTraffic)
            })
          }
        }
      }
      true
    })

    isProxyApps = findPreference(Key.isProxyApps).asInstanceOf[SwitchPreference]
    isProxyApps.setOnPreferenceClickListener((preference: Preference) => {
      startActivity(new Intent(activity, classOf[AppManager]))
      isProxyApps.setChecked(true)
      false
    })

    findPreference("recovery").setOnPreferenceClickListener((preference: Preference) => {
      ShadowsocksApplication.track(Shadowsocks.TAG, "reset")
      activity.recovery()
      true
    })

    val flush = findPreference("flush_dnscache")
    if (Build.VERSION.SDK_INT < 17) flush.setSummary(R.string.flush_dnscache_summary)
    flush.setOnPreferenceClickListener(_ => {
      ShadowsocksApplication.track(Shadowsocks.TAG, "flush_dnscache")
      activity.flushDnsCache()
      true
    })

    findPreference("about").setOnPreferenceClickListener((preference: Preference) => {
      ShadowsocksApplication.track(Shadowsocks.TAG, "about")
      val web = new WebView(activity)
      web.loadUrl("file:///android_asset/pages/about.html")
      web.setWebViewClient(new WebViewClient() {
        override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
          startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))
          true
        }
      })

      new AlertDialog.Builder(activity)
        .setTitle(getString(R.string.about_title).formatLocal(Locale.ENGLISH, ShadowsocksApplication.getVersionName))
        .setCancelable(false)
        .setNegativeButton(getString(android.R.string.ok),
          ((dialog: DialogInterface, id: Int) => dialog.cancel()): DialogInterface.OnClickListener)
        .setView(web)
        .create()
        .show()
      true
    })
  }

  override def onResume {
    super.onResume()
    isProxyApps.setChecked(ShadowsocksApplication.settings.getBoolean(Key.isProxyApps, false))  // update
  }

  override def onPause {
    super.onPause()
    ShadowsocksApplication.settings.unregisterOnSharedPreferenceChangeListener(this)
  }

  def onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) = key match {
    case Key.isNAT => activity.handler.post(() => {
      activity.deattachService
      activity.attachService
    })
    case _ =>
  }
}

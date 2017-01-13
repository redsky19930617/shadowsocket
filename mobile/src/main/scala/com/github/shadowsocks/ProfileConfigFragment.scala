/*******************************************************************************/
/*                                                                             */
/*  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          */
/*  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  */
/*                                                                             */
/*  This program is free software: you can redistribute it and/or modify       */
/*  it under the terms of the GNU General Public License as published by       */
/*  the Free Software Foundation, either version 3 of the License, or          */
/*  (at your option) any later version.                                        */
/*                                                                             */
/*  This program is distributed in the hope that it will be useful,            */
/*  but WITHOUT ANY WARRANTY; without even the implied warranty of             */
/*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              */
/*  GNU General Public License for more details.                               */
/*                                                                             */
/*  You should have received a copy of the GNU General Public License          */
/*  along with this program. If not, see <http://www.gnu.org/licenses/>.       */
/*                                                                             */
/*******************************************************************************/

package com.github.shadowsocks

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.{DialogInterface, Intent, SharedPreferences}
import android.os.{Build, Bundle, UserManager}
import android.support.design.widget.Snackbar
import android.support.v14.preference.SwitchPreference
import android.support.v7.app.AlertDialog
import android.support.v7.preference.Preference
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.text.TextUtils
import android.view.MenuItem
import be.mygod.preference.{EditTextPreference, PreferenceFragment}
import com.github.shadowsocks.ShadowsocksApplication.app
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.plugin.{PluginConfiguration, PluginInterface, PluginManager, PluginOptions}
import com.github.shadowsocks.preference.{IconListPreference, PluginConfigurationDialogFragment}
import com.github.shadowsocks.utils.{Action, Key, Utils}

object ProfileConfigFragment {
  private final val REQUEST_CODE_CONFIGURE = 1
}

class ProfileConfigFragment extends PreferenceFragment with OnMenuItemClickListener
  with OnSharedPreferenceChangeListener {
  import ProfileConfigFragment._

  private var profile: Profile = _
  private var isProxyApps: SwitchPreference = _
  private var plugin: IconListPreference = _
  private var pluginConfigure: EditTextPreference = _
  private var pluginConfiguration: PluginConfiguration = _

  override def onCreatePreferences(bundle: Bundle, key: String) {
    app.profileManager.getProfile(getActivity.getIntent.getIntExtra(Action.EXTRA_PROFILE_ID, -1)) match {
      case Some(p) =>
        profile = p
        profile.serialize(app.editor).apply()
      case None => getActivity.finish()
    }
    addPreferencesFromResource(R.xml.pref_profile)
    if (Build.VERSION.SDK_INT >= 25 && getActivity.getSystemService(classOf[UserManager]).isDemoUser) {
      findPreference(Key.host).setSummary("shadowsocks.example.org")
      findPreference(Key.remotePort).setSummary("1337")
      findPreference(Key.password).setSummary("\u2022" * 32)
    }
    isProxyApps = findPreference(Key.proxyApps).asInstanceOf[SwitchPreference]
    isProxyApps.setEnabled(Utils.isLollipopOrAbove || app.isNatEnabled)
    isProxyApps.setOnPreferenceClickListener(_ => {
      startActivity(new Intent(getActivity, classOf[AppManager]))
      isProxyApps.setChecked(true)
      false
    })
    plugin = findPreference(Key.plugin).asInstanceOf[IconListPreference]
    pluginConfigure = findPreference("plugin.configure").asInstanceOf[EditTextPreference]
    plugin.unknownValueSummary = getString(R.string.plugin_unknown)
    plugin.setOnPreferenceChangeListener((_, value) => {
      val selected = value.asInstanceOf[String]
      pluginConfiguration = new PluginConfiguration(pluginConfiguration.pluginsOptions, selected)
      app.editor.putString(Key.plugin, pluginConfiguration.toString).putBoolean(Key.dirty, true).apply()
      pluginConfigure.setEnabled(!TextUtils.isEmpty(selected))
      true
    })
    pluginConfigure.setOnPreferenceChangeListener((_, value) => try {
      val selected = pluginConfiguration.selected
      pluginConfiguration = new PluginConfiguration(pluginConfiguration.pluginsOptions +
        (pluginConfiguration.selected -> new PluginOptions(selected, value.asInstanceOf[String])), selected)
      app.editor.putString(Key.plugin, pluginConfiguration.toString).putBoolean(Key.dirty, true).apply()
      true
    } catch {
      case exc: IllegalArgumentException =>
        Snackbar.make(getActivity.findViewById(R.id.snackbar), exc.getLocalizedMessage, Snackbar.LENGTH_LONG).show()
        false
    })
    initPlugins()
    app.listenForPackageChanges(getView.post(initPlugins))
    app.settings.registerOnSharedPreferenceChangeListener(this)
  }

  def initPlugins() {
    val plugins = PluginManager.fetchPlugins()
    plugin.setEntries(plugins.map(_.label))
    plugin.setEntryValues(plugins.map(_.id.asInstanceOf[CharSequence]))
    plugin.setEntryIcons(plugins.map(_.icon))
    pluginConfiguration = new PluginConfiguration(app.settings.getString(Key.plugin, null))
    plugin.setValue(pluginConfiguration.selected)
    plugin.checkSummary()
    pluginConfigure.setEnabled(!TextUtils.isEmpty(pluginConfiguration.selected))
    pluginConfigure.setText(pluginConfiguration.selectedOptions.toString)
  }

  override def onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String): Unit =
    if (key != Key.proxyApps && findPreference(key) != null) app.editor.putBoolean(Key.dirty, true).apply()

  override def onDestroy() {
    app.settings.unregisterOnSharedPreferenceChangeListener(this)
    super.onDestroy()
  }

  override def onResume() {
    super.onResume()
    isProxyApps.setChecked(app.settings.getBoolean(Key.proxyApps, false)) // fetch proxyApps updated by AppManager
  }

  override def onDisplayPreferenceDialog(preference: Preference): Unit = if (preference eq pluginConfigure) {
    val selected = pluginConfiguration.selected
    val intent = new Intent(PluginInterface.ACTION_CONFIGURE(selected))
    if (intent.resolveActivity(getActivity.getPackageManager) != null)
      startActivityForResult(intent.putExtra(PluginInterface.EXTRA_OPTIONS,
        pluginConfiguration.selectedOptions.toString), REQUEST_CODE_CONFIGURE) else {
      val bundle = new Bundle()
      bundle.putString(PluginConfigurationDialogFragment.PLUGIN_ID_FRAGMENT_TAG, selected)
      displayPreferenceDialog(preference.getKey, new PluginConfigurationDialogFragment, bundle)
    }
  } else super.onDisplayPreferenceDialog(preference)

  override def onMenuItemClick(item: MenuItem): Boolean = item.getItemId match {
    case R.id.action_delete =>
      new AlertDialog.Builder(getActivity)
        .setTitle(R.string.delete_confirm_prompt)
        .setPositiveButton(R.string.yes, ((_, _) => {
          app.profileManager.delProfile(profile.id)
          getActivity.finish()
        }): DialogInterface.OnClickListener)
        .setNegativeButton(R.string.no, null)
        .create()
        .show()
      true
    case R.id.action_apply =>
      saveAndExit()
      true
    case _ => false
  }

  def saveAndExit() {
    profile.deserialize(app.settings)
    app.profileManager.updateProfile(profile)
    if (ProfilesFragment.instance != null) ProfilesFragment.instance.profilesAdapter.deepRefreshId(profile.id)
    getActivity.finish()
  }
}

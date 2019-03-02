/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.shadowsocks.Core.app
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.DirectBoot
import com.github.shadowsocks.utils.Key
import com.google.android.material.snackbar.Snackbar
import com.google.common.collect.Multimap
import com.google.common.collect.MultimapBuilder
import kotlinx.coroutines.*

class AppManager : AppCompatActivity() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: AppManager? = null

        private var receiver: BroadcastReceiver? = null
        private var cachedApps: List<PackageInfo>? = null
        private suspend fun getApps(pm: PackageManager) = synchronized(AppManager) {
            if (receiver == null) receiver = Core.listenForPackageChanges {
                synchronized(AppManager) {
                    receiver = null
                    cachedApps = null
                }
                AppManager.instance?.loadApps()
            }
            // Labels and icons can change on configuration (locale, etc.) changes, therefore they are not cached.
            val cachedApps = cachedApps ?: pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                    .filter {
                        when (it.packageName) {
                            app.packageName -> false
                            "android" -> true
                            else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) ?: false
                        }
                    }
            this.cachedApps = cachedApps
            cachedApps
        }.map {
            yield()
            ProxiedApp(pm, it.applicationInfo, it.packageName)
        }
    }

    private class ProxiedApp(private val pm: PackageManager, private val appInfo: ApplicationInfo,
                             val packageName: String) {
        val name: CharSequence = appInfo.loadLabel(pm)    // cached for sorting
        val icon: Drawable get() = appInfo.loadIcon(pm)
        val uid get() = appInfo.uid
    }

    private inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view), View.OnClickListener {
        private val icon = view.findViewById<ImageView>(R.id.itemicon)
        private val check = view.findViewById<Switch>(R.id.itemcheck)
        private val tvTitle = view.findViewById<TextView>(R.id.title)
        private val tvDesc = view.findViewById<TextView>(R.id.desc)
        private lateinit var item: ProxiedApp

        init {
            view.setOnClickListener(this)
        }

        @SuppressLint("SetTextI18n")
        fun bind(app: ProxiedApp) {
            this.item = app

            icon.setImageDrawable(app.icon)
            tvTitle.text = app.name
            tvDesc.text = "${app.packageName} (${app.uid})"
            check.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains("switch")) {
                check.isChecked = isProxiedApp(item)
            }
        }

        override fun onClick(v: View?) {
            if (isProxiedApp(item)) {
                val list = proxiedUidMap.removeAll(item.uid)
                proxiedApps.removeAll(list)
            } else {
                proxiedApps.add(item.packageName)
                proxiedUidMap.put(item.uid, item.packageName)
            }
            DataStore.individual = proxiedApps.joinToString("\n")
            DataStore.dirty = true

            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, "switch")
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(), Filterable {
        private var filteredApps = apps

        suspend fun reload() {
            val list = getApps(packageManager)

            val map = MultimapBuilder.treeKeys().arrayListValues().build<Int, String>()
            list.filter { app ->
                app.packageName in proxiedApps
            }.forEach { app ->
                map.put(app.uid, app.packageName)
            }
            proxiedUidMap = map

            apps = list.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
            filteredApps = apps
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) = holder.bind(filteredApps[position])
        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
                AppViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.layout_apps_item, parent, false))
        override fun getItemCount(): Int = filteredApps.size

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence): FilterResults {
                    val filteredApps = if (constraint.isEmpty()) {
                        apps
                    } else {
                        apps.filter {
                            it.name.contains(constraint, true) ||
                                    it.packageName.contains(constraint, true) ||
                                    it.uid.toString().contains(constraint)
                        }
                    }

                    return FilterResults().also {
                        it.count = filteredApps.size
                        it.values = filteredApps
                    }
                }

                override fun publishResults(constraint: CharSequence, results: FilterResults) {
                    @Suppress("UNCHECKED_CAST")
                    filteredApps = results.values as List<ProxiedApp>
                    notifyDataSetChanged()
                }
            }
        }
    }

    private lateinit var proxiedApps: HashSet<String>
    private lateinit var proxiedUidMap: Multimap<Int, String>
    private lateinit var toolbar: Toolbar
    private lateinit var bypassSwitch: RadioButton
    private lateinit var appListView: RecyclerView
    private lateinit var loadingView: View
    private lateinit var editQuery: EditText
    private lateinit var appsAdapter: AppsAdapter
    private val clipboard by lazy { getSystemService<ClipboardManager>()!! }
    private var loader: Job? = null
    private var apps = listOf<ProxiedApp>()

    private val shortAnimTime by lazy { resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
    private fun View.crossFadeFrom(other: View) {
        clearAnimation()
        other.clearAnimation()
        if (visibility == View.VISIBLE && other.visibility == View.GONE) return
        alpha = 0F
        visibility = View.VISIBLE
        animate().alpha(1F).duration = shortAnimTime
        other.animate().alpha(0F).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                other.visibility = View.GONE
            }
        }).duration = shortAnimTime
    }

    private fun initProxiedApps(str: String = DataStore.individual) {
        proxiedApps = str.split('\n').toHashSet()
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUidMap.containsKey(app.uid)

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = GlobalScope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED) {
            loadingView.crossFadeFrom(appListView)
            val adapter = appListView.adapter as AppsAdapter
            withContext(Dispatchers.IO) { adapter.reload() }

            val queryText = editQuery.text
            if (queryText.isEmpty()) {
                adapter.notifyDataSetChanged()
            } else {
                adapter.filter.filter(queryText)
            }

            appListView.crossFadeFrom(loadingView)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_apps)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (!DataStore.proxyApps) {
            DataStore.proxyApps = true
            DataStore.dirty = true
        }

        val switchListener = { switch: Boolean ->
            DataStore.proxyApps = switch
            DataStore.dirty = true
            if (!switch) {
                finish()
            }
        }
        val btnOn = findViewById<RadioButton>(R.id.btn_on)
        val btnOff = findViewById<RadioButton>(R.id.btn_off)
        (if (DataStore.proxyApps) btnOn else btnOff).isChecked = true

        btnOn.setOnCheckedChangeListener { _, b ->
            if (b) switchListener(true)
        }
        btnOff.setOnCheckedChangeListener { _, b ->
            if (b) switchListener(false)
        }

        bypassSwitch = findViewById(R.id.btn_bypass)
        bypassSwitch.isChecked = DataStore.bypass
        bypassSwitch.setOnCheckedChangeListener { _, checked ->
            DataStore.bypass = checked
            DataStore.dirty = true
        }

        initProxiedApps()
        loadingView = findViewById(R.id.loading)
        appListView = findViewById(R.id.list)
        appListView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        appListView.itemAnimator = DefaultItemAnimator()
        appsAdapter = AppsAdapter()
        appListView.adapter = appsAdapter

        editQuery = findViewById(R.id.edit_query)
        editQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                appsAdapter.filter.filter(s)
            }
        })

        instance = this
        loadApps()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.app_manager_menu, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_apply_all -> {
                val profiles = ProfileManager.getAllProfiles()
                if (profiles != null) {
                    val proxiedAppString = DataStore.individual
                    profiles.forEach {
                        it.individual = proxiedAppString
                        ProfileManager.updateProfile(it)
                    }
                    if (DataStore.directBootAware) DirectBoot.update()
                    Snackbar.make(appListView, R.string.action_apply_all, Snackbar.LENGTH_LONG).show()
                } else Snackbar.make(appListView, R.string.action_export_err, Snackbar.LENGTH_LONG).show()
                return true
            }
            R.id.action_export_clipboard -> {
                clipboard.primaryClip = ClipData.newPlainText(Key.individual,
                        "${DataStore.bypass}\n${DataStore.individual}")
                Snackbar.make(appListView, R.string.action_export_msg, Snackbar.LENGTH_LONG).show()
                return true
            }
            R.id.action_import_clipboard -> {
                val proxiedAppString = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (enabled, apps) = if (i < 0) Pair(proxiedAppString, "") else
                            Pair(proxiedAppString.substring(0, i), proxiedAppString.substring(i + 1))
                        bypassSwitch.isChecked = enabled.toBoolean()
                        DataStore.individual = apps
                        DataStore.dirty = true
                        Snackbar.make(appListView, R.string.action_import_msg, Snackbar.LENGTH_LONG).show()
                        initProxiedApps(apps)
                        loadApps()
                        return true
                    } catch (_: IllegalArgumentException) { }
                }
                Snackbar.make(appListView, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU)
        if (toolbar.isOverflowMenuShowing) toolbar.hideOverflowMenu() else toolbar.showOverflowMenu()
    else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        instance = null
        loader?.cancel()
        super.onDestroy()
    }
}

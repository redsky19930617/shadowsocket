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

package com.github.shadowsocks.plugin

import android.content.pm.ComponentInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import com.github.shadowsocks.Core
import com.github.shadowsocks.Core.app
import com.github.shadowsocks.plugin.PluginManager.loadString
import com.github.shadowsocks.utils.signaturesCompat

abstract class ResolvedPlugin(protected val resolveInfo: ResolveInfo) : Plugin() {
    protected abstract val componentInfo: ComponentInfo
    private val resources by lazy { app.packageManager.getResourcesForApplication(componentInfo.applicationInfo) }

    override val id by lazy { componentInfo.metaData.loadString(PluginContract.METADATA_KEY_ID) { resources }!! }
    override val idAliases: Array<String> by lazy {
        when (val value = componentInfo.metaData.get(PluginContract.METADATA_KEY_ID_ALIASES)) {
            is String -> arrayOf(value)
            is Int -> when (resources.getResourceTypeName(value)) {
                "string" -> arrayOf(resources.getString(value))
                else -> resources.getStringArray(value)
            }
            null -> emptyArray()
            else -> error("unknown type for plugin meta-data idAliases")
        }
    }
    override val label: CharSequence get() = resolveInfo.loadLabel(app.packageManager)
    override val icon: Drawable get() = resolveInfo.loadIcon(app.packageManager)
    override val defaultConfig by lazy {
        componentInfo.metaData.loadString(PluginContract.METADATA_KEY_DEFAULT_CONFIG) { resources }
    }
    override val packageName: String get() = componentInfo.packageName
    override val trusted by lazy {
        Core.getPackageInfo(packageName).signaturesCompat.any(PluginManager.trustedSignatures::contains)
    }
}

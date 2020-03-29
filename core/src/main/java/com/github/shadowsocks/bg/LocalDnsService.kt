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

package com.github.shadowsocks.bg

import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.acl.AclMatcher
import com.github.shadowsocks.net.HostsFile
import com.github.shadowsocks.net.LocalDnsServer
import com.github.shadowsocks.net.Socks5Endpoint
import com.github.shadowsocks.preference.DataStore
import kotlinx.coroutines.CoroutineScope
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.*

object LocalDnsService {
    private val servers = WeakHashMap<Interface, LocalDnsServer>()

    interface Interface : BaseService.Interface {
        override suspend fun startProcesses(hosts: HostsFile) {
            super.startProcesses(hosts)
            LocalDnsServer(this::resolver, hosts).also {
                servers[this] = it
            }.start(InetSocketAddress(DataStore.listenAddress, 8153))
        }

        override fun killProcesses(scope: CoroutineScope) {
            servers.remove(this)?.shutdown(scope)
            super.killProcesses(scope)
        }
    }
}

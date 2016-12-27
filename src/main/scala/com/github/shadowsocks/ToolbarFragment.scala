/*******************************************************************************/
/*                                                                             */
/*  Copyright (C) 2016 by Max Lv <max.c.lv@gmail.com>                          */
/*  Copyright (C) 2016 by Mygod Studio <contact-shadowsocks-android@mygod.be>  */
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

import android.app.Fragment
import android.os.Bundle
import android.support.v7.widget.Toolbar
import android.view.View

class ToolbarFragment extends Fragment {
  var toolbar: Toolbar = _

  override def onViewCreated(view: View, savedInstanceState: Bundle) {
    super.onViewCreated(view, savedInstanceState)
    toolbar = view.findViewById(R.id.toolbar).asInstanceOf[Toolbar]
    val activity = getActivity.asInstanceOf[MainActivity]
    try activity.drawer.setToolbar(activity, toolbar, true) catch {
      case _: Exception => // ignore for now
    }
  }

  def onTrafficUpdated(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long): Unit = ()
}

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

import java.util.{Timer, TimerTask}

import android.app.Service
import android.content.Context
import android.os.{Handler, RemoteCallbackList}
import com.github.shadowsocks.aidl.{Config, IShadowsocksService, IShadowsocksServiceCallback}
import com.github.shadowsocks.utils.{State, TrafficMonitor, TrafficMonitorThread}

trait BaseService extends Service {

  @volatile private var state = State.STOPPED
  @volatile private var callbackCount = 0
  @volatile protected var config: Config = null

  var timer: Timer = null
  var trafficMonitorThread: TrafficMonitorThread = null

  final val callbacks = new RemoteCallbackList[IShadowsocksServiceCallback]

  val binder = new IShadowsocksService.Stub {
    override def getMode: Int = {
      getServiceMode
    }

    override def getState: Int = {
      state
    }

    override def unregisterCallback(cb: IShadowsocksServiceCallback) {
      if (cb != null ) {
        callbacks.unregister(cb)
        callbackCount -= 1
      }
      if (callbackCount == 0 && timer != null) {
        timer.cancel()
        timer = null
      }
      if (callbackCount == 0 && state == State.STOPPED) {
        stopBackgroundService()
      }
    }

    override def registerCallback(cb: IShadowsocksServiceCallback) {
      if (cb != null) {
        if (callbackCount == 0 && timer == null) {
          val task = new TimerTask {
            def run {
              TrafficMonitor.updateRate()
              updateTrafficRate()
            }
          }
          timer = new Timer(true)
          timer.schedule(task, 1000, 1000)
        }
        callbacks.register(cb)
        callbackCount += 1
      }
    }

    override def stop() {
      if (state == State.CONNECTED) {
        stopRunner()
      }
    }

    override def start(config: Config) {
      if (state == State.STOPPED) {
        startRunner(config)
      }
    }
  }

  def startRunner(config: Config) {
    this.config = config

    TrafficMonitor.reset()
    trafficMonitorThread = new TrafficMonitorThread()
    trafficMonitorThread.start()
  }

  def stopRunner() {
    // Make sure update total traffic when stopping the runner
    updateTrafficTotal(TrafficMonitor.txTotal, TrafficMonitor.rxTotal)

    TrafficMonitor.reset()
    if (trafficMonitorThread != null) {
      trafficMonitorThread.stopThread()
      trafficMonitorThread = null
    }
  }

  def updateTrafficTotal(tx: Long, rx: Long) {
    val config = this.config  // avoid race conditions without locking
    if (config != null) {
      ShadowsocksApplication.profileManager.getProfile(config.profileId) match {
        case Some(profile) =>
          profile.tx += tx
          profile.rx += rx
          ShadowsocksApplication.profileManager.updateProfile(profile)
        case None => // Ignore
      }
    }
  }

  def stopBackgroundService()
  def getServiceMode: Int
  def getTag: String
  def getContext: Context

  def getCallbackCount: Int = {
    callbackCount
  }
  def getState: Int = {
    state
  }
  def changeState(s: Int) {
    changeState(s, null)
  }

  def updateTrafficRate() {
    val handler = new Handler(getContext.getMainLooper)
    handler.post(() => {
      if (callbackCount > 0) {
        val txRate = TrafficMonitor.getTxRate
        val rxRate = TrafficMonitor.getRxRate
        val txTotal = TrafficMonitor.getTxTotal
        val rxTotal = TrafficMonitor.getRxTotal
        val n = callbacks.beginBroadcast()
        for (i <- 0 until n) {
          try {
            callbacks.getBroadcastItem(i).trafficUpdated(txRate, rxRate, txTotal, rxTotal)
          } catch {
            case _: Exception => // Ignore
          }
        }
        callbacks.finishBroadcast()
      }
    })
  }

  protected def changeState(s: Int, msg: String) {
    val handler = new Handler(getContext.getMainLooper)
    handler.post(() => if (state != s) {
      if (callbackCount > 0) {
        val n = callbacks.beginBroadcast()
        for (i <- 0 until n) {
          try {
            callbacks.getBroadcastItem(i).stateChanged(s, msg)
          } catch {
            case _: Exception => // Ignore
          }
        }
        callbacks.finishBroadcast()
      }
      state = s
    })
  }
}

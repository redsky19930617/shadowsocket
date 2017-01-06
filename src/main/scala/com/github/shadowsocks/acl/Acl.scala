package com.github.shadowsocks.acl

import java.io.FileNotFoundException

import com.github.shadowsocks.ShadowsocksApplication.app
import com.github.shadowsocks.utils.IOUtils
import com.j256.ormlite.field.DatabaseField

import scala.collection.mutable
import scala.io.Source

/**
  * ACL handler compliant with: src/main/jni/shadowsocks-libev/src/acl.c
  *
  * OrmLite integration is unused for now.
  *
  * @author Mygod
  */
class Acl {
  @DatabaseField(generatedId = true)
  var id: Int = _
  val bypassHostnames = new mutable.SortedList[String]()
  val proxyHostnames = new mutable.SortedList[String]()
  val subnets = new mutable.SortedList[Subnet]()
  @DatabaseField
  var bypass: Boolean = _

  def getBypassHostnamesString: String = bypassHostnames.mkString("\n")
  def getProxyHostnamesString: String = proxyHostnames.mkString("\n")
  def getSubnetsString: String = subnets.mkString("\n")
  def setBypassHostnamesString(value: String) {
    bypassHostnames.clear()
    bypassHostnames ++= value.split("\n")
  }
  def setProxyHostnamesString(value: String) {
    proxyHostnames.clear()
    proxyHostnames ++= value.split("\n")
  }
  def setSubnetsString(value: String) {
    subnets.clear()
    subnets ++= value.split("\n").map(Subnet.fromString)
  }

  def fromAcl(other: Acl): Acl = {
    bypassHostnames.clear()
    bypassHostnames ++= other.bypassHostnames
    proxyHostnames.clear()
    proxyHostnames ++= other.proxyHostnames
    subnets.clear()
    subnets ++= other.subnets
    bypass = other.bypass
    this
  }
  def fromSource(value: Source): Acl = {
    bypassHostnames.clear()
    proxyHostnames.clear()
    this.subnets.clear()
    bypass = false
    lazy val bypassSubnets = new mutable.SortedList[Subnet]()
    lazy val proxySubnets = new mutable.SortedList[Subnet]()
    var hostnames: mutable.SortedList[String] = proxyHostnames
    var subnets: mutable.SortedList[Subnet] = proxySubnets
    for (line <- value.getLines()) (line.indexOf('#') match {
      case -1 => line
      case index => line.substring(0, index)  // trim comments
    }).trim match {
      case "[outbound_block_list]" =>
        hostnames = null
        subnets = null
      case "[black_list]" | "[bypass_list]" =>
        hostnames = bypassHostnames
        subnets = bypassSubnets
      case "[white_list]" | "[proxy_list]" =>
        hostnames = proxyHostnames
        subnets = proxySubnets
      case "[reject_all]" | "[bypass_all]" => bypass = true
      case "[accept_all]" | "[proxy_all]" => bypass = false
      case input if subnets != null && input.nonEmpty => try subnets += Subnet.fromString(input) catch {
        case _: IllegalArgumentException => hostnames += input
      }
      case _ =>
    }
    this.subnets ++= (if (bypass) bypassSubnets else proxySubnets)
    this
  }
  final def fromId(id: String): Acl = fromSource(Source.fromFile(Acl.getPath(id)))

  override def toString: String = {
    val result = new StringBuilder()
    result.append(if (bypass) "[bypass_all]\n" else "[proxy_all]\n")
    var bypassList = bypassHostnames.toStream
    var proxyList = proxyHostnames.toStream
    if (bypass) bypassList = subnets.toStream.map(_.toString) #::: bypassList
    else proxyList = subnets.toStream.map(_.toString) #::: proxyList
    if (bypassList.nonEmpty) {
      result.append("[bypass_list]\n")
      result.append(bypassList.mkString("\n"))
      result.append('\n')
    }
    if (proxyList.nonEmpty) {
      result.append("[proxy_list]\n")
      result.append(proxyList.mkString("\n"))
      result.append('\n')
    }
    result.toString
  }

  def isValidCustomRules: Boolean = bypass && bypassHostnames.isEmpty

  // Don't change: dummy fields for OrmLite interaction

  // noinspection ScalaUnusedSymbol
  @DatabaseField(useGetSet = true)
  private val bypassHostnamesString: String = null
  // noinspection ScalaUnusedSymbol
  @DatabaseField(useGetSet = true)
  private val proxyHostnamesString: String = null
  // noinspection ScalaUnusedSymbol
  @DatabaseField(useGetSet = true)
  private val subnetsString: String = null
}

object Acl {
  final val ALL = "all"
  final val BYPASS_LAN = "bypass-lan"
  final val BYPASS_CHN = "bypass-china"
  final val BYPASS_LAN_CHN = "bypass-lan-china"
  final val GFWLIST = "gfwlist"
  final val CHINALIST = "china-list"
  final val CUSTOM_RULES = "custom-rules"

  def getPath(id: String): String = app.getApplicationInfo.dataDir + '/' + id + ".acl"
  def customRules: Acl = {
    val acl = new Acl()
    try acl.fromId(CUSTOM_RULES) catch {
      case _: FileNotFoundException =>
    }
    acl.bypass = true
    acl.bypassHostnames.clear() // everything is bypassed
    acl
  }
  def save(id: String, acl: Acl): Unit = IOUtils.writeString(getPath(id), acl.toString)
}

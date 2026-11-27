/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.provisioning

import android.util.Xml
import org.signal.core.util.logging.Log
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Parses the GSMA OMA-DM style client-provisioning document returned by an RCS autoconfiguration
 * server (RCC.14 §2.6 / RCC.07). The document is a tree of
 * `<characteristic type="...">` nodes each holding `<parm name="..." value="..."/>` leaves.
 *
 * This parser walks the characteristics the messaging stack needs (APPLICATION/IMS, SERVICES,
 * IM/CHAT, FT, and the top-level validity token) and ignores the rest.
 */
object RcsConfigParser {

  private val TAG = Log.tag(RcsConfigParser::class.java)

  class InvalidConfigException(message: String) : Exception(message)

  @Throws(InvalidConfigException::class)
  fun parse(xml: String): RcsConfig {
    val root = parseCharacteristic(xml)

    val version = root.firstParm("version") ?: "0"
    val validity = root.firstParm("validity")?.toLongOrNull() ?: DEFAULT_VALIDITY_SECONDS

    val ims = root.find("APPLICATION") { it.firstParm("AppID")?.contains("ims", ignoreCase = true) == true }
      ?: root.find("APPLICATION")
      ?: throw InvalidConfigException("No IMS APPLICATION characteristic")

    val rawProxy = ims.deep("LBO_P-CSCF_Address")?.firstParm("Address")
      ?: ims.firstParm("PCSCF_Address")
      ?: throw InvalidConfigException("No P-CSCF address")

    // The P-CSCF address may be a bare host or "host:port".
    val proxyHost = rawProxy.substringBefore(':')
    val proxyPort = rawProxy.substringAfter(':', "").toIntOrNull() ?: DEFAULT_SIP_TLS_PORT

    val sip = RcsConfig.Sip(
      privateUserId = ims.firstParm("Private_User_Identity") ?: throw InvalidConfigException("No private user id"),
      publicUserId = ims.firstParm("Public_User_Identity") ?: throw InvalidConfigException("No public user id"),
      homeDomain = ims.firstParm("Home_network_domain_name") ?: throw InvalidConfigException("No home domain"),
      proxyAddress = proxyHost,
      proxyPort = proxyPort,
      transport = ims.firstParm("Psk_transport") ?: ims.firstParm("Transport") ?: "SIPoTLS",
      realm = ims.firstParm("Realm") ?: ims.firstParm("Home_network_domain_name") ?: "",
      authType = ims.firstParm("AuthType") ?: "Digest"
    )

    val services = root.find("SERVICES")
    val im = root.deep("IM") ?: root.find("APPLICATION") { it.firstParm("AppID")?.contains("rcs", ignoreCase = true) == true }
    val ft = root.deep("FT")

    val msrp = RcsConfig.Msrp(
      authUser = im?.firstParm("imAuthUser") ?: sip.privateUserId,
      authPassword = im?.firstParm("imAuthPwd") ?: "",
      maxMessageSizeBytes = im?.firstParm("MaxSizeImSession")?.toLongOrNull() ?: DEFAULT_MAX_MESSAGE_BYTES
    )

    val capabilities = RcsConfig.Capabilities(
      chat = services?.firstParm("ChatAuth")?.booleanParm() ?: true,
      fileTransfer = services?.firstParm("ftAuth")?.booleanParm() ?: (ft != null),
      fileTransferMaxSizeBytes = ft?.firstParm("FtMaxSize")?.toLongOrNull() ?: DEFAULT_FT_MAX_BYTES,
      groupChat = services?.firstParm("groupChatAuth")?.booleanParm() ?: true,
      isComposing = im?.firstParm("imCapAlwaysON")?.booleanParm() ?: true,
      deliveryReports = true,
      displayReports = im?.firstParm("imCapAlwaysON")?.booleanParm() ?: true,
      e2ee = services?.firstParm("e2eeAuth")?.booleanParm()
        ?: services?.firstParm("mlsAuth")?.booleanParm()
        ?: false
    )

    val msisdn = root.deep("TOKEN")?.firstParm("msisdn")
      ?: ims.firstParm("Public_User_Identity")?.extractMsisdn()
      ?: ""

    return RcsConfig(
      userMsisdn = msisdn,
      sip = sip,
      msrp = msrp,
      capabilities = capabilities,
      validitySeconds = validity,
      version = version
    )
  }

  private fun String.booleanParm(): Boolean = this == "1" || equals("true", ignoreCase = true)

  /** Pulls a bare MSISDN out of a `sip:+15551234567@domain` or `tel:+15551234567` public identity. */
  private fun String.extractMsisdn(): String? =
    Regex("\\+?\\d{6,15}").find(substringBefore('@'))?.value

  /**
   * A parsed `<characteristic>` node: its own parms and its child characteristics.
   */
  private class Characteristic(val type: String) {
    val parms = HashMap<String, String>()
    val children = ArrayList<Characteristic>()

    fun firstParm(name: String): String? = parms.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun find(type: String, predicate: (Characteristic) -> Boolean = { true }): Characteristic? =
      children.firstOrNull { it.type.equals(type, ignoreCase = true) && predicate(it) }

    /** Depth-first search for the first characteristic of [type] anywhere in the subtree. */
    fun deep(type: String): Characteristic? {
      if (this.type.equals(type, ignoreCase = true)) return this
      for (child in children) {
        child.deep(type)?.let { return it }
      }
      return null
    }
  }

  private fun parseCharacteristic(xml: String): Characteristic {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(xml))

    val root = Characteristic("ROOT")
    val stack = ArrayDeque<Characteristic>()
    stack.addLast(root)

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      when (event) {
        XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
          "characteristic" -> {
            val child = Characteristic(parser.getAttributeValue(null, "type") ?: "")
            stack.last().children.add(child)
            stack.addLast(child)
          }
          "parm" -> {
            val name = parser.getAttributeValue(null, "name")
            val value = parser.getAttributeValue(null, "value")
            if (name != null && value != null) {
              stack.last().parms[name] = value
            }
          }
        }
        XmlPullParser.END_TAG -> if (parser.name.equals("characteristic", ignoreCase = true) && stack.size > 1) {
          stack.removeLast()
        }
      }
      event = parser.next()
    }
    return root
  }

  private const val DEFAULT_VALIDITY_SECONDS = 86_400L * 30
  private const val DEFAULT_SIP_TLS_PORT = 5061
  private const val DEFAULT_MAX_MESSAGE_BYTES = 8_192L
  private const val DEFAULT_FT_MAX_BYTES = 100L * 1024 * 1024
}

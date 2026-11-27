/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.external.ExternalIncomingMessages
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.external.rcs.provisioning.RcsConfig
import org.thoughtcrime.securesms.external.rcs.provisioning.RcsConfigParser
import org.thoughtcrime.securesms.external.rcs.sip.DefaultRcsSipClient
import org.thoughtcrime.securesms.external.rcs.sip.RcsEventListener
import org.thoughtcrime.securesms.external.rcs.sip.RcsRegistrationState
import org.thoughtcrime.securesms.external.rcs.sip.RcsSipClient

/**
 * Single coordination point for RCS: holds the SIP client, exposes whether RCS is usable right now,
 * and routes inbound traffic into the shared [ExternalIncomingMessages] pipeline.
 *
 * The transport and jobs talk only to this object; the networking details live behind
 * [RcsSipClient].
 */
object RcsManager : RcsEventListener {

  private val TAG = Log.tag(RcsManager::class.java)

  private val sipClient: RcsSipClient = DefaultRcsSipClient().apply { setListener(RcsManager) }

  val registrationState: RcsRegistrationState get() = sipClient.state

  /** The parsed configuration, if we have a valid one. */
  fun config(): RcsConfig? {
    val xml = RcsPreferences.configXml ?: return null
    return runCatching { RcsConfigParser.parse(xml) }.getOrNull()
  }

  /** RCS is usable only when enabled, provisioned with a still-valid config, and SIP-registered. */
  fun isReady(): Boolean {
    return RcsPreferences.isEnabled &&
      RcsPreferences.hasValidConfig &&
      sipClient.state == RcsRegistrationState.REGISTERED
  }

  /** Attempt to (re-)register using the stored config. No-op if we aren't provisioned. */
  fun register(): RcsRegistrationState {
    val config = config()
    if (!RcsPreferences.isEnabled || config == null || !RcsPreferences.hasValidConfig) {
      return RcsRegistrationState.UNPROVISIONED
    }
    return sipClient.register(config)
  }

  fun unregister() = sipClient.unregister()

  /** Fresh, positive RCS reachability for [msisdn]? Non-blocking; backed by [RcsCapabilityCache]. */
  fun isReachable(msisdn: String): Boolean {
    if (!isReady()) return false
    return RcsCapabilityCache.isReachable(msisdn)
  }

  /** Refresh (over the network) the cached capabilities for [msisdn]. Safe to call from a job. */
  fun refreshCapabilities(msisdn: String) {
    if (!isReady()) {
      RcsCapabilityCache.put(msisdn, null)
      return
    }
    val caps = sipClient.queryCapabilities(msisdn)
    RcsCapabilityCache.put(msisdn, caps)
  }

  fun sendMessage(msisdn: String, body: String?): RcsSipClient.SendOutcome {
    return sipClient.sendMessage(msisdn, body, attachments = emptyList())
  }

  // region RcsEventListener — inbound traffic from the SIP layer

  override fun onIncomingMessage(fromMsisdn: String, body: String?, sentTimeMillis: Long) {
    val context: Context = org.thoughtcrime.securesms.dependencies.AppDependencies.application
    val from = ExternalIncomingMessages.resolveAddress(fromMsisdn) ?: return
    ExternalIncomingMessages.insert(
      context = context,
      type = ExternalTransportType.RCS,
      from = from,
      body = body,
      sentTimeMillis = sentTimeMillis
    )
  }

  override fun onDeliveryReport(messageId: String, delivered: Boolean) {
    val id = messageId.toLongOrNull() ?: return
    if (delivered) {
      SignalDatabase.messages.markExternalAsDelivered(id, System.currentTimeMillis())
    }
  }

  override fun onDisplayReport(messageId: String) {
    // RCS display (read) receipts map onto Signal's read-receipt handling; wired once the SIP layer lands.
  }

  override fun onRegistrationStateChanged(state: RcsRegistrationState) {
    Log.i(TAG, "RCS registration state: $state")
  }

  // endregion
}

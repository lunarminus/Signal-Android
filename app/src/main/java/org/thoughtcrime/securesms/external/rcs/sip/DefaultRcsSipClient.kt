/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.sip

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.rcs.provisioning.RcsConfig
import java.util.concurrent.atomic.AtomicReference

/**
 * The on-device SIP/MSRP/MLS implementation of [RcsSipClient].
 *
 * STATUS: not yet implemented. This is the single unfinished layer of the RCS transport (see
 * docs/rcs/RCS.md). It cannot be completed or verified in isolation, because a SIP REGISTER only
 * succeeds once the DroidGuard-backed autoconfiguration above has produced valid IMS credentials on
 * a real device, and Jibe's exact behaviour there still needs to be confirmed against live traffic.
 *
 * It reports [RcsRegistrationState.UNREGISTERED] and refuses to send, so the transport cleanly
 * reports itself unavailable and the framework falls back to MMS/SMS. That keeps the app fully
 * functional while this layer is built out.
 *
 * To complete it, implement, in order:
 *   1. A TLS SIP stack (REGISTER with IMS-AKA or SIP Digest auth using [RcsConfig.sip], keep-alive,
 *      re-REGISTER before expiry). No SIP library ships with the app; either vendor a minimal one
 *      (jain-sip style) or bind a privileged/native service if one becomes available.
 *   2. Capability exchange via SIP OPTIONS (RCC.07 §2.6) to answer [queryCapabilities].
 *   3. MSRP sessions (SDP offer/answer in SIP INVITE/MESSAGE) to carry message bodies and files.
 *   4. GSMA UP 3.0 MLS (RFC 9420) for end-to-end encryption, interoperable with Google Messages and
 *      iOS, keyed per conversation.
 *   5. Delivery/display reports (IMDN) mapped back through [RcsEventListener].
 */
class DefaultRcsSipClient : RcsSipClient {

  private val TAG = Log.tag(DefaultRcsSipClient::class.java)
  private val stateRef = AtomicReference(RcsRegistrationState.UNREGISTERED)
  private var listener: RcsEventListener? = null

  override val state: RcsRegistrationState get() = stateRef.get()

  override fun register(config: RcsConfig): RcsRegistrationState {
    Log.w(TAG, "SIP registration not yet implemented; RCS remains inactive and the app falls back to MMS/SMS.")
    setState(RcsRegistrationState.UNREGISTERED)
    return state
  }

  override fun unregister() {
    setState(RcsRegistrationState.UNREGISTERED)
  }

  override fun queryCapabilities(msisdn: String): RcsContactCapabilities? {
    // No registration, so reachability is unknown. Returning null makes the transport fall back.
    return null
  }

  override fun sendMessage(msisdn: String, body: String?, attachments: List<RcsOutgoingAttachment>): RcsSipClient.SendOutcome {
    return RcsSipClient.SendOutcome.NotRegistered
  }

  override fun setListener(listener: RcsEventListener?) {
    this.listener = listener
  }

  private fun setState(newState: RcsRegistrationState) {
    val previous = stateRef.getAndSet(newState)
    if (previous != newState) {
      listener?.onRegistrationStateChanged(newState)
    }
  }
}

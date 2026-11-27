/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.sip

import org.thoughtcrime.securesms.external.rcs.provisioning.RcsConfig

/**
 * The IMS/SIP transport underneath RCS: it keeps a SIP registration alive with the carrier's
 * P-CSCF, answers capability queries (SIP OPTIONS / presence) and carries message bodies over MSRP
 * (and, under GSMA Universal Profile 3.0, MLS-encrypted payloads).
 *
 * This is deliberately an interface. Everything above it in this package — attestation, HTTP
 * provisioning, the transport wiring, capability caching, the job plumbing and the message mapping —
 * is fully implemented and testable. The SIP/MSRP/MLS network layer is the one piece that cannot be
 * finished or verified without a device that can actually register against Jibe (which needs the
 * DroidGuard-backed provisioning above to succeed first). Keeping it behind this interface lets the
 * rest ship and lets that layer be dropped in — or swapped for a native/privileged implementation
 * if GrapheneOS exposes one — without touching the transport.
 *
 * See docs/rcs/RCS.md for the state of each layer and what completing [DefaultRcsSipClient] requires.
 */
interface RcsSipClient {

  val state: RcsRegistrationState

  /** Register (or refresh registration) with the IMS core described by [config]. */
  fun register(config: RcsConfig): RcsRegistrationState

  /** Tear down the SIP registration. */
  fun unregister()

  /**
   * Query whether [msisdn] is reachable over RCS right now (SIP OPTIONS / capability exchange).
   * Returns null if the answer isn't known yet (the caller should treat that as "not reachable" and
   * fall back to SMS/MMS).
   */
  fun queryCapabilities(msisdn: String): RcsContactCapabilities?

  /**
   * Send a text (and optional attachments) to [msisdn] over the registered session.
   *
   * @return a [SendOutcome]; a non-[SendOutcome.Sent] result tells the transport to fall back.
   */
  fun sendMessage(msisdn: String, body: String?, attachments: List<RcsOutgoingAttachment>): SendOutcome

  /** Register a sink for inbound messages, receipts and capability updates. */
  fun setListener(listener: RcsEventListener?)

  enum class SendOutcome { Sent, NotRegistered, NotReachable, Failed }
}

enum class RcsRegistrationState {
  /** Not provisioned yet, or provisioning is still needed. */
  UNPROVISIONED,

  /** Provisioned but not currently registered with the IMS core. */
  UNREGISTERED,

  /** A SIP REGISTER is in flight. */
  REGISTERING,

  /** Registered and able to send/receive. */
  REGISTERED,

  /** Registration failed (bad credentials, network, server refusal). */
  FAILED
}

data class RcsContactCapabilities(
  val rcsChat: Boolean,
  val fileTransfer: Boolean,
  val e2ee: Boolean
)

data class RcsOutgoingAttachment(
  val contentType: String,
  val fileName: String?,
  val sizeBytes: Long,
  /** A content:// or file:// URI the SIP layer can stream from. */
  val uri: String
)

/** Callbacks the SIP layer raises for inbound traffic. Implemented by the RCS manager. */
interface RcsEventListener {
  fun onIncomingMessage(fromMsisdn: String, body: String?, sentTimeMillis: Long)
  fun onDeliveryReport(messageId: String, delivered: Boolean)
  fun onDisplayReport(messageId: String)
  fun onRegistrationStateChanged(state: RcsRegistrationState)
}

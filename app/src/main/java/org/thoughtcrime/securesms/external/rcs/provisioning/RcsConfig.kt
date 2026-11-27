/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.provisioning

/**
 * The subset of a GSMA RCS client configuration (RCC.07 / RCC.14, the "autoconfig" XML the carrier's
 * configuration server returns) that this app needs to register for RCS and send/receive messages.
 *
 * The full configuration document is large; only the fields the registration and messaging layers
 * read are modelled here. Unknown nodes are ignored by [RcsConfigParser].
 */
data class RcsConfig(
  /** The user's own MSISDN in international format, as confirmed by the configuration server. */
  val userMsisdn: String,
  /** SIP registration (IMS) parameters. */
  val sip: Sip,
  /** Message Session Relay Protocol parameters used for the actual message bodies. */
  val msrp: Msrp,
  /** Service capabilities the server provisioned for this user. */
  val capabilities: Capabilities,
  /** Seconds until this configuration must be re-fetched (RCC.14 validity / version token). */
  val validitySeconds: Long,
  /** Server-assigned configuration version, echoed back on the next autoconfig request. */
  val version: String
) {
  data class Sip(
    val privateUserId: String,
    val publicUserId: String,
    val homeDomain: String,
    val proxyAddress: String,
    val proxyPort: Int,
    /** "SIPoTLS", "SIPoTCP" or "SIPoUDP". RCS over Jibe is TLS. */
    val transport: String,
    val realm: String,
    /** Authentication type: "EarlyIMS", "AKA" (SIM-based) or "Digest". */
    val authType: String
  )

  data class Msrp(
    val authUser: String,
    val authPassword: String,
    val maxMessageSizeBytes: Long
  )

  data class Capabilities(
    val chat: Boolean,
    val fileTransfer: Boolean,
    val fileTransferMaxSizeBytes: Long,
    val groupChat: Boolean,
    val isComposing: Boolean,
    val deliveryReports: Boolean,
    val displayReports: Boolean,
    /** Whether the server advertised MLS-based end-to-end encryption (GSMA UP 3.0). */
    val e2ee: Boolean
  )

  val isTls: Boolean get() = sip.transport.equals("SIPoTLS", ignoreCase = true)
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.ExternalTransport
import org.thoughtcrime.securesms.external.ExternalTransportCapabilities
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.external.rcs.jobs.RcsSendJob
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Rich Communication Services: carrier (Jibe-backed) messaging with typing indicators, delivery and
 * read receipts, reactions, large attachments and — under GSMA Universal Profile 3.0 — MLS
 * end-to-end encryption interoperable with Google Messages and iOS.
 *
 * RCS is registered **first** in [org.thoughtcrime.securesms.external.ExternalTransports], so when a
 * contact is reachable over RCS it is chosen over MMS/SMS; otherwise the registry falls through to
 * them. [isAvailable] is true only once RCS is enabled, provisioned and SIP-registered (see
 * [RcsManager]); until the SIP layer is complete this returns false and the app uses MMS/SMS, so
 * turning RCS on is always safe.
 */
object RcsTransport : ExternalTransport {

  private val TAG = Log.tag(RcsTransport::class.java)

  override val type: ExternalTransportType = ExternalTransportType.RCS

  override val capabilities: ExternalTransportCapabilities
    get() {
      val config = RcsManager.config()
      return ExternalTransportCapabilities(
        supportsText = true,
        supportsAttachments = config?.capabilities?.fileTransfer ?: true,
        supportsGroupThreads = config?.capabilities?.groupChat ?: true,
        supportsDeliveryReports = config?.capabilities?.deliveryReports ?: true,
        supportsReadReceipts = config?.capabilities?.displayReports ?: true,
        supportsTypingIndicators = config?.capabilities?.isComposing ?: true,
        supportsReactions = true,
        supportsSubject = true,
        maxAttachmentBytes = config?.capabilities?.fileTransferMaxSizeBytes ?: (100L * 1024 * 1024)
      )
    }

  override fun isAvailable(context: Context): Boolean {
    return RcsPreferences.isEnabled && RcsManager.isReady()
  }

  override fun canReach(recipient: Recipient): Boolean {
    if (!recipient.isIndividual || recipient.isSelf || !recipient.hasSmsAddress) {
      return false
    }
    val msisdn = recipient.smsAddressOrNull() ?: return false
    return RcsManager.isReachable(msisdn)
  }

  @WorkerThread
  override fun canSend(recipient: Recipient, message: OutgoingMessage): Boolean {
    if (!canReach(recipient)) {
      return false
    }
    val hasText = message.body.isNotEmpty()
    val caps = capabilities
    val attachmentsOk = message.attachments.isEmpty() || caps.supportsAttachments
    return (hasText || message.attachments.isNotEmpty()) && attachmentsOk
  }

  @WorkerThread
  override fun enqueueSend(context: Context, messageId: Long, recipient: Recipient) {
    AppDependencies.jobManager.add(RcsSendJob(messageId, recipient))
  }

  private fun Recipient.smsAddressOrNull(): String? = if (hasSmsAddress) requireSmsAddress() else null
}

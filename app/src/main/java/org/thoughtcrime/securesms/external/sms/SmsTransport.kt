/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.DefaultSmsApp
import org.thoughtcrime.securesms.external.ExternalTransport
import org.thoughtcrime.securesms.external.ExternalTransportCapabilities
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Plain carrier text messages to a single phone number.
 */
object SmsTransport : ExternalTransport {

  override val type: ExternalTransportType = ExternalTransportType.SMS

  override val capabilities: ExternalTransportCapabilities = ExternalTransportCapabilities(
    supportsText = true,
    supportsAttachments = false,
    supportsGroupThreads = false,
    supportsDeliveryReports = true
  )

  override fun isAvailable(context: Context): Boolean {
    return DefaultSmsApp.hasTelephony(context) &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
  }

  override fun canReach(recipient: Recipient): Boolean {
    return recipient.isIndividual && !recipient.isSelf && recipient.hasSmsAddress
  }

  override fun canSend(recipient: Recipient, message: OutgoingMessage): Boolean {
    return canReach(recipient) &&
      message.attachments.isEmpty() &&
      message.sharedContacts.isEmpty() &&
      message.body.isNotEmpty()
  }

  override fun enqueueSend(context: Context, messageId: Long, recipient: Recipient) {
    AppDependencies.jobManager.add(SmsSendJob(messageId, recipient))
  }
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.DefaultSmsApp
import org.thoughtcrime.securesms.external.ExternalTransport
import org.thoughtcrime.securesms.external.ExternalTransportCapabilities
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Carrier multimedia messages: attachments, group threads and anything else plain SMS can't carry.
 */
object MmsTransport : ExternalTransport {

  override val type: ExternalTransportType = ExternalTransportType.MMS

  override val capabilities: ExternalTransportCapabilities = ExternalTransportCapabilities(
    supportsText = true,
    supportsAttachments = true,
    supportsGroupThreads = true,
    supportsDeliveryReports = false,
    supportsSubject = true,
    maxAttachmentBytes = 300L * 1024
  )

  override fun isAvailable(context: Context): Boolean {
    // Sending MMS through SmsManager needs no runtime permission beyond SEND_SMS, but only the default
    // SMS app can reliably send and receive MMS on modern Android.
    return DefaultSmsApp.hasTelephony(context) &&
      DefaultSmsApp.isDefault(context) &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
  }

  override fun canReach(recipient: Recipient): Boolean {
    return if (recipient.isMmsGroup) {
      true
    } else {
      recipient.isIndividual && !recipient.isSelf && recipient.hasSmsAddress
    }
  }

  override fun canSend(recipient: Recipient, message: OutgoingMessage): Boolean {
    if (!canReach(recipient)) {
      return false
    }

    if (recipient.isMmsGroup) {
      val members = SignalDatabase.groups.getGroupMembers(recipient.requireGroupId(), GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF)
      return members.isNotEmpty() && members.all { it.hasSmsAddress }
    }

    return true
  }

  override fun enqueueSend(context: Context, messageId: Long, recipient: Recipient) {
    AppDependencies.jobManager.add(MmsSendJob(messageId, recipient))
  }
}

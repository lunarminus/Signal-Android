/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.recipients.Recipient
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.util.SignalE164Util

/**
 * The single path every external transport uses to store an incoming message: it becomes an ordinary
 * row in the message table, in the same thread as the sender's Signal messages, and triggers the normal
 * notification flow.
 */
object ExternalIncomingMessages {

  private val TAG = Log.tag(ExternalIncomingMessages::class.java)

  /**
   * Finds or creates the recipient for a carrier address. Phone numbers and short codes become phone
   * number recipients. Anything else (alphanumeric sender ids like "BANK", or email gateways) is kept
   * in the recipient's email/SMS-address slot so it still gets its own thread.
   */
  @JvmStatic
  @WorkerThread
  fun resolveAddress(address: String?): RecipientId? {
    val trimmed = address?.trim()
    if (trimmed.isNullOrEmpty()) {
      return null
    }

    val e164 = SignalE164Util.formatAsE164(trimmed)
    return if (e164 != null) {
      SignalDatabase.recipients.getOrInsertFromE164(e164)
    } else {
      SignalDatabase.recipients.getOrInsertFromEmail(trimmed)
    }
  }

  /**
   * Stores an incoming external message and notifies the user.
   *
   * @param groupId For group threads (MMS groups), the group the message belongs to; null for 1:1.
   * @return The insert result, or null if the message was dropped (blocked sender or duplicate).
   */
  @JvmStatic
  @WorkerThread
  fun insert(
    context: Context,
    type: ExternalTransportType,
    from: RecipientId,
    body: String?,
    sentTimeMillis: Long,
    receivedTimeMillis: Long = System.currentTimeMillis(),
    subscriptionId: Int = -1,
    attachments: List<Attachment> = emptyList(),
    groupId: GroupId? = null
  ): MessageTable.InsertResult? {
    val sender = Recipient.resolved(from)
    if (sender.isBlocked) {
      Log.i(TAG, "Dropping ${type.id} from blocked recipient $from")
      return null
    }

    if (groupId != null && Recipient.externalGroupExact(groupId).isBlocked) {
      Log.i(TAG, "Dropping ${type.id} for blocked group")
      return null
    }

    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = from,
      sentTimeMillis = sentTimeMillis,
      serverTimeMillis = sentTimeMillis,
      receivedTimeMillis = receivedTimeMillis,
      groupId = groupId,
      body = body,
      subscriptionId = subscriptionId,
      attachments = attachments,
      messageExtras = MessageExtras(externalTransport = type.id)
    )

    val result = SignalDatabase.messages.insertMessageInbox(message).orElse(null)

    if (result == null) {
      Log.w(TAG, "Failed to insert incoming ${type.id}, likely a duplicate.")
      return null
    }

    Log.i(TAG, "Inserted incoming ${type.id} as message ${result.messageId} in thread ${result.threadId}")
    AppDependencies.messageNotifier.updateNotification(context, ConversationId.forConversation(result.threadId))
    return result
  }
}

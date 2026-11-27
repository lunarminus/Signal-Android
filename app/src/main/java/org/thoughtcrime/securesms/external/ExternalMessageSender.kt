/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender

/**
 * Glue between the shared [MessageSender] pipeline and the external transports.
 */
object ExternalMessageSender {

  private val TAG = Log.tag(ExternalMessageSender::class.java)

  /**
   * Prepares an outgoing message for an external transport: strips Signal-only features the transport
   * can't carry and records which transport is used.
   */
  @JvmStatic
  fun prepare(message: OutgoingMessage, transport: ExternalTransport): OutgoingMessage {
    val capabilities = transport.capabilities
    val stripped = message.copy(
      isSecure = false,
      expiresIn = 0,
      isViewOnce = false,
      outgoingQuote = null,
      mentions = emptyList(),
      bodyRanges = null,
      linkPreviews = emptyList(),
      sharedContacts = if (capabilities.supportsAttachments) message.sharedContacts else emptyList(),
      attachments = if (capabilities.supportsAttachments) message.attachments else emptyList(),
      messageToEdit = 0
    )
    return ExternalTransports.tag(stripped, transport.type)
  }

  /**
   * Called by [MessageSender] once an outgoing external message is in the database.
   */
  @JvmStatic
  @WorkerThread
  fun enqueue(context: Context, recipient: Recipient, sendType: MessageSender.SendType, messageId: Long) {
    val type = ExternalTransportType.fromSendType(sendType)
    if (type == null) {
      Log.w(TAG, "Not an external send type: $sendType")
      return
    }

    val transport = ExternalTransports.get(type)
    if (!ExternalMessagingPreferences.isEnabled || !transport.isAvailable(context)) {
      Log.w(TAG, "${type.id} is not available, failing message $messageId")
      markFailed(context, messageId)
      return
    }

    transport.enqueueSend(context, messageId, recipient)
  }

  /**
   * Sends a plain text message to [recipient] over the best external transport, outside of the
   * conversation screen (quick replies, "respond via message" from the dialer, ...).
   *
   * @return true if the message was queued.
   */
  @JvmStatic
  @WorkerThread
  fun sendText(context: Context, recipient: Recipient, body: String, subscriptionId: Int = -1): Boolean {
    val message = OutgoingMessage.sms(recipient, body).copy(subscriptionId = subscriptionId)
    val transport = ExternalTransports.select(context, recipient, message) ?: return false
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

    MessageSender.send(context, prepare(message, transport), threadId, transport.type.toSendType(), null, null)
    return true
  }

  /**
   * Sends an already-built [message] over the best external transport, for flows (like sharing) that
   * build their own messages. Returns false if no transport can take it.
   */
  @JvmStatic
  @JvmOverloads
  @WorkerThread
  fun send(context: Context, message: OutgoingMessage, threadId: Long, subscriptionId: Int = -1): Boolean {
    val withSim = if (subscriptionId != -1) message.copy(subscriptionId = subscriptionId) else message
    val transport = ExternalTransports.select(context, withSim.threadRecipient, withSim) ?: return false
    MessageSender.send(context, prepare(withSim, transport), threadId, transport.type.toSendType(), null, null)
    return true
  }

  /** Marks [messageId] failed and shows the usual "message not delivered" notification. */
  @JvmStatic
  @WorkerThread
  fun markFailed(context: Context, messageId: Long) {
    SignalDatabase.messages.markAsSentFailed(messageId)

    val threadId = SignalDatabase.messages.getThreadIdForMessage(messageId)
    val threadRecipient = SignalDatabase.threads.getRecipientForThreadId(threadId)
    if (threadId > 0 && threadRecipient != null) {
      AppDependencies.messageNotifier.notifyMessageDeliveryFailed(context, threadRecipient, ConversationId.forConversation(threadId))
    }
  }
}

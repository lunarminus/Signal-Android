/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.external.mms.MmsTransport
import org.thoughtcrime.securesms.external.sms.SmsTransport
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Registry of every installed [ExternalTransport], and the place that decides which one carries a
 * given message.
 *
 * Transports are tried in registration order, so put the simplest/cheapest first: a plain text
 * message to one person goes over SMS, anything SMS can't carry falls through to MMS. A future RCS
 * transport would go first and fall back to SMS/MMS when a contact isn't reachable over RCS.
 */
object ExternalTransports {

  private val TAG = Log.tag(ExternalTransports::class.java)

  private val transports: List<ExternalTransport> by lazy {
    listOf(
      SmsTransport,
      MmsTransport
    )
  }

  @JvmStatic
  fun all(): List<ExternalTransport> = transports

  @JvmStatic
  fun get(type: ExternalTransportType): ExternalTransport = transports.first { it.type == type }

  /** Whether external messaging is turned on and at least one transport is usable right now. */
  @JvmStatic
  fun isAnyAvailable(context: Context): Boolean {
    return ExternalMessagingPreferences.isEnabled && transports.any { it.isAvailable(context) }
  }

  /** Whether some available transport can reach [recipient] (an individual with a phone number, or an MMS group). */
  @JvmStatic
  fun canReach(context: Context, recipient: Recipient): Boolean {
    if (!ExternalMessagingPreferences.isEnabled) {
      return false
    }
    return transports.any { it.isAvailable(context) && it.canReach(recipient) }
  }

  /**
   * Picks the transport that should carry [message] to [recipient], or null if none can.
   */
  @JvmStatic
  @WorkerThread
  fun select(context: Context, recipient: Recipient, message: OutgoingMessage): ExternalTransport? {
    if (!ExternalMessagingPreferences.isEnabled) {
      Log.w(TAG, "External messaging is disabled.")
      return null
    }

    val selected = transports.firstOrNull { it.isAvailable(context) && it.canSend(recipient, message) }
    if (selected == null) {
      Log.w(TAG, "No external transport can send this message to ${recipient.id}.")
    }
    return selected
  }

  /** Tags [message] with the transport that carries it, so the choice is visible on the stored row. */
  @JvmStatic
  fun tag(message: OutgoingMessage, type: ExternalTransportType): OutgoingMessage {
    val extras = (message.messageExtras ?: MessageExtras()).copy(externalTransport = type.id)
    return message.copy(messageExtras = extras)
  }

  /** The transport recorded on a stored message, if any. */
  @JvmStatic
  fun typeOf(extras: MessageExtras?): ExternalTransportType? {
    return ExternalTransportType.fromId(extras?.externalTransport)
  }
}

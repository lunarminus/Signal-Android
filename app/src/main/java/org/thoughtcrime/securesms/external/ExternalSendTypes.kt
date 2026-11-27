/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.content.Context
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.dualsim.SubscriptionManagerCompat

/**
 * Works out which [MessageSendType]s the composer should offer for a conversation, and which one it
 * should start with.
 */
object ExternalSendTypes {

  /** The send types available for [recipient], Signal first. Never empty. */
  @JvmStatic
  fun available(context: Context, recipient: Recipient): List<MessageSendType> {
    val types = mutableListOf<MessageSendType>()

    // Anyone not known to be unregistered gets Signal, so an unchecked registration state never downgrades a chat to SMS.
    val signalReachable = !recipient.isMmsGroup && !recipient.isUnregistered && Recipient.self().isRegistered
    if (signalReachable) {
      types += MessageSendType.SignalMessageSendType
    }

    if (ExternalTransports.canReach(context, recipient)) {
      val sims = SubscriptionManagerCompat(context).activeAndReadySubscriptionInfos
      if (sims.size > 1) {
        sims.forEach { types += MessageSendType.ExternalMessageSendType(it.subscriptionId, it.displayName.toString()) }
      } else {
        types += MessageSendType.ExternalMessageSendType()
      }
    }

    if (types.isEmpty()) {
      types += MessageSendType.SignalMessageSendType
    }

    return types
  }

  /** The send type a message to [recipient] should use when the user hasn't picked one (notification replies, shares). */
  @JvmStatic
  fun defaultFor(context: Context, recipient: Recipient): MessageSendType {
    return default(available(context, recipient))
  }

  /** The send type to start a conversation with, chosen from [available]. */
  @JvmStatic
  fun default(available: List<MessageSendType>): MessageSendType {
    val signal = available.firstOrNull { it.usesSignalTransport }
    val external = available.firstOrNull { !it.usesSignalTransport }

    return when {
      signal != null && (external == null || ExternalMessagingPreferences.isPreferSignalWhenAvailable) -> signal
      external != null -> external
      else -> MessageSendType.SignalMessageSendType
    }
  }
}

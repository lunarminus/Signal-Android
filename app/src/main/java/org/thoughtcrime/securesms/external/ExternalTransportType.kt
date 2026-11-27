/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import org.thoughtcrime.securesms.sms.MessageSender

/**
 * Every non-Signal ("external") way of delivering a message that this app knows about.
 *
 * To add a new transport (for example RCS), add an entry here, implement [ExternalTransport] and
 * register it in [ExternalTransports].
 */
enum class ExternalTransportType(val id: String) {
  /** Plain carrier text messages. */
  SMS("sms"),

  /** Carrier multimedia messages, also used for group threads and long or rich content. */
  MMS("mms");

  /** The legacy [MessageSender.SendType] that the shared send pipeline uses for this transport. */
  fun toSendType(): MessageSender.SendType {
    return when (this) {
      SMS -> MessageSender.SendType.SMS
      MMS -> MessageSender.SendType.MMS
    }
  }

  companion object {
    @JvmStatic
    fun fromId(id: String?): ExternalTransportType? = entries.firstOrNull { it.id == id }

    @JvmStatic
    fun fromSendType(sendType: MessageSender.SendType): ExternalTransportType? {
      return when (sendType) {
        MessageSender.SendType.SMS -> SMS
        MessageSender.SendType.MMS -> MMS
        MessageSender.SendType.SIGNAL -> null
      }
    }
  }
}

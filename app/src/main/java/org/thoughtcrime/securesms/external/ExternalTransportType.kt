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
  MMS("mms"),

  /**
   * Rich Communication Services: carrier (Jibe-backed) messaging with receipts, typing, reactions,
   * large attachments and, under GSMA Universal Profile 3.0, MLS end-to-end encryption. Preferred
   * over SMS/MMS when a contact is reachable; see [org.thoughtcrime.securesms.external.rcs.RcsTransport].
   */
  RCS("rcs");

  /**
   * The [MessageSender.SendType] the shared send pipeline uses when storing this transport's
   * messages. RCS rows are stored like MMS (they can carry attachments and group threads); the
   * concrete transport is resolved separately from the tag on [MessageExtras], not from this.
   */
  fun toSendType(): MessageSender.SendType {
    return when (this) {
      SMS -> MessageSender.SendType.SMS
      MMS -> MessageSender.SendType.MMS
      RCS -> MessageSender.SendType.MMS
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

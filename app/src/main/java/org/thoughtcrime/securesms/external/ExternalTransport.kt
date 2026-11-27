/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.content.Context
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * A way to deliver messages outside of the Signal protocol, such as SMS, MMS or (later) RCS.
 *
 * Transports never touch the message database layout directly: outgoing messages are inserted by the
 * shared [org.thoughtcrime.securesms.sms.MessageSender] pipeline before [enqueueSend] is called, and
 * incoming messages are handed to [ExternalIncomingMessages].
 */
interface ExternalTransport {
  val type: ExternalTransportType

  val capabilities: ExternalTransportCapabilities

  /**
   * Whether the device and app state currently allow using this transport at all: the user turned
   * external messaging on, the needed permissions are granted and the hardware supports it.
   */
  fun isAvailable(context: Context): Boolean

  /** Whether this transport can carry [message] to [recipient]. Called only when [isAvailable] is true. */
  @WorkerThread
  fun canSend(recipient: Recipient, message: OutgoingMessage): Boolean

  /** Whether this transport can, in principle, reach [recipient] (ignoring message contents). */
  fun canReach(recipient: Recipient): Boolean

  /** Schedule delivery of the already-inserted outgoing message [messageId] to [recipient]. */
  @WorkerThread
  fun enqueueSend(context: Context, messageId: Long, recipient: Recipient)
}

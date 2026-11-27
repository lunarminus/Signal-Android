/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.external.ExternalTransports
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * When RCS can't carry an already-stored message, pick the next external transport that can
 * (MMS, then SMS), re-tag the stored row so the conversation UI shows the right transport, and
 * enqueue that transport's send. Mirrors Google Messages' "sent as text message" fallback.
 */
object RcsFallback {

  private val TAG = Log.tag(RcsFallback::class.java)

  @WorkerThread
  fun requeueWithoutRcs(context: Context, messageId: Long, recipient: Recipient): Boolean {
    val fallback = ExternalTransports.all()
      .firstOrNull { it.type != ExternalTransportType.RCS && it.isAvailable(context) && it.canReach(recipient) }

    if (fallback == null) {
      Log.w(TAG, "[$messageId] No non-RCS transport can reach ${recipient.id}.")
      return false
    }

    retag(messageId, fallback.type)
    Log.i(TAG, "[$messageId] Re-queued over ${fallback.type.id}.")
    fallback.enqueueSend(context, messageId, recipient)
    return true
  }

  private fun retag(messageId: Long, type: ExternalTransportType) {
    val current = SignalDatabase.messages.getMessageExtras(messageId) ?: MessageExtras()
    SignalDatabase.messages.updateMessageExtras(messageId, current.copy(externalTransport = type.id))
  }
}

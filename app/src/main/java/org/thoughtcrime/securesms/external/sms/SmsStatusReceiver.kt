/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Receives the carrier's "sent" and "delivered" callbacks for messages sent by [SmsSendJob] and hands
 * them to [SmsStatusJob] so the database work happens off the main thread.
 */
class SmsStatusReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(SmsStatusReceiver::class.java)

    const val ACTION_SENT = "org.thoughtcrime.securesms.external.sms.SENT"
    const val ACTION_DELIVERED = "org.thoughtcrime.securesms.external.sms.DELIVERED"

    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_RUN_ATTEMPT = "run_attempt"
    const val EXTRA_IS_MULTIPART = "is_multipart"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
    if (messageId < 0) {
      Log.w(TAG, "Status broadcast without a message id.")
      return
    }

    when (intent.action) {
      ACTION_SENT -> {
        AppDependencies.jobManager.add(
          SmsStatusJob.sent(
            messageId = messageId,
            resultCode = resultCode,
            runAttempt = intent.getIntExtra(EXTRA_RUN_ATTEMPT, 0),
            isMultipart = intent.getBooleanExtra(EXTRA_IS_MULTIPART, true)
          )
        )
      }

      ACTION_DELIVERED -> {
        AppDependencies.jobManager.add(
          SmsStatusJob.delivered(
            messageId = messageId,
            pdu = intent.getByteArrayExtra("pdu"),
            format = intent.getStringExtra("format")
          )
        )
      }

      else -> Log.w(TAG, "Unknown action: ${intent.action}")
    }
  }
}

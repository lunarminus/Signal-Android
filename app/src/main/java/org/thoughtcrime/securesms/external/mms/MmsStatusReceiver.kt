/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Receives the platform MMS service's results for sends ([MmsSendJob]) and downloads ([MmsDownloadJob]).
 */
class MmsStatusReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(MmsStatusReceiver::class.java)

    const val ACTION_SENT = "org.thoughtcrime.securesms.external.mms.SENT"
    const val ACTION_DOWNLOADED = "org.thoughtcrime.securesms.external.mms.DOWNLOADED"

    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_FILE_URI = "file_uri"
    const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
    const val EXTRA_TRANSACTION_ID = "transaction_id"
    const val EXTRA_CONTENT_LOCATION = "content_location"
    const val EXTRA_ATTEMPT = "attempt"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val fileUri = intent.getStringExtra(EXTRA_FILE_URI)
    val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)

    when (intent.action) {
      ACTION_SENT -> {
        AppDependencies.jobManager.add(
          MmsResultJob.sent(
            messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1),
            resultCode = resultCode,
            httpStatus = httpStatus,
            response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA),
            fileUri = fileUri
          )
        )
      }

      ACTION_DOWNLOADED -> {
        AppDependencies.jobManager.add(
          MmsResultJob.downloaded(
            resultCode = resultCode,
            httpStatus = httpStatus,
            fileUri = fileUri,
            subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1),
            transactionId = intent.getByteArrayExtra(EXTRA_TRANSACTION_ID),
            contentLocation = intent.getStringExtra(EXTRA_CONTENT_LOCATION),
            attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
          )
        )
      }

      else -> Log.w(TAG, "Unknown action ${intent.action}")
    }
  }
}

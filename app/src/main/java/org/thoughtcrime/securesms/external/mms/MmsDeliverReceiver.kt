/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Receives WAP push MMS notifications. Android only sends WAP_PUSH_DELIVER to the default SMS app.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(MmsDeliverReceiver::class.java)
    private const val MMS_MIME_TYPE = "application/vnd.wap.mms-message"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION || intent.type != MMS_MIME_TYPE) {
      return
    }

    val data = intent.getByteArrayExtra("data")
    if (data == null || data.isEmpty()) {
      Log.w(TAG, "Empty WAP push.")
      return
    }

    val subscriptionId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
    Log.i(TAG, "Received an MMS notification.")
    AppDependencies.jobManager.add(MmsDownloadJob.fromNotification(data, subscriptionId))
  }
}

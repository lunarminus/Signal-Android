/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.ExternalIncomingMessages
import org.thoughtcrime.securesms.external.ExternalMessageSender
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Handles "respond via message" (quick text replies when declining a call). Android requires the
 * default SMS app to provide this service.
 */
class HeadlessSmsSendService : Service() {

  companion object {
    private val TAG = Log.tag(HeadlessSmsSendService::class.java)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null || intent.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
      stopSelf(startId)
      return START_NOT_STICKY
    }

    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
    val addresses = intent.data?.schemeSpecificPart?.substringBefore('?')?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val subscriptionId = intent.getIntExtra("subscription", -1)

    if (text.isNullOrBlank() || addresses.isEmpty()) {
      Log.w(TAG, "Nothing to send.")
      stopSelf(startId)
      return START_NOT_STICKY
    }

    SignalExecutors.BOUNDED.execute {
      for (address in addresses) {
        val recipientId = ExternalIncomingMessages.resolveAddress(address) ?: continue
        val queued = ExternalMessageSender.sendText(applicationContext, Recipient.resolved(recipientId), text, subscriptionId)
        Log.i(TAG, "Respond-via-message queued: $queued")
      }
      stopSelf(startId)
    }

    return START_NOT_STICKY
  }
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.DefaultSmsApp
import org.thoughtcrime.securesms.external.ExternalMessagingPreferences

/**
 * Receives incoming SMS. As the default SMS app we get SMS_DELIVER; otherwise (if the user enabled SMS
 * but kept another default app) we fall back to the SMS_RECEIVED broadcast every app with the
 * permission sees.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(SmsDeliverReceiver::class.java)
  }

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action
    Log.i(TAG, "Received $action")

    if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION && action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
      return
    }

    // When we're the default app, the same message also arrives as SMS_RECEIVED. Only handle it once.
    if (action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION && DefaultSmsApp.isDefault(context)) {
      return
    }

    // Without the default role, only pick messages up if the user turned SMS on in this app.
    if (action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION && !ExternalMessagingPreferences.isEnabled) {
      return
    }

    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
    if (messages.isNullOrEmpty()) {
      Log.w(TAG, "No messages in broadcast.")
      return
    }

    val pdus = messages.mapNotNull { it?.pdu }
    val format = intent.getStringExtra("format")
    val subscriptionId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))

    AppDependencies.jobManager.add(SmsReceiveJob(pdus, format, subscriptionId))
  }
}

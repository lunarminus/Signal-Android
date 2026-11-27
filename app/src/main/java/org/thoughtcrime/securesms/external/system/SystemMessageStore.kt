/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.system

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.DefaultSmsApp
import org.thoughtcrime.securesms.external.ExternalMessagingPreferences
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Mirrors carrier messages into Android's system SMS/MMS store.
 *
 * Signal's own encrypted database stays the source of truth. Android expects the default SMS app to
 * write every SMS it sends and receives to the system store (when another app is the default, Android
 * or that app does it), so we only write while we hold the role.
 */
object SystemMessageStore {

  private val TAG = Log.tag(SystemMessageStore::class.java)

  private fun shouldWrite(context: Context): Boolean {
    return ExternalMessagingPreferences.isWriteToSystemProviderEnabled && DefaultSmsApp.isDefault(context)
  }

  @JvmStatic
  @WorkerThread
  fun recordReceivedSms(context: Context, address: String, body: String, sentTime: Long, subscriptionId: Int): Uri? {
    if (!shouldWrite(context)) {
      return null
    }

    val values = ContentValues().apply {
      put(Telephony.Sms.ADDRESS, address)
      put(Telephony.Sms.BODY, body)
      put(Telephony.Sms.DATE, System.currentTimeMillis())
      put(Telephony.Sms.DATE_SENT, sentTime)
      put(Telephony.Sms.READ, 0)
      put(Telephony.Sms.SEEN, 0)
      put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
      put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
    }

    return insert(context, Telephony.Sms.Inbox.CONTENT_URI, values)
  }

  @JvmStatic
  @WorkerThread
  fun recordSentSms(context: Context, recipient: Recipient, body: String, sentTime: Long, subscriptionId: Int): Uri? {
    if (!shouldWrite(context) || !recipient.hasSmsAddress) {
      return null
    }

    val values = ContentValues().apply {
      put(Telephony.Sms.ADDRESS, recipient.requireSmsAddress())
      put(Telephony.Sms.BODY, body)
      put(Telephony.Sms.DATE, sentTime)
      put(Telephony.Sms.DATE_SENT, sentTime)
      put(Telephony.Sms.READ, 1)
      put(Telephony.Sms.SEEN, 1)
      put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
      put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
    }

    return insert(context, Telephony.Sms.Sent.CONTENT_URI, values)
  }

  private fun insert(context: Context, uri: Uri, values: ContentValues): Uri? {
    return try {
      context.contentResolver.insert(uri, values)
    } catch (e: Exception) {
      Log.w(TAG, "Could not write to the system message store.", e)
      null
    }
  }
}

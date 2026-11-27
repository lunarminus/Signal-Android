/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.external.ExternalMessageSender
import org.thoughtcrime.securesms.external.ExternalMessagingPreferences
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.phonenumbers.NumberUtil

/**
 * Hands one outgoing SMS to the carrier. The result of the send (and, if enabled, the delivery report)
 * comes back later through [SmsStatusReceiver].
 */
class SmsSendJob private constructor(
  parameters: Parameters,
  private val messageId: Long,
  private val runAttempt: Int
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalSmsSendJob"

    private val TAG = Log.tag(SmsSendJob::class.java)

    private const val MAX_ATTEMPTS = 15
    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_RUN_ATTEMPT = "run_attempt"

    @JvmStatic
    fun getSmsManager(context: Context, subscriptionId: Int): SmsManager {
      return if (Build.VERSION.SDK_INT >= 31) {
        val manager = context.getSystemService(SmsManager::class.java)
        if (subscriptionId != -1) manager.createForSubscriptionId(subscriptionId) else manager
      } else {
        @Suppress("DEPRECATION")
        if (subscriptionId != -1) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
      }
    }
  }

  constructor(messageId: Long, destination: Recipient, runAttempt: Int = 0, initialDelayMs: Long = 0) : this(
    Parameters.Builder()
      .setQueue(destination.id.toQueueKey() + "::SMS")
      .setMaxAttempts(MAX_ATTEMPTS)
      .setLifespan(Parameters.IMMORTAL)
      .setInitialDelay(initialDelayMs)
      .build(),
    messageId,
    runAttempt
  )

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putLong(KEY_MESSAGE_ID, messageId)
      .putInt(KEY_RUN_ATTEMPT, runAttempt)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onAdded() {
    SignalDatabase.messages.markAsSending(messageId)
  }

  override fun onRun() {
    if (runAttempt >= MAX_ATTEMPTS) {
      Log.w(TAG, "[$messageId] Hit the retry limit. Failing.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val record = SignalDatabase.messages.getMessageRecordOrNull(messageId)
    if (record == null) {
      Log.w(TAG, "[$messageId] Message no longer exists.")
      return
    }

    if (!record.isPending && !record.isFailed) {
      Log.w(TAG, "[$messageId] Already sent. Ignoring.")
      return
    }

    if (record.isSecure) {
      Log.w(TAG, "[$messageId] Refusing to send a Signal message over SMS.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val recipient = record.toRecipient.resolve()
    if (!recipient.hasSmsAddress) {
      Log.w(TAG, "[$messageId] Recipient ${recipient.id} has no SMS address.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    var address = recipient.requireSmsAddress()
    if (!NumberUtil.isValidEmail(address)) {
      address = PhoneNumberUtils.stripSeparators(PhoneNumberUtils.convertKeypadLettersToDigits(address))
    }

    if (!PhoneNumberUtils.isWellFormedSmsAddress(address) && !NumberUtil.isValidEmail(address)) {
      Log.w(TAG, "[$messageId] Not a valid SMS destination.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val body = record.body
    val smsManager = getSmsManager(context, record.subscriptionId)
    val parts: ArrayList<String> = smsManager.divideMessage(body)
    val isMultipart = parts.size > 1
    val sentIntents = ArrayList(parts.indices.map { index -> sentIntent(index, isMultipart) })
    val deliveredIntents = if (ExternalMessagingPreferences.isDeliveryReportsEnabled) ArrayList(parts.indices.map { index -> deliveredIntent(index) }) else null

    Log.i(TAG, "[$messageId] Sending SMS in ${parts.size} part(s), attempt $runAttempt")

    try {
      smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
    } catch (e: RuntimeException) {
      // Some devices throw for certain recipients/bodies. Fall back to sending the parts one by one.
      Log.w(TAG, "[$messageId] sendMultipartTextMessage failed, trying part by part", e)
      try {
        parts.forEachIndexed { index, part ->
          smsManager.sendTextMessage(address, null, part, sentIntents[index], deliveredIntents?.get(index))
        }
      } catch (e2: RuntimeException) {
        Log.w(TAG, "[$messageId] Could not hand SMS to the carrier", e2)
        ExternalMessageSender.markFailed(context, messageId)
      }
    }
  }

  private fun sentIntent(part: Int, isMultipart: Boolean): PendingIntent {
    val intent = Intent(SmsStatusReceiver.ACTION_SENT, Uri.parse("signal-sms://sent/$messageId/$part/${System.nanoTime()}"), context, SmsStatusReceiver::class.java)
      .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
      .putExtra(SmsStatusReceiver.EXTRA_RUN_ATTEMPT, runAttempt)
      .putExtra(SmsStatusReceiver.EXTRA_IS_MULTIPART, isMultipart)

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable())
  }

  private fun deliveredIntent(part: Int): PendingIntent {
    val intent = Intent(SmsStatusReceiver.ACTION_DELIVERED, Uri.parse("signal-sms://delivered/$messageId/$part/${System.nanoTime()}"), context, SmsStatusReceiver::class.java)
      .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable())
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    Log.w(TAG, "[$messageId] onFailure()")
    ExternalMessageSender.markFailed(context, messageId)
  }

  class Factory : Job.Factory<SmsSendJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): SmsSendJob {
      val data = JsonJobData.deserialize(serializedData)
      return SmsSendJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getInt(KEY_RUN_ATTEMPT))
    }
  }
}

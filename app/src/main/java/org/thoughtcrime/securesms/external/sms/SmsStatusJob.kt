/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.app.Activity
import android.telephony.SmsManager
import android.telephony.SmsMessage
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.ExternalMessageSender
import org.thoughtcrime.securesms.external.system.SystemMessageStore
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob
import kotlin.time.Duration.Companion.seconds

/**
 * Applies a carrier "sent" or "delivered" result to the stored message.
 */
class SmsStatusJob private constructor(
  parameters: Parameters,
  private val action: String,
  private val messageId: Long,
  private val resultCode: Int,
  private val runAttempt: Int,
  private val isMultipart: Boolean,
  private val pdu: ByteArray?,
  private val format: String?
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalSmsStatusJob"

    private val TAG = Log.tag(SmsStatusJob::class.java)

    private const val ACTION_SENT = "sent"
    private const val ACTION_DELIVERED = "delivered"

    private const val KEY_ACTION = "action"
    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_RESULT = "result"
    private const val KEY_RUN_ATTEMPT = "run_attempt"
    private const val KEY_IS_MULTIPART = "is_multipart"
    private const val KEY_PDU = "pdu"
    private const val KEY_FORMAT = "format"

    /** GSM/CDMA TP-Status values: 0x00-0x1F complete, 0x20-0x3F still trying, 0x40+ failed. */
    private const val STATUS_PENDING = 0x20
    private const val STATUS_FAILED = 0x40

    fun sent(messageId: Long, resultCode: Int, runAttempt: Int, isMultipart: Boolean): SmsStatusJob {
      return SmsStatusJob(parameters(), ACTION_SENT, messageId, resultCode, runAttempt, isMultipart, null, null)
    }

    fun delivered(messageId: Long, pdu: ByteArray?, format: String?): SmsStatusJob {
      return SmsStatusJob(parameters(), ACTION_DELIVERED, messageId, 0, 0, false, pdu, format)
    }

    private fun parameters(): Parameters {
      return Parameters.Builder()
        .setQueue("ExternalSmsStatusJob")
        .setMaxAttempts(1)
        .build()
    }
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_ACTION, action)
      .putLong(KEY_MESSAGE_ID, messageId)
      .putInt(KEY_RESULT, resultCode)
      .putInt(KEY_RUN_ATTEMPT, runAttempt)
      .putBoolean(KEY_IS_MULTIPART, isMultipart)
      .putString(KEY_PDU, pdu?.let { Base64.encodeWithPadding(it) })
      .putString(KEY_FORMAT, format)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    when (action) {
      ACTION_SENT -> handleSent()
      ACTION_DELIVERED -> handleDelivered()
    }
  }

  private fun handleSent() {
    val record = SignalDatabase.messages.getMessageRecordOrNull(messageId)
    if (record == null) {
      Log.w(TAG, "[$messageId] Message no longer exists.")
      return
    }

    when (resultCode) {
      Activity.RESULT_OK -> {
        Log.i(TAG, "[$messageId] Sent.")
        if (!record.isOutgoing || record.isFailed || !record.isPending) {
          // A later part of a multipart message, or already handled.
          return
        }
        SignalDatabase.messages.markExternalAsSent(messageId)
        SystemMessageStore.recordSentSms(context, record.toRecipient.resolve(), record.body, record.dateSent, record.subscriptionId)
      }

      SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_ERROR_RADIO_OFF -> {
        if (isMultipart) {
          Log.w(TAG, "[$messageId] No service, not retrying a multipart message.")
          ExternalMessageSender.markFailed(context, messageId)
        } else {
          val delay = (30L * (runAttempt + 1)).coerceAtMost(600L).seconds.inWholeMilliseconds
          Log.w(TAG, "[$messageId] No service, retrying in ${delay}ms.")
          AppDependencies.jobManager.add(SmsSendJob(messageId, record.toRecipient, runAttempt + 1, delay))
        }
      }

      else -> {
        Log.w(TAG, "[$messageId] Send failed with result $resultCode.")
        ExternalMessageSender.markFailed(context, messageId)
      }
    }
  }

  private fun handleDelivered() {
    val status = if (pdu != null) {
      val message = if (format != null) SmsMessage.createFromPdu(pdu, format) else {
        @Suppress("DEPRECATION")
        SmsMessage.createFromPdu(pdu)
      }
      message?.status ?: 0
    } else {
      0
    }

    when {
      status >= STATUS_FAILED -> {
        Log.w(TAG, "[$messageId] Carrier reported delivery failure ($status).")
        ExternalMessageSender.markFailed(context, messageId)
      }
      status >= STATUS_PENDING -> Log.i(TAG, "[$messageId] Delivery still pending ($status).")
      else -> {
        Log.i(TAG, "[$messageId] Delivered.")
        SignalDatabase.messages.markExternalAsDelivered(messageId, System.currentTimeMillis())
      }
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() = Unit

  class Factory : Job.Factory<SmsStatusJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): SmsStatusJob {
      val data = JsonJobData.deserialize(serializedData)
      return SmsStatusJob(
        parameters,
        data.getString(KEY_ACTION),
        data.getLong(KEY_MESSAGE_ID),
        data.getInt(KEY_RESULT),
        data.getInt(KEY_RUN_ATTEMPT),
        data.getBooleanOrDefault(KEY_IS_MULTIPART, true),
        data.getStringOrDefault(KEY_PDU, null)?.let { Base64.decode(it) },
        data.getStringOrDefault(KEY_FORMAT, null)
      )
    }
  }
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.sms

import android.telephony.SmsMessage
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.ExternalIncomingMessages
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.external.system.SystemMessageStore
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob

/**
 * Parses the PDUs of an incoming SMS broadcast and stores the message.
 */
class SmsReceiveJob private constructor(
  parameters: Parameters,
  private val pdus: List<ByteArray>,
  private val format: String?,
  private val subscriptionId: Int
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalSmsReceiveJob"

    private val TAG = Log.tag(SmsReceiveJob::class.java)

    private const val KEY_PDUS = "pdus"
    private const val KEY_FORMAT = "format"
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"
  }

  constructor(pdus: List<ByteArray>, format: String?, subscriptionId: Int) : this(
    Parameters.Builder()
      .setQueue("ExternalSmsReceiveJob")
      .setMaxAttempts(25)
      .setLifespan(Parameters.IMMORTAL)
      .build(),
    pdus,
    format,
    subscriptionId
  )

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putStringArray(KEY_PDUS, pdus.map { Base64.encodeWithPadding(it) }.toTypedArray())
      .putString(KEY_FORMAT, format)
      .putInt(KEY_SUBSCRIPTION_ID, subscriptionId)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    val messages: List<SmsMessage> = pdus.mapNotNull { pdu ->
      if (format != null) {
        SmsMessage.createFromPdu(pdu, format)
      } else {
        @Suppress("DEPRECATION")
        SmsMessage.createFromPdu(pdu)
      }
    }

    if (messages.isEmpty()) {
      Log.w(TAG, "No parsable PDUs.")
      return
    }

    val first = messages.first()
    val body = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
    val address = first.displayOriginatingAddress ?: first.originatingAddress

    if (isExempt(first, address, body)) {
      Log.i(TAG, "Ignoring exempt message.")
      return
    }

    val from = ExternalIncomingMessages.resolveAddress(address)
    if (from == null) {
      Log.w(TAG, "Could not resolve a sender for an incoming SMS.")
      return
    }

    val sentTime = first.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

    val result = ExternalIncomingMessages.insert(
      context = context,
      type = ExternalTransportType.SMS,
      from = from,
      body = body,
      sentTimeMillis = sentTime,
      subscriptionId = subscriptionId
    )

    if (result != null && address != null) {
      SystemMessageStore.recordReceivedSms(context, address, body, sentTime, subscriptionId)
    }
  }

  private fun isExempt(message: SmsMessage, address: String?, body: String): Boolean {
    // Class 0 ("flash") messages are meant to be shown and discarded by the system.
    if (message.messageClass == SmsMessage.MessageClass.CLASS_0) {
      return true
    }

    // Visual voicemail and carrier control messages.
    val shortSender = (address?.length ?: 0) < 7
    return shortSender && (body.uppercase().startsWith("//ANDROID:") || body.startsWith("//BREW:"))
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    Log.w(TAG, "Failed to process an incoming SMS.")
  }

  class Factory : Job.Factory<SmsReceiveJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): SmsReceiveJob {
      val data = JsonJobData.deserialize(serializedData)
      return SmsReceiveJob(
        parameters,
        data.getStringArray(KEY_PDUS).map { Base64.decode(it) },
        data.getStringOrDefault(KEY_FORMAT, null),
        data.getIntOrDefault(KEY_SUBSCRIPTION_ID, -1)
      )
    }
  }
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import org.signal.core.util.Base64
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.mms.pdu.NotificationInd
import org.thoughtcrime.securesms.external.mms.pdu.PduHeaders
import org.thoughtcrime.securesms.external.mms.pdu.PduParser
import org.thoughtcrime.securesms.external.sms.SmsSendJob
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob

/**
 * Asks the platform MMS service to download the message announced by an MMS notification. The result
 * comes back through [MmsStatusReceiver] and is processed by [MmsResultJob].
 */
class MmsDownloadJob private constructor(
  parameters: Parameters,
  private val notificationPdu: ByteArray?,
  private val contentLocation: String?,
  private val transactionId: ByteArray?,
  private val subscriptionId: Int,
  private val attempt: Int
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalMmsDownloadJob"

    private val TAG = Log.tag(MmsDownloadJob::class.java)

    private const val KEY_NOTIFICATION = "notification"
    private const val KEY_CONTENT_LOCATION = "content_location"
    private const val KEY_TRANSACTION_ID = "transaction_id"
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"
    private const val KEY_ATTEMPT = "attempt"

    @JvmStatic
    fun fromNotification(pdu: ByteArray, subscriptionId: Int): MmsDownloadJob {
      return MmsDownloadJob(parameters(0), pdu, null, null, subscriptionId, 0)
    }

    @JvmStatic
    fun retry(contentLocation: String, transactionId: ByteArray?, subscriptionId: Int, attempt: Int, delayMs: Long): MmsDownloadJob {
      return MmsDownloadJob(parameters(delayMs), null, contentLocation, transactionId, subscriptionId, attempt)
    }

    private fun parameters(delayMs: Long): Parameters {
      return Parameters.Builder()
        .setQueue("ExternalMmsDownloadJob")
        .setMaxAttempts(1)
        .setLifespan(Parameters.IMMORTAL)
        .setInitialDelay(delayMs)
        .build()
    }
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_NOTIFICATION, notificationPdu?.let { Base64.encodeWithPadding(it) })
      .putString(KEY_CONTENT_LOCATION, contentLocation)
      .putString(KEY_TRANSACTION_ID, transactionId?.let { Base64.encodeWithPadding(it) })
      .putInt(KEY_SUBSCRIPTION_ID, subscriptionId)
      .putInt(KEY_ATTEMPT, attempt)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    var location = contentLocation
    var transaction = transactionId

    if (notificationPdu != null) {
      PduParser.setParseContentDisposition(MmsConfig.get(context, subscriptionId).supportsContentDisposition)
      val pdu = try {
        PduParser(notificationPdu).parse()
      } catch (e: RuntimeException) {
        Log.w(TAG, "Unparsable MMS notification.", e)
        null
      }

      if (pdu == null || pdu.messageType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
        Log.w(TAG, "Not an MMS notification (type ${pdu?.messageType}).")
        return
      }

      val notification = pdu as NotificationInd
      location = notification.contentLocation?.let { String(it, Charsets.UTF_8) }
      transaction = notification.transactionId
    }

    if (location.isNullOrEmpty()) {
      Log.w(TAG, "MMS notification without a content location.")
      return
    }

    val (_, uri) = MmsFileProvider.create(context, "download-${System.currentTimeMillis()}-${System.nanoTime()}.pdu")
    MmsFileProvider.grantToPhone(context, uri)

    val downloadedIntent = Intent(MmsStatusReceiver.ACTION_DOWNLOADED, Uri.parse("signal-mms://downloaded/${System.nanoTime()}"), context, MmsStatusReceiver::class.java)
      .putExtra(MmsStatusReceiver.EXTRA_FILE_URI, uri.toString())
      .putExtra(MmsStatusReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId)
      .putExtra(MmsStatusReceiver.EXTRA_TRANSACTION_ID, transaction)
      .putExtra(MmsStatusReceiver.EXTRA_CONTENT_LOCATION, location)
      .putExtra(MmsStatusReceiver.EXTRA_ATTEMPT, attempt)

    val pendingIntent = PendingIntent.getBroadcast(context, 0, downloadedIntent, PendingIntentFlags.mutable())

    Log.i(TAG, "Downloading MMS (attempt $attempt).")
    SmsSendJob.getSmsManager(context, subscriptionId).downloadMultimediaMessage(context, location, uri, null, pendingIntent)
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    Log.w(TAG, "Could not start an MMS download.")
  }

  class Factory : Job.Factory<MmsDownloadJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MmsDownloadJob {
      val data = JsonJobData.deserialize(serializedData)
      return MmsDownloadJob(
        parameters,
        data.getStringOrDefault(KEY_NOTIFICATION, null)?.let { Base64.decode(it) },
        data.getStringOrDefault(KEY_CONTENT_LOCATION, null),
        data.getStringOrDefault(KEY_TRANSACTION_ID, null)?.let { Base64.decode(it) },
        data.getIntOrDefault(KEY_SUBSCRIPTION_ID, -1),
        data.getIntOrDefault(KEY_ATTEMPT, 0)
      )
    }
  }
}

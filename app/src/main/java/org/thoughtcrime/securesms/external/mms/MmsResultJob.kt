/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.app.Activity
import android.net.Uri
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.Base64
import org.signal.core.util.contentproviders.BlobProvider
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.ExternalIncomingMessages
import org.thoughtcrime.securesms.external.ExternalMessageSender
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.external.mms.pdu.CharacterSets
import org.thoughtcrime.securesms.external.mms.pdu.ContentType
import org.thoughtcrime.securesms.external.mms.pdu.NotifyRespInd
import org.thoughtcrime.securesms.external.mms.pdu.PduComposer
import org.thoughtcrime.securesms.external.mms.pdu.PduHeaders
import org.thoughtcrime.securesms.external.mms.pdu.PduParser
import org.thoughtcrime.securesms.external.mms.pdu.RetrieveConf
import org.thoughtcrime.securesms.external.mms.pdu.SendConf
import org.thoughtcrime.securesms.external.sms.SmsSendJob
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.SignalE164Util
import kotlin.time.Duration.Companion.minutes

/**
 * Processes the platform's answer to an MMS send or download.
 */
class MmsResultJob private constructor(
  parameters: Parameters,
  private val action: String,
  private val messageId: Long,
  private val resultCode: Int,
  private val httpStatus: Int,
  private val response: ByteArray?,
  private val fileUri: String?,
  private val subscriptionId: Int,
  private val transactionId: ByteArray?,
  private val contentLocation: String?,
  private val attempt: Int
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalMmsResultJob"

    private val TAG = Log.tag(MmsResultJob::class.java)

    private const val ACTION_SENT = "sent"
    private const val ACTION_DOWNLOADED = "downloaded"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    private const val KEY_ACTION = "action"
    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_RESULT = "result"
    private const val KEY_HTTP = "http"
    private const val KEY_RESPONSE = "response"
    private const val KEY_FILE_URI = "file_uri"
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"
    private const val KEY_TRANSACTION_ID = "transaction_id"
    private const val KEY_CONTENT_LOCATION = "content_location"
    private const val KEY_ATTEMPT = "attempt"

    fun sent(messageId: Long, resultCode: Int, httpStatus: Int, response: ByteArray?, fileUri: String?): MmsResultJob {
      return MmsResultJob(parameters(), ACTION_SENT, messageId, resultCode, httpStatus, response, fileUri, -1, null, null, 0)
    }

    fun downloaded(resultCode: Int, httpStatus: Int, fileUri: String?, subscriptionId: Int, transactionId: ByteArray?, contentLocation: String?, attempt: Int): MmsResultJob {
      return MmsResultJob(parameters(), ACTION_DOWNLOADED, -1, resultCode, httpStatus, null, fileUri, subscriptionId, transactionId, contentLocation, attempt)
    }

    private fun parameters(): Parameters {
      return Parameters.Builder()
        .setQueue("ExternalMmsResultJob")
        .setMaxAttempts(1)
        .setLifespan(Parameters.IMMORTAL)
        .build()
    }
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_ACTION, action)
      .putLong(KEY_MESSAGE_ID, messageId)
      .putInt(KEY_RESULT, resultCode)
      .putInt(KEY_HTTP, httpStatus)
      .putString(KEY_RESPONSE, response?.let { Base64.encodeWithPadding(it) })
      .putString(KEY_FILE_URI, fileUri)
      .putInt(KEY_SUBSCRIPTION_ID, subscriptionId)
      .putString(KEY_TRANSACTION_ID, transactionId?.let { Base64.encodeWithPadding(it) })
      .putString(KEY_CONTENT_LOCATION, contentLocation)
      .putInt(KEY_ATTEMPT, attempt)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    when (action) {
      ACTION_SENT -> handleSent()
      ACTION_DOWNLOADED -> handleDownloaded()
    }
  }

  private fun handleSent() {
    deleteFile()

    if (messageId < 0) {
      return
    }

    if (resultCode != Activity.RESULT_OK) {
      Log.w(TAG, "[$messageId] MMS send failed: result $resultCode, HTTP $httpStatus")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val conf = response?.let {
      try {
        PduParser(it).parse() as? SendConf
      } catch (e: RuntimeException) {
        Log.w(TAG, "[$messageId] Unparsable send confirmation.", e)
        null
      }
    }

    if (conf != null && conf.responseStatus != PduHeaders.RESPONSE_STATUS_OK) {
      Log.w(TAG, "[$messageId] MMSC rejected the message: ${conf.responseStatus}")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    Log.i(TAG, "[$messageId] MMS sent.")
    SignalDatabase.messages.markExternalAsSent(messageId)
  }

  private fun handleDownloaded() {
    if (resultCode != Activity.RESULT_OK) {
      deleteFile()
      Log.w(TAG, "MMS download failed: result $resultCode, HTTP $httpStatus, attempt $attempt")
      if (contentLocation != null && attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
        AppDependencies.jobManager.add(MmsDownloadJob.retry(contentLocation, transactionId, subscriptionId, attempt + 1, (attempt + 1).minutes.inWholeMilliseconds))
      }
      return
    }

    val file = fileUri?.let { MmsFileProvider.fileFor(context, Uri.parse(it)) }
    val bytes = file?.takeIf { it.exists() }?.readBytes()
    file?.delete()

    if (bytes == null || bytes.isEmpty()) {
      Log.w(TAG, "Downloaded MMS is empty.")
      return
    }

    PduParser.setParseContentDisposition(MmsConfig.get(context, subscriptionId).supportsContentDisposition)
    val retrieved = try {
      PduParser(bytes).parse() as? RetrieveConf
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unparsable MMS.", e)
      null
    }

    if (retrieved == null) {
      Log.w(TAG, "Downloaded data was not a RetrieveConf.")
      return
    }

    store(retrieved)
    acknowledge(retrieved.transactionId ?: transactionId)
  }

  private fun store(retrieved: RetrieveConf) {
    val fromAddress = retrieved.from?.string
    val from: RecipientId = ExternalIncomingMessages.resolveAddress(fromAddress) ?: run {
      Log.w(TAG, "MMS without a usable sender.")
      return
    }

    val others: Set<RecipientId> = (retrieved.to.orEmpty() + retrieved.cc.orEmpty())
      .mapNotNull { it?.string }
      .filterNot { isSelf(it) }
      .mapNotNull { ExternalIncomingMessages.resolveAddress(it) }
      .filter { it != from }
      .toSet()

    val groupId = if (others.isNotEmpty() && MmsConfig.get(context, subscriptionId).groupMmsEnabled) {
      SignalDatabase.groups.getOrCreateMmsGroupForMembers(others + from + Recipient.self().id)
    } else {
      null
    }

    val textParts = mutableListOf<String>()
    val attachments = mutableListOf<Attachment>()

    retrieved.subject?.string?.takeIf { it.isNotBlank() }?.let { textParts += it }

    val body = retrieved.body
    for (i in 0 until (body?.partsNum ?: 0)) {
      val part = body!!.getPart(i)
      val contentType = part.contentType?.let { String(it, Charsets.UTF_8) }?.lowercase() ?: continue
      val data = part.data ?: continue

      when {
        contentType == ContentType.APP_SMIL -> Unit
        contentType == ContentType.TEXT_PLAIN -> textParts += decodeText(data, part.charset)
        else -> {
          val name = (part.filename ?: part.name ?: part.contentLocation)?.let { String(it, Charsets.UTF_8) }
          val uri = BlobProvider.getInstance().forData(data).createForSingleSessionInMemory()
          attachments += UriAttachment(
            uri = uri,
            contentType = contentType,
            transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
            size = data.size.toLong(),
            fileName = name,
            voiceNote = false,
            borderless = false,
            videoGif = false,
            quote = false,
            quoteTargetContentType = null,
            caption = null,
            stickerLocator = null,
            blurHash = null,
            audioHash = null,
            transformProperties = null
          )
        }
      }
    }

    val sentTime = retrieved.date.takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis()

    ExternalIncomingMessages.insert(
      context = context,
      type = ExternalTransportType.MMS,
      from = from,
      body = textParts.joinToString("\n").ifEmpty { null },
      sentTimeMillis = sentTime,
      subscriptionId = subscriptionId,
      attachments = attachments,
      groupId = groupId
    )
  }

  private fun decodeText(data: ByteArray, charset: Int): String {
    return try {
      if (charset == CharacterSets.ANY_CHARSET || charset == 0) {
        String(data, Charsets.UTF_8)
      } else {
        String(data, charset(CharacterSets.getMimeName(charset)))
      }
    } catch (e: Exception) {
      String(data, Charsets.UTF_8)
    }
  }

  private fun isSelf(address: String): Boolean {
    if (address.equals("insert-address-token", ignoreCase = true)) {
      return true
    }
    val own = SignalStore.account.e164 ?: return false
    return SignalE164Util.formatAsE164(address) == own
  }

  /** Tells the MMSC we have the message, so it stops re-sending the notification. */
  private fun acknowledge(transactionId: ByteArray?) {
    if (transactionId == null) {
      return
    }

    try {
      val ind = NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, transactionId, PduHeaders.STATUS_RETRIEVED)
      val pdu = PduComposer(context, ind).make() ?: return
      val (file, uri) = MmsFileProvider.create(context, "ack-${System.nanoTime()}.pdu")
      file.writeBytes(pdu)
      MmsFileProvider.grantToPhone(context, uri)
      SmsSendJob.getSmsManager(context, subscriptionId).sendMultimediaMessage(context, uri, null, null, null)
    } catch (e: Exception) {
      Log.w(TAG, "Could not acknowledge the MMS.", e)
    }
  }

  private fun deleteFile() {
    fileUri?.let { MmsFileProvider.fileFor(context, Uri.parse(it))?.delete() }
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() = Unit

  class Factory : Job.Factory<MmsResultJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MmsResultJob {
      val data = JsonJobData.deserialize(serializedData)
      return MmsResultJob(
        parameters,
        data.getString(KEY_ACTION),
        data.getLongOrDefault(KEY_MESSAGE_ID, -1),
        data.getInt(KEY_RESULT),
        data.getIntOrDefault(KEY_HTTP, 0),
        data.getStringOrDefault(KEY_RESPONSE, null)?.let { Base64.decode(it) },
        data.getStringOrDefault(KEY_FILE_URI, null),
        data.getIntOrDefault(KEY_SUBSCRIPTION_ID, -1),
        data.getStringOrDefault(KEY_TRANSACTION_ID, null)?.let { Base64.decode(it) },
        data.getStringOrDefault(KEY_CONTENT_LOCATION, null),
        data.getIntOrDefault(KEY_ATTEMPT, 0)
      )
    }
  }
}

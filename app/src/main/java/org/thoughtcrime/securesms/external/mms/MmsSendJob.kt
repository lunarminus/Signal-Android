/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.StreamUtil
import org.signal.core.util.logging.Log
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.external.ExternalMessageSender
import org.thoughtcrime.securesms.external.mms.pdu.CharacterSets
import org.thoughtcrime.securesms.external.mms.pdu.ContentType
import org.thoughtcrime.securesms.external.mms.pdu.EncodedStringValue
import org.thoughtcrime.securesms.external.mms.pdu.PduBody
import org.thoughtcrime.securesms.external.mms.pdu.PduComposer
import org.thoughtcrime.securesms.external.mms.pdu.PduHeaders
import org.thoughtcrime.securesms.external.mms.pdu.PduPart
import org.thoughtcrime.securesms.external.mms.pdu.SendReq
import org.thoughtcrime.securesms.external.sms.SmsSendJob
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.ImageCompressionUtil
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.IOException

/**
 * Builds an MMS SendReq for one outgoing message and hands it to the platform MMS service. The result
 * comes back through [MmsStatusReceiver].
 */
class MmsSendJob private constructor(
  parameters: Parameters,
  private val messageId: Long
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalMmsSendJob"

    private val TAG = Log.tag(MmsSendJob::class.java)

    private const val KEY_MESSAGE_ID = "message_id"

    /** Room left in the carrier's size limit for headers, SMIL and part framing. */
    private const val OVERHEAD_BYTES = 8 * 1024

    private const val INSERT_ADDRESS_TOKEN = "insert-address-token"
  }

  constructor(messageId: Long, destination: Recipient) : this(
    Parameters.Builder()
      .setQueue(destination.id.toQueueKey() + "::MMS")
      .setMaxAttempts(5)
      .setLifespan(Parameters.IMMORTAL)
      .build(),
    messageId
  )

  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong(KEY_MESSAGE_ID, messageId).serialize()

  override fun getFactoryKey(): String = KEY

  override fun onAdded() {
    SignalDatabase.messages.markAsSending(messageId)
  }

  override fun onRun() {
    val record = SignalDatabase.messages.getMessageRecordOrNull(messageId)
    if (record == null) {
      Log.w(TAG, "[$messageId] Message no longer exists.")
      return
    }

    if (!record.isPending && !record.isFailed) {
      Log.w(TAG, "[$messageId] Already sent.")
      return
    }

    if (record.isSecure) {
      Log.w(TAG, "[$messageId] Refusing to send a Signal message over MMS.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val threadRecipient = SignalDatabase.threads.getRecipientForThreadId(record.threadId)
    if (threadRecipient == null) {
      Log.w(TAG, "[$messageId] No thread recipient.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val config = MmsConfig.get(context, record.subscriptionId)

    val request = try {
      buildSendReq(threadRecipient, record.body, SignalDatabase.attachments.getAttachmentsForMessage(messageId), config)
    } catch (e: UndeliverableException) {
      Log.w(TAG, "[$messageId] ${e.message}")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val pdu = PduComposer(context, request).make()
    if (pdu == null || pdu.size > config.maxMessageBytes) {
      Log.w(TAG, "[$messageId] Could not compose a PDU within ${config.maxMessageBytes} bytes (got ${pdu?.size}).")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val (file, uri) = MmsFileProvider.create(context, "send-$messageId-${System.currentTimeMillis()}.pdu")
    file.writeBytes(pdu)
    MmsFileProvider.grantToPhone(context, uri)

    val sentIntent = Intent(MmsStatusReceiver.ACTION_SENT, Uri.parse("signal-mms://sent/$messageId/${System.nanoTime()}"), context, MmsStatusReceiver::class.java)
      .putExtra(MmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
      .putExtra(MmsStatusReceiver.EXTRA_FILE_URI, uri.toString())

    val pendingIntent = PendingIntent.getBroadcast(context, 0, sentIntent, PendingIntentFlags.mutable())

    Log.i(TAG, "[$messageId] Sending MMS of ${pdu.size} bytes to ${request.to?.size ?: 0} recipient(s).")

    try {
      val smsManager = SmsSendJob.getSmsManager(context, record.subscriptionId)
      smsManager.sendMultimediaMessage(context, uri, null, null, pendingIntent)
    } catch (e: Exception) {
      Log.w(TAG, "[$messageId] Platform refused the MMS.", e)
      file.delete()
      ExternalMessageSender.markFailed(context, messageId)
    }
  }

  @Throws(UndeliverableException::class)
  private fun buildSendReq(threadRecipient: Recipient, text: String?, attachments: List<DatabaseAttachment>, config: MmsConfig): SendReq {
    val request = SendReq()

    val ownNumber = SignalStore.account.e164
    request.from = EncodedStringValue(if (ownNumber.isNullOrEmpty()) INSERT_ADDRESS_TOKEN else ownNumber)

    val destinations: List<String> = if (threadRecipient.isMmsGroup) {
      SignalDatabase.groups.getGroupMembers(threadRecipient.requireGroupId(), GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF).map {
        if (!it.hasSmsAddress) throw UndeliverableException("Group member ${it.id} has no SMS address")
        it.requireSmsAddress()
      }
    } else {
      if (!threadRecipient.hasSmsAddress) throw UndeliverableException("Recipient has no SMS address")
      listOf(threadRecipient.requireSmsAddress())
    }

    if (destinations.isEmpty()) {
      throw UndeliverableException("No destinations")
    }

    destinations.forEach { request.addTo(EncodedStringValue(it)) }
    request.date = System.currentTimeMillis() / 1000

    val body = PduBody()
    var budget = config.maxMessageBytes - OVERHEAD_BYTES
    var index = 0

    if (!text.isNullOrEmpty()) {
      val data = text.toByteArray(Charsets.UTF_8)
      body.addPart(textPart("text_$index", data))
      budget -= data.size
      index++
    }

    val media = attachments.filter { !it.quote }
    for (attachment in media) {
      val contentType = attachment.contentType ?: MediaUtil.OCTET
      val data = try {
        readAttachment(attachment, contentType, budget, config)
      } catch (e: IOException) {
        throw UndeliverableException("Could not read attachment: ${e.message}")
      } ?: throw UndeliverableException("Attachment of ${attachment.size} bytes doesn't fit the carrier limit")

      val finalType = if (data.second) MediaUtil.IMAGE_JPEG else contentType
      val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(finalType) ?: "bin"
      val name = "part_$index.$extension"

      val part = PduPart().apply {
        setContentType(finalType.toByteArray())
        setContentLocation(name.toByteArray())
        setName(name.toByteArray())
        setContentId("<part_$index>".toByteArray())
        if (finalType.startsWith("text/")) setCharset(CharacterSets.UTF_8)
        setData(data.first)
      }

      body.addPart(part)
      budget -= data.first.size
      index++
    }

    if (budget < 0) {
      throw UndeliverableException("Message is larger than the carrier limit of ${config.maxMessageBytes} bytes")
    }

    val smil = PduPart().apply {
      setContentId("<smil>".toByteArray())
      setContentLocation("smil.xml".toByteArray())
      setContentType(ContentType.APP_SMIL.toByteArray())
      setData(MmsSmil.build(body))
    }
    body.addPart(0, smil)

    request.body = body
    request.messageSize = body.let { b -> (0 until b.partsNum).sumOf { (b.getPart(it).data?.size ?: 0).toLong() } }
    request.messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
    request.expiry = 7L * 24 * 60 * 60
    request.priority = PduHeaders.PRIORITY_NORMAL
    request.deliveryReport = PduHeaders.VALUE_NO
    request.readReport = PduHeaders.VALUE_NO

    return request
  }

  private fun textPart(name: String, data: ByteArray): PduPart {
    return PduPart().apply {
      setCharset(CharacterSets.UTF_8)
      setContentType(ContentType.TEXT_PLAIN.toByteArray())
      setContentLocation("$name.txt".toByteArray())
      setName("$name.txt".toByteArray())
      setContentId("<$name>".toByteArray())
      setData(data)
    }
  }

  /**
   * Returns the bytes to send for [attachment] and whether they were re-encoded as JPEG, or null if it
   * can't be made to fit in [budget] bytes.
   */
  @Throws(IOException::class)
  private fun readAttachment(attachment: DatabaseAttachment, contentType: String, budget: Int, config: MmsConfig): Pair<ByteArray, Boolean>? {
    if (attachment.size in 1..budget.toLong() && !needsResize(contentType)) {
      return StreamUtil.readFully(SignalDatabase.attachments.getAttachmentStream(attachment.attachmentId, 0)) to false
    }

    if (!MediaUtil.isImageType(contentType) || MediaUtil.isGif(contentType) || attachment.uri == null) {
      return if (attachment.size <= budget) {
        StreamUtil.readFully(SignalDatabase.attachments.getAttachmentStream(attachment.attachmentId, 0)) to false
      } else {
        null
      }
    }

    // Shrink images until they fit: first by dimensions, then by quality.
    val maxDimension = maxOf(config.maxImageWidth, config.maxImageHeight).coerceAtLeast(320)
    val dimensions = listOf(maxDimension * 2, (maxDimension * 1.5).toInt(), maxDimension, (maxDimension * 0.75).toInt(), maxDimension / 2)
    val qualities = listOf(80, 65, 50)

    for (dimension in dimensions) {
      for (quality in qualities) {
        try {
          val result = ImageCompressionUtil.compress(context, contentType, MediaUtil.IMAGE_JPEG, DecryptableUri(attachment.uri!!), dimension, quality)
          if (result.data.size <= budget) {
            Log.i(TAG, "[$messageId] Image shrunk to ${result.data.size} bytes (${result.width}x${result.height}, q$quality).")
            return result.data to true
          }
        } catch (e: Exception) {
          Log.w(TAG, "[$messageId] Image compression failed", e)
          return null
        }
      }
    }

    return null
  }

  private fun needsResize(contentType: String): Boolean {
    // HEIC/WEBP aren't widely supported by other phones' MMS apps; send them as JPEG.
    return contentType == "image/heic" || contentType == "image/heif" || contentType == "image/webp"
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    ExternalMessageSender.markFailed(context, messageId)
  }

  private class UndeliverableException(message: String) : Exception(message)

  class Factory : Job.Factory<MmsSendJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MmsSendJob {
      return MmsSendJob(parameters, JsonJobData.deserialize(serializedData).getLong(KEY_MESSAGE_ID))
    }
  }
}

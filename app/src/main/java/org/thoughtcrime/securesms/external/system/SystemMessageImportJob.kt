/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.ExternalIncomingMessages
import org.thoughtcrime.securesms.external.ExternalMessagingPreferences
import org.thoughtcrime.securesms.external.ExternalTransportType
import org.thoughtcrime.securesms.external.ExternalTransports
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.BaseJob
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Copies the texts already on the phone (Android's SMS/MMS store) into Signal, so old conversations
 * show up next to new ones. Only messages newer than the last import are read, and anything already in
 * the conversation (for example, a text we wrote to the store ourselves) is skipped, so running it
 * again is safe.
 */
class SystemMessageImportJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalSystemMessageImportJob"

    private val TAG = Log.tag(SystemMessageImportJob::class.java)

    private const val MMS_ADDRESS_FROM = 137
    private const val MMS_ADDRESS_TO = 151
    private const val MMS_ADDRESS_CC = 130
    private const val MMS_TYPE_SEND_REQ = 128
    private const val MMS_TYPE_RETRIEVE_CONF = 132

    /** Attachments bigger than this are left out of the import. */
    private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

    @JvmStatic
    fun canRun(context: Context): Boolean {
      return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
  }

  constructor() : this(
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxInstancesForFactory(1)
      .setMaxAttempts(1)
      .setLifespan(Parameters.IMMORTAL)
      .build()
  )

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  private val touchedThreads = mutableSetOf<Long>()
  private var imported = 0

  override fun onRun() {
    if (!canRun(context)) {
      Log.w(TAG, "No permission to read the system message store.")
      return
    }

    ExternalMessagingPreferences.importState = ExternalMessagingPreferences.ImportState.RUNNING

    try {
      importSms()
      importMms()
    } finally {
      touchedThreads.forEach { threadId ->
        SignalDatabase.threads.update(threadId, unarchive = false)
        SignalDatabase.threads.updateReadState(threadId)
      }
      ExternalMessagingPreferences.importState = ExternalMessagingPreferences.ImportState.DONE
      ExternalMessagingPreferences.importedMessageCount += imported
      Log.i(TAG, "Imported $imported messages into ${touchedThreads.size} conversations.")
    }
  }

  // region SMS

  private fun importSms() {
    val since = ExternalMessagingPreferences.lastImportedSmsId
    var newest = since

    val projection = arrayOf(
      Telephony.Sms._ID,
      Telephony.Sms.ADDRESS,
      Telephony.Sms.BODY,
      Telephony.Sms.DATE,
      Telephony.Sms.DATE_SENT,
      Telephony.Sms.TYPE,
      Telephony.Sms.READ,
      Telephony.Sms.SUBSCRIPTION_ID
    )

    query(Telephony.Sms.CONTENT_URI, projection, "${Telephony.Sms._ID} > ?", arrayOf(since.toString()), "${Telephony.Sms._ID} ASC")?.use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.long(Telephony.Sms._ID)
        try {
          importSms(cursor)
        } catch (e: Exception) {
          Log.w(TAG, "Skipping SMS $id", e)
        }
        newest = maxOf(newest, id)
      }
    }

    ExternalMessagingPreferences.lastImportedSmsId = newest
  }

  private fun importSms(cursor: Cursor) {
    val address = cursor.string(Telephony.Sms.ADDRESS) ?: return
    val body = cursor.string(Telephony.Sms.BODY) ?: ""
    val date = cursor.long(Telephony.Sms.DATE)
    val dateSent = cursor.long(Telephony.Sms.DATE_SENT).takeIf { it > 0 } ?: date
    val type = cursor.int(Telephony.Sms.TYPE)
    val read = cursor.int(Telephony.Sms.READ) == 1
    val subscriptionId = cursor.int(Telephony.Sms.SUBSCRIPTION_ID, -1)

    val recipientId = ExternalIncomingMessages.resolveAddress(address) ?: return

    when (type) {
      Telephony.Sms.MESSAGE_TYPE_INBOX -> {
        val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipientId))
        if (SignalDatabase.messages.hasMessageWithDateSentAndBody(threadId, dateSent, body)) return

        val result = ExternalIncomingMessages.insert(
          context = context,
          type = ExternalTransportType.SMS,
          from = recipientId,
          body = body,
          sentTimeMillis = dateSent,
          receivedTimeMillis = date,
          subscriptionId = subscriptionId,
          notify = false
        ) ?: return

        SignalDatabase.messages.setImportedMessageState(result.messageId, dateReceived = date, read = read)
        touchedThreads += result.threadId
        imported++
      }

      Telephony.Sms.MESSAGE_TYPE_SENT -> {
        val recipient = Recipient.resolved(recipientId)
        val message = OutgoingMessage.sms(recipient, body).copy(sentTimeMillis = date, subscriptionId = subscriptionId)
        insertSent(ExternalTransports.tag(message, ExternalTransportType.SMS), recipient, date)
      }

      else -> Unit // Drafts, queued and failed messages aren't history worth keeping.
    }
  }

  // endregion

  // region MMS

  private fun importMms() {
    val since = ExternalMessagingPreferences.lastImportedMmsId
    var newest = since

    val projection = arrayOf(
      Telephony.Mms._ID,
      Telephony.Mms.DATE,
      Telephony.Mms.DATE_SENT,
      Telephony.Mms.MESSAGE_BOX,
      Telephony.Mms.MESSAGE_TYPE,
      Telephony.Mms.READ,
      Telephony.Mms.SUBJECT,
      Telephony.Mms.SUBSCRIPTION_ID
    )

    query(Telephony.Mms.CONTENT_URI, projection, "${Telephony.Mms._ID} > ?", arrayOf(since.toString()), "${Telephony.Mms._ID} ASC")?.use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.long(Telephony.Mms._ID)
        try {
          importMms(cursor, id)
        } catch (e: Exception) {
          Log.w(TAG, "Skipping MMS $id", e)
        }
        newest = maxOf(newest, id)
      }
    }

    ExternalMessagingPreferences.lastImportedMmsId = newest
  }

  private fun importMms(cursor: Cursor, id: Long) {
    val messageType = cursor.int(Telephony.Mms.MESSAGE_TYPE)
    if (messageType != MMS_TYPE_SEND_REQ && messageType != MMS_TYPE_RETRIEVE_CONF) {
      return // Notifications for messages that were never downloaded.
    }

    val box = cursor.int(Telephony.Mms.MESSAGE_BOX)
    val date = cursor.long(Telephony.Mms.DATE) * 1000
    val dateSent = cursor.long(Telephony.Mms.DATE_SENT).takeIf { it > 0 }?.times(1000) ?: date
    val read = cursor.int(Telephony.Mms.READ) == 1
    val subject = cursor.string(Telephony.Mms.SUBJECT)
    val subscriptionId = cursor.int(Telephony.Mms.SUBSCRIPTION_ID, -1)

    val self = Recipient.self().id
    val addresses = readMmsAddresses(id)
    val from = addresses.firstOrNull { it.first == MMS_ADDRESS_FROM }?.second
    val others = addresses.filter { it.first == MMS_ADDRESS_TO || it.first == MMS_ADDRESS_CC }.map { it.second }.toSet() - self

    val (text, attachments) = readMmsParts(id)
    val body = listOfNotNull(subject?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotBlank() }).joinToString("\n")

    if (body.isEmpty() && attachments.isEmpty()) {
      return
    }

    when (box) {
      Telephony.Mms.MESSAGE_BOX_INBOX -> {
        val sender = from?.takeIf { it != self } ?: return
        val members = others - sender
        val groupId = if (members.isNotEmpty()) SignalDatabase.groups.getOrCreateMmsGroupForMembers(members + sender + self) else null

        val threadRecipient = if (groupId != null) Recipient.externalGroupExact(groupId) else Recipient.resolved(sender)
        val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(threadRecipient)
        if (SignalDatabase.messages.hasMessageWithDateSentAndBody(threadId, dateSent, body)) return

        val result = ExternalIncomingMessages.insert(
          context = context,
          type = ExternalTransportType.MMS,
          from = sender,
          body = body,
          sentTimeMillis = dateSent,
          receivedTimeMillis = date,
          subscriptionId = subscriptionId,
          attachments = attachments,
          groupId = groupId,
          notify = false
        ) ?: return

        SignalDatabase.messages.setImportedMessageState(result.messageId, dateReceived = date, read = read)
        touchedThreads += result.threadId
        imported++
      }

      Telephony.Mms.MESSAGE_BOX_SENT -> {
        if (others.isEmpty()) return

        val recipient = if (others.size == 1) {
          Recipient.resolved(others.first())
        } else {
          Recipient.externalGroupExact(SignalDatabase.groups.getOrCreateMmsGroupForMembers(others + self))
        }

        val message = OutgoingMessage(
          threadRecipient = recipient,
          sentTimeMillis = date,
          body = body,
          attachments = attachments,
          isSecure = false,
          subscriptionId = subscriptionId
        )
        insertSent(ExternalTransports.tag(message, ExternalTransportType.MMS), recipient, date)
      }

      else -> Unit
    }
  }

  private fun readMmsAddresses(messageId: Long): List<Pair<Int, RecipientId>> {
    val uri = Uri.parse("content://mms/$messageId/addr")
    val result = mutableListOf<Pair<Int, RecipientId>>()

    query(uri, arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE), null, null, null)?.use { cursor ->
      while (cursor.moveToNext()) {
        val address = cursor.string(Telephony.Mms.Addr.ADDRESS) ?: continue
        if (address.equals("insert-address-token", ignoreCase = true)) {
          result += cursor.int(Telephony.Mms.Addr.TYPE) to Recipient.self().id
          continue
        }
        val recipientId = ExternalIncomingMessages.resolveAddress(address) ?: continue
        result += cursor.int(Telephony.Mms.Addr.TYPE) to recipientId
      }
    }

    return result
  }

  private fun readMmsParts(messageId: Long): Pair<String, List<Attachment>> {
    val texts = mutableListOf<String>()
    val attachments = mutableListOf<Attachment>()

    val projection = arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, Telephony.Mms.Part.FILENAME, Telephony.Mms.Part.NAME)
    query(Uri.parse("content://mms/part"), projection, "${Telephony.Mms.Part.MSG_ID} = ?", arrayOf(messageId.toString()), null)?.use { cursor ->
      while (cursor.moveToNext()) {
        val partId = cursor.long(Telephony.Mms.Part._ID)
        val contentType = cursor.string(Telephony.Mms.Part.CONTENT_TYPE)?.lowercase() ?: continue

        when {
          contentType == "application/smil" -> Unit
          contentType == "text/plain" -> cursor.string(Telephony.Mms.Part.TEXT)?.let { texts += it }
          else -> readPart(partId, contentType, cursor.string(Telephony.Mms.Part.FILENAME) ?: cursor.string(Telephony.Mms.Part.NAME))?.let { attachments += it }
        }
      }
    }

    return texts.joinToString("\n") to attachments
  }

  private fun readPart(partId: Long, contentType: String, fileName: String?): Attachment? {
    val partUri = Uri.parse("content://mms/part/$partId")
    val size = context.contentResolver.openAssetFileDescriptor(partUri, "r")?.use { it.length } ?: -1L
    if (size > MAX_ATTACHMENT_BYTES) {
      Log.w(TAG, "Leaving out a $size byte attachment.")
      return null
    }

    val input = context.contentResolver.openInputStream(partUri) ?: return null
    val blobUri = input.use { AppDependencies.blobs.forData(it, size.coerceAtLeast(0)).createForSingleSessionOnDisk(context) }

    return UriAttachment(
      uri = blobUri,
      contentType = contentType,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      size = size.coerceAtLeast(0),
      fileName = fileName,
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

  // endregion

  private fun insertSent(message: OutgoingMessage, recipient: Recipient, date: Long) {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    if (SignalDatabase.messages.hasMessageWithDateSentAndBody(threadId, message.sentTimeMillis, message.body)) return

    val result = SignalDatabase.messages.insertMessageOutbox(message, threadId, forceSms = true, insertListener = null)
    SignalDatabase.messages.markExternalAsSent(result.messageId)
    SignalDatabase.messages.setImportedMessageState(result.messageId, dateReceived = date, read = true)
    touchedThreads += threadId
    imported++
  }

  private fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String?): Cursor? {
    return try {
      context.contentResolver.query(uri, projection, selection, args, order)
    } catch (e: Exception) {
      Log.w(TAG, "Could not read $uri", e)
      null
    }
  }

  private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }
  private fun Cursor.long(column: String): Long = getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L
  private fun Cursor.int(column: String, default: Int = 0): Int = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: default

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    ExternalMessagingPreferences.importState = ExternalMessagingPreferences.ImportState.DONE
    Log.w(TAG, "Import failed.")
  }

  class Factory : Job.Factory<SystemMessageImportJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): SystemMessageImportJob {
      return SystemMessageImportJob(parameters)
    }
  }
}

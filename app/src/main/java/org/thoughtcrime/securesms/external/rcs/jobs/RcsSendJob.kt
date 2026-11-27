/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.external.ExternalMessageSender
import org.thoughtcrime.securesms.external.rcs.RcsManager
import org.thoughtcrime.securesms.external.rcs.sip.RcsSipClient
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Hands one outgoing message to the RCS SIP session. If RCS can't carry it (not registered, contact
 * no longer reachable, or the session rejects it), the message is re-queued over the next external
 * transport that can take it — the same "RCS, then fall back to MMS/SMS" behaviour Google Messages
 * gives — so a message is never silently lost.
 */
class RcsSendJob private constructor(
  parameters: Parameters,
  private val messageId: Long
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalRcsSendJob"
    private val TAG = Log.tag(RcsSendJob::class.java)
    private const val MAX_ATTEMPTS = 3
    private const val KEY_MESSAGE_ID = "message_id"
  }

  constructor(messageId: Long, destination: Recipient) : this(
    Parameters.Builder()
      .setQueue(destination.id.toQueueKey() + "::RCS")
      .setMaxAttempts(MAX_ATTEMPTS)
      .setLifespan(Parameters.IMMORTAL)
      .build(),
    messageId
  )

  override fun serialize(): ByteArray? =
    JsonJobData.Builder().putLong(KEY_MESSAGE_ID, messageId).serialize()

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
      Log.w(TAG, "[$messageId] Already handled. Ignoring.")
      return
    }
    if (record.isSecure) {
      Log.w(TAG, "[$messageId] Refusing to send a Signal message over RCS.")
      ExternalMessageSender.markFailed(context, messageId)
      return
    }

    val recipient = record.toRecipient.resolve()
    val msisdn = if (recipient.hasSmsAddress) recipient.requireSmsAddress() else null
    if (msisdn == null) {
      Log.w(TAG, "[$messageId] Recipient has no address; falling back.")
      fallBack(recipient)
      return
    }

    when (RcsManager.sendMessage(msisdn, record.body)) {
      RcsSipClient.SendOutcome.Sent -> {
        Log.i(TAG, "[$messageId] Handed to RCS session.")
        SignalDatabase.messages.markExternalAsSent(messageId)
      }
      RcsSipClient.SendOutcome.NotRegistered,
      RcsSipClient.SendOutcome.NotReachable -> {
        Log.i(TAG, "[$messageId] RCS unavailable for this message; falling back to MMS/SMS.")
        fallBack(recipient)
      }
      RcsSipClient.SendOutcome.Failed -> {
        Log.w(TAG, "[$messageId] RCS send failed; falling back to MMS/SMS.")
        fallBack(recipient)
      }
    }
  }

  /**
   * Re-route the already-stored message over the best non-RCS external transport. Clearing the RCS
   * tag lets [ExternalTransports.select] pick MMS/SMS next.
   */
  private fun fallBack(recipient: Recipient) {
    val requeued = org.thoughtcrime.securesms.external.rcs.RcsFallback.requeueWithoutRcs(context, messageId, recipient)
    if (!requeued) {
      ExternalMessageSender.markFailed(context, messageId)
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    Log.w(TAG, "[$messageId] onFailure()")
    ExternalMessageSender.markFailed(context, messageId)
  }

  class Factory : Job.Factory<RcsSendJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): RcsSendJob {
      val data = JsonJobData.deserialize(serializedData)
      return RcsSendJob(parameters, data.getLong(KEY_MESSAGE_ID))
    }
  }
}

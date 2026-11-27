/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.external.ExternalIncomingMessages

/**
 * Opens a conversation for sms:, smsto:, mms: and mmsto: links (the "compose a text" entry point other
 * apps use). Android requires the default SMS app to handle these.
 */
class SmsSendToActivity : Activity() {

  companion object {
    private val TAG = Log.tag(SmsSendToActivity::class.java)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val original = intent
    SignalExecutors.BOUNDED.execute {
      val next = try {
        resolveNextIntent(original)
      } catch (e: Exception) {
        Log.w(TAG, "Could not handle send-to intent", e)
        MainActivity.clearTop(this)
      }

      runOnUiThread {
        startActivity(next)
        finish()
      }
    }
  }

  private fun resolveNextIntent(original: Intent): Intent {
    val data: Uri? = original.data
    val (address, body) = when {
      data == null -> null to original.getStringExtra(Intent.EXTRA_TEXT)
      data.scheme == "content" && data.authority == ContactsContract.AUTHORITY -> queryContactAddress(data) to null
      data.scheme == "content" -> null to null
      else -> parseSmsUri(data) to (original.getStringExtra("sms_body") ?: parseBody(data) ?: original.getStringExtra(Intent.EXTRA_TEXT))
    }

    val recipientId = address?.let { ExternalIncomingMessages.resolveAddress(it.substringBefore(',').substringBefore(';')) }
      ?: return MainActivity.clearTop(this)

    // A conversation needs a real thread, so make one for people we've never talked to.
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipientId))
    return ConversationIntents.createBuilderSync(this, recipientId, threadId)
      .withDraftText(body)
      .build()
  }

  private fun parseSmsUri(uri: Uri): String? {
    // e.g. smsto:+15551234567?body=hi or sms:5551234567
    return Uri.decode(uri.schemeSpecificPart?.substringBefore('?'))?.takeIf { it.isNotBlank() }
  }

  private fun parseBody(uri: Uri): String? {
    val query = uri.schemeSpecificPart?.substringAfter('?', "") ?: return null
    return query.split('&')
      .map { it.split('=', limit = 2) }
      .firstOrNull { it.size == 2 && it[0] == "body" }
      ?.let { Uri.decode(it[1]) }
  }

  private fun queryContactAddress(uri: Uri): String? {
    return contentResolver.query(uri, arrayOf(ContactsContract.Data.DATA1), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }
  }
}

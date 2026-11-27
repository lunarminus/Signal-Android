/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.ui

import android.content.Context
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.external.ExternalTransportType

/**
 * Labels SMS/MMS messages in a conversation, so a text that didn't come over Signal (and whose sender
 * may have been faked) never looks like a Signal message.
 */
object ExternalMessageLabels {

  /** "SMS" or "MMS" for a message that didn't go over Signal, or null for a Signal message. */
  @JvmStatic
  fun transportLabel(context: Context, record: MessageRecord): String? {
    if (record.isSecure || record.isUpdate) {
      return null
    }

    val type = ExternalTransportType.fromId(record.messageExtras?.externalTransport)
    return context.getString(if (type == ExternalTransportType.MMS) R.string.ExternalMessage__mms else R.string.ExternalMessage__sms)
  }

  /** Puts the transport label in front of a footer's date, e.g. "SMS · 9:41 PM". */
  @JvmStatic
  fun withTransportLabel(context: Context, record: MessageRecord, dateLabel: String?): String? {
    val label = transportLabel(context, record) ?: return dateLabel
    return if (dateLabel.isNullOrEmpty()) label else context.getString(R.string.ExternalMessage__label_and_date, label, dateLabel)
  }
}

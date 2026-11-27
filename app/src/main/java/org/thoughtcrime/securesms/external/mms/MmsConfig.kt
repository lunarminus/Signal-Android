/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.content.Context
import android.telephony.SmsManager
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.sms.SmsSendJob

/**
 * Carrier MMS limits for one SIM, read from the platform's carrier config.
 */
data class MmsConfig(
  val maxMessageBytes: Int,
  val maxImageWidth: Int,
  val maxImageHeight: Int,
  val supportsContentDisposition: Boolean,
  val groupMmsEnabled: Boolean
) {
  companion object {
    private val TAG = Log.tag(MmsConfig::class.java)

    /** AOSP defaults, used when the carrier config can't be read. */
    private val DEFAULT = MmsConfig(
      maxMessageBytes = 300 * 1024,
      maxImageWidth = 640,
      maxImageHeight = 480,
      supportsContentDisposition = true,
      groupMmsEnabled = true
    )

    @JvmStatic
    fun get(context: Context, subscriptionId: Int): MmsConfig {
      return try {
        @Suppress("DEPRECATION")
        val bundle = SmsSendJob.getSmsManager(context, subscriptionId).carrierConfigValues
        MmsConfig(
          maxMessageBytes = bundle.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT.maxMessageBytes).takeIf { it > 0 } ?: DEFAULT.maxMessageBytes,
          maxImageWidth = bundle.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, DEFAULT.maxImageWidth).takeIf { it > 0 } ?: DEFAULT.maxImageWidth,
          maxImageHeight = bundle.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, DEFAULT.maxImageHeight).takeIf { it > 0 } ?: DEFAULT.maxImageHeight,
          supportsContentDisposition = bundle.getBoolean(SmsManager.MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION, DEFAULT.supportsContentDisposition),
          groupMmsEnabled = bundle.getBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, DEFAULT.groupMmsEnabled)
        )
      } catch (e: Exception) {
        Log.w(TAG, "Could not read carrier MMS config, using defaults.", e)
        DEFAULT
      }
    }
  }
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * User settings for external (non-Signal) messaging. Kept in their own preferences file, separate from
 * upstream's stores, so the fork stays easy to rebase.
 */
object ExternalMessagingPreferences {

  private const val FILE = "external_messaging"

  private const val KEY_ENABLED = "enabled"
  private const val KEY_DELIVERY_REPORTS = "sms_delivery_reports"
  private const val KEY_WRITE_TO_SYSTEM_PROVIDER = "write_to_system_provider"
  private const val KEY_PREFER_SIGNAL = "prefer_signal_when_available"
  private const val KEY_IMPORT_STATE = "system_import_state"
  private const val KEY_IMPORTED_COUNT = "system_import_count"
  private const val KEY_LAST_IMPORTED_SMS_ID = "system_import_last_sms_id"
  private const val KEY_LAST_IMPORTED_MMS_ID = "system_import_last_mms_id"

  private val prefs: SharedPreferences
    get() = AppDependencies.application.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Master switch: whether SMS/MMS can be sent and received in this app. */
  @JvmStatic
  var isEnabled: Boolean
    get() = prefs.getBoolean(KEY_ENABLED, false)
    set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

  /** Ask the carrier for SMS delivery reports. */
  @JvmStatic
  var isDeliveryReportsEnabled: Boolean
    get() = prefs.getBoolean(KEY_DELIVERY_REPORTS, false)
    set(value) = prefs.edit { putBoolean(KEY_DELIVERY_REPORTS, value) }

  /**
   * Copy sent and received carrier messages into Android's system SMS/MMS store, so other apps (backup
   * tools, a different default SMS app later) still see them. Only possible while this app is the
   * default SMS app.
   */
  @JvmStatic
  var isWriteToSystemProviderEnabled: Boolean
    get() = prefs.getBoolean(KEY_WRITE_TO_SYSTEM_PROVIDER, true)
    set(value) = prefs.edit { putBoolean(KEY_WRITE_TO_SYSTEM_PROVIDER, value) }

  /** Default to Signal for contacts who are on Signal, and fall back to SMS/MMS for everyone else. */
  @JvmStatic
  var isPreferSignalWhenAvailable: Boolean
    get() = prefs.getBoolean(KEY_PREFER_SIGNAL, true)
    set(value) = prefs.edit { putBoolean(KEY_PREFER_SIGNAL, value) }

  enum class ImportState { NEVER, RUNNING, DONE }

  /** Where the import of texts already on the phone stands. */
  @JvmStatic
  var importState: ImportState
    get() = ImportState.entries.getOrNull(prefs.getInt(KEY_IMPORT_STATE, 0)) ?: ImportState.NEVER
    set(value) = prefs.edit { putInt(KEY_IMPORT_STATE, value.ordinal) }

  /** How many texts have been imported from the phone so far. */
  @JvmStatic
  var importedMessageCount: Int
    get() = prefs.getInt(KEY_IMPORTED_COUNT, 0)
    set(value) = prefs.edit { putInt(KEY_IMPORTED_COUNT, value) }

  /** The newest system SMS row already imported, so a later import only reads what's new. */
  @JvmStatic
  var lastImportedSmsId: Long
    get() = prefs.getLong(KEY_LAST_IMPORTED_SMS_ID, 0L)
    set(value) = prefs.edit { putLong(KEY_LAST_IMPORTED_SMS_ID, value) }

  /** The newest system MMS row already imported. */
  @JvmStatic
  var lastImportedMmsId: Long
    get() = prefs.getLong(KEY_LAST_IMPORTED_MMS_ID, 0L)
    set(value) = prefs.edit { putLong(KEY_LAST_IMPORTED_MMS_ID, value) }
}

/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * User settings and persisted provisioning state for RCS, kept in their own preferences file so the
 * fork stays easy to rebase (same convention as [org.thoughtcrime.securesms.external.ExternalMessagingPreferences]).
 */
object RcsPreferences {

  private const val FILE = "external_rcs"

  private const val KEY_ENABLED = "enabled"
  private const val KEY_CONFIG_XML = "config_xml"
  private const val KEY_CONFIG_FETCHED_AT = "config_fetched_at"
  private const val KEY_CONFIG_VALIDITY = "config_validity_seconds"
  private const val KEY_CONFIG_VERSION = "config_version"
  private const val KEY_USER_MSISDN = "user_msisdn"
  private const val KEY_LAST_REG_FAILURE = "last_registration_failure"

  private val prefs: SharedPreferences
    get() = AppDependencies.application.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /**
   * Master switch. RCS is opt-in and off by default: it is experimental, needs a Google-backed
   * provisioning step and only works on specific device configurations (see docs/rcs/RCS.md).
   */
  @JvmStatic
  var isEnabled: Boolean
    get() = prefs.getBoolean(KEY_ENABLED, false)
    set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

  /** The last RCS configuration document fetched from the autoconfiguration server, or null. */
  var configXml: String?
    get() = prefs.getString(KEY_CONFIG_XML, null)
    set(value) = prefs.edit { putString(KEY_CONFIG_XML, value) }

  var configVersion: String
    get() = prefs.getString(KEY_CONFIG_VERSION, "0") ?: "0"
    set(value) = prefs.edit { putString(KEY_CONFIG_VERSION, value) }

  var userMsisdn: String?
    get() = prefs.getString(KEY_USER_MSISDN, null)
    set(value) = prefs.edit { putString(KEY_USER_MSISDN, value) }

  private var configFetchedAt: Long
    get() = prefs.getLong(KEY_CONFIG_FETCHED_AT, 0)
    set(value) = prefs.edit { putLong(KEY_CONFIG_FETCHED_AT, value) }

  private var configValiditySeconds: Long
    get() = prefs.getLong(KEY_CONFIG_VALIDITY, 0)
    set(value) = prefs.edit { putLong(KEY_CONFIG_VALIDITY, value) }

  var lastRegistrationFailure: String?
    get() = prefs.getString(KEY_LAST_REG_FAILURE, null)
    set(value) = prefs.edit { putString(KEY_LAST_REG_FAILURE, value) }

  fun recordConfigFetched(validitySeconds: Long) {
    configFetchedAt = System.currentTimeMillis()
    configValiditySeconds = validitySeconds
  }

  /** True once we have a configuration that has not yet passed its validity window. */
  val hasValidConfig: Boolean
    get() {
      val fetched = configFetchedAt
      if (configXml == null || fetched == 0L) return false
      val ageSeconds = (System.currentTimeMillis() - fetched) / 1000
      return ageSeconds < configValiditySeconds
    }

  fun clearProvisioning() {
    prefs.edit {
      remove(KEY_CONFIG_XML)
      remove(KEY_CONFIG_FETCHED_AT)
      remove(KEY_CONFIG_VALIDITY)
      remove(KEY_CONFIG_VERSION)
      remove(KEY_USER_MSISDN)
      remove(KEY_LAST_REG_FAILURE)
    }
  }
}

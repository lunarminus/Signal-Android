/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony

/**
 * Helpers around Android's "default SMS app" role. Android only delivers incoming SMS/MMS
 * (SMS_DELIVER / WAP_PUSH_DELIVER) to the default SMS app, and only that app may write to the system
 * SMS/MMS store.
 */
object DefaultSmsApp {

  @JvmStatic
  fun hasTelephony(context: Context): Boolean {
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) ||
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
  }

  @JvmStatic
  fun isDefault(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 29) {
      val roleManager = context.getSystemService(RoleManager::class.java)
      roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && roleManager.isRoleHeld(RoleManager.ROLE_SMS)
    } else {
      context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
    }
  }

  /** An intent that asks the user to make this app the default SMS app, or null if that isn't possible. */
  @JvmStatic
  fun createRequestIntent(context: Context): Intent? {
    return if (Build.VERSION.SDK_INT >= 29) {
      val roleManager = context.getSystemService(RoleManager::class.java)
      if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
        roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
      } else {
        null
      }
    } else {
      Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
    }
  }

  /** Opens the system screen where the user can pick a different default SMS app. */
  @JvmStatic
  fun createChangeDefaultIntent(): Intent {
    return if (Build.VERSION.SDK_INT >= 24) {
      Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    } else {
      Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    }
  }
}

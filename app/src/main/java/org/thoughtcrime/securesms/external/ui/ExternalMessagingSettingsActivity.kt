/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.external.DefaultSmsApp
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.external.ExternalMessagingPreferences
import org.thoughtcrime.securesms.external.system.SystemMessageImportJob

/**
 * Settings for external (SMS/MMS) messaging: the master switch, the default-SMS-app role and a few
 * transport options.
 */
class ExternalMessagingSettingsActivity : PassphraseRequiredActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, ExternalMessagingSettingsActivity::class.java)

    private val REQUIRED_PERMISSIONS = arrayOf(
      Manifest.permission.SEND_SMS,
      Manifest.permission.RECEIVE_SMS,
      Manifest.permission.READ_SMS,
      Manifest.permission.RECEIVE_MMS,
      Manifest.permission.READ_PHONE_STATE
    )
  }

  private val state = mutableStateOf(SettingsState())

  private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
    val sendGranted = results[Manifest.permission.SEND_SMS] == true || hasPermission(Manifest.permission.SEND_SMS)
    if (sendGranted) {
      ExternalMessagingPreferences.isEnabled = true
      if (!DefaultSmsApp.isDefault(this)) {
        requestDefaultRole()
      } else {
        importOnceIfNeeded()
      }
    } else {
      Toast.makeText(this, R.string.ExternalMessagingSettings__permissions_needed, Toast.LENGTH_LONG).show()
    }
    refresh()
  }

  private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (DefaultSmsApp.isDefault(this)) {
      ExternalMessagingPreferences.isEnabled = true
    }
    importOnceIfNeeded()
    refresh()
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    super.onCreate(savedInstanceState, ready)

    setContent {
      SignalTheme {
        SettingsScreen(
          state = state.value,
          onBack = { finish() },
          onEnabledChanged = ::onEnabledChanged,
          onDefaultAppClicked = ::onDefaultAppClicked,
          onDeliveryReportsChanged = { ExternalMessagingPreferences.isDeliveryReportsEnabled = it; refresh() },
          onSystemCopyChanged = { ExternalMessagingPreferences.isWriteToSystemProviderEnabled = it; refresh() },
          onPreferSignalChanged = { ExternalMessagingPreferences.isPreferSignalWhenAvailable = it; refresh() },
          onImportClicked = ::startImport
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun refresh() {
    state.value = SettingsState(
      enabled = ExternalMessagingPreferences.isEnabled,
      isDefaultApp = DefaultSmsApp.isDefault(this),
      hasTelephony = DefaultSmsApp.hasTelephony(this),
      deliveryReports = ExternalMessagingPreferences.isDeliveryReportsEnabled,
      systemCopy = ExternalMessagingPreferences.isWriteToSystemProviderEnabled,
      preferSignal = ExternalMessagingPreferences.isPreferSignalWhenAvailable,
      importState = ExternalMessagingPreferences.importState,
      importedCount = ExternalMessagingPreferences.importedMessageCount
    )
  }

  /** Copies the texts already on the phone into Signal SMS. Safe to run again: it only picks up what's new. */
  private fun startImport() {
    if (!SystemMessageImportJob.canRun(this)) {
      permissionLauncher.launch(REQUIRED_PERMISSIONS)
      return
    }

    ExternalMessagingPreferences.importState = ExternalMessagingPreferences.ImportState.RUNNING
    AppDependencies.jobManager.add(SystemMessageImportJob())
    Toast.makeText(this, R.string.ExternalMessagingSettings__import_started, Toast.LENGTH_LONG).show()
    refresh()
  }

  /** The first time SMS/MMS is set up, bring the phone's existing texts along. */
  private fun importOnceIfNeeded() {
    if (ExternalMessagingPreferences.isEnabled && ExternalMessagingPreferences.importState == ExternalMessagingPreferences.ImportState.NEVER && SystemMessageImportJob.canRun(this)) {
      startImport()
    }
  }

  private fun onEnabledChanged(enabled: Boolean) {
    if (!enabled) {
      ExternalMessagingPreferences.isEnabled = false
      refresh()
      return
    }

    if (REQUIRED_PERMISSIONS.all { hasPermission(it) }) {
      ExternalMessagingPreferences.isEnabled = true
      if (!DefaultSmsApp.isDefault(this)) {
        requestDefaultRole()
      }
      refresh()
    } else {
      permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }
  }

  private fun onDefaultAppClicked() {
    if (DefaultSmsApp.isDefault(this)) {
      startActivity(DefaultSmsApp.createChangeDefaultIntent())
    } else {
      requestDefaultRole()
    }
  }

  private fun requestDefaultRole() {
    val intent = DefaultSmsApp.createRequestIntent(this) ?: return
    roleLauncher.launch(intent)
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
  }

  data class SettingsState(
    val enabled: Boolean = false,
    val isDefaultApp: Boolean = false,
    val hasTelephony: Boolean = true,
    val deliveryReports: Boolean = false,
    val systemCopy: Boolean = true,
    val preferSignal: Boolean = true,
    val importState: ExternalMessagingPreferences.ImportState = ExternalMessagingPreferences.ImportState.NEVER,
    val importedCount: Int = 0
  )
}

@Composable
private fun SettingsScreen(
  state: ExternalMessagingSettingsActivity.SettingsState,
  onBack: () -> Unit,
  onEnabledChanged: (Boolean) -> Unit,
  onDefaultAppClicked: () -> Unit,
  onDeliveryReportsChanged: (Boolean) -> Unit,
  onSystemCopyChanged: (Boolean) -> Unit,
  onPreferSignalChanged: (Boolean) -> Unit,
  onImportClicked: () -> Unit
) {
  Scaffolds.Settings(
    title = stringResource(R.string.ExternalMessagingSettings__title),
    onNavigationClick = onBack,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(modifier = Modifier.padding(paddingValues)) {
      item {
        Rows.ToggleRow(
          checked = state.enabled,
          text = stringResource(R.string.ExternalMessagingSettings__use_sms_and_mms),
          label = stringResource(R.string.ExternalMessagingSettings__use_sms_and_mms_summary),
          enabled = state.hasTelephony,
          onCheckChanged = onEnabledChanged
        )
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.ExternalMessagingSettings__make_default),
          label = stringResource(if (state.isDefaultApp) R.string.ExternalMessagingSettings__is_default else R.string.ExternalMessagingSettings__not_default),
          enabled = state.hasTelephony,
          onClick = onDefaultAppClicked
        )
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.ExternalMessagingSettings__import),
          label = when (state.importState) {
            ExternalMessagingPreferences.ImportState.NEVER -> stringResource(R.string.ExternalMessagingSettings__import_summary)
            ExternalMessagingPreferences.ImportState.RUNNING -> stringResource(R.string.ExternalMessagingSettings__import_running)
            ExternalMessagingPreferences.ImportState.DONE -> stringResource(R.string.ExternalMessagingSettings__import_done, state.importedCount)
          },
          enabled = state.enabled && state.importState != ExternalMessagingPreferences.ImportState.RUNNING,
          onClick = onImportClicked
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Rows.ToggleRow(
          checked = state.preferSignal,
          text = stringResource(R.string.ExternalMessagingSettings__prefer_signal),
          label = stringResource(R.string.ExternalMessagingSettings__prefer_signal_summary),
          enabled = state.enabled,
          onCheckChanged = onPreferSignalChanged
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.deliveryReports,
          text = stringResource(R.string.ExternalMessagingSettings__delivery_reports),
          label = stringResource(R.string.ExternalMessagingSettings__delivery_reports_summary),
          enabled = state.enabled,
          onCheckChanged = onDeliveryReportsChanged
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.systemCopy,
          text = stringResource(R.string.ExternalMessagingSettings__keep_system_copy),
          label = stringResource(R.string.ExternalMessagingSettings__keep_system_copy_summary),
          enabled = state.enabled,
          onCheckChanged = onSystemCopyChanged
        )
      }
    }
  }
}

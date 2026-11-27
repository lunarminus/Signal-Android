/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.chats.ChatsSettingsEvents
import org.signal.appsettings.chats.ChatsSettingsScreen
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.thoughtcrime.securesms.external.ui.ExternalMessagingSettingsActivity

/**
 * Hosts [ChatsSettingsScreen]. The view model navigates by sending events to [parentEventEmitter], so this works from
 * whichever host owns the app settings back stack.
 */
@Composable
fun ChatsSettingsEntry(
  parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) {
  val viewModel: ChatsSettingsViewModel = viewModel { ChatsSettingsViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  val context = LocalContext.current

  ChatsSettingsScreen(
    state = state,
    onEvent = { event ->
      if (event == ChatsSettingsEvents.SmsAndMmsClicked) {
        context.startActivity(ExternalMessagingSettingsActivity.createIntent(context))
      } else {
        viewModel.onEvent(event)
      }
    }
  )
}

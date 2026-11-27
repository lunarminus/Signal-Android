/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.ui.creategroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.signal.remoteconfig.SelectionLimits
import org.thoughtcrime.securesms.contacts.SelectedContact
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.external.ExternalMessagingPreferences
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupUiState.NavTarget
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupUiState.UserMessage
import org.thoughtcrime.securesms.recipients.PhoneNumber
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientRepository
import org.thoughtcrime.securesms.recipients.ui.RecipientSelection

class CreateGroupViewModel : ViewModel() {
  companion object {
    private val TAG = Log.tag(CreateGroupViewModel::class)
  }

  private val internalUiState = MutableStateFlow(CreateGroupUiState())
  val uiState: StateFlow<CreateGroupUiState> = internalUiState.asStateFlow()

  fun onSearchQueryChanged(query: String) {
    internalUiState.update { it.copy(searchQuery = query) }
  }

  suspend fun shouldAllowSelection(selection: RecipientSelection): Boolean = when (selection) {
    is RecipientSelection.HasId -> true
    is RecipientSelection.HasPhone -> recipientExists(selection.phone)
  }

  private suspend fun recipientExists(phone: PhoneNumber): Boolean {
    internalUiState.update { it.copy(isLookingUpRecipient = true) }

    return when (val lookupResult = RecipientRepository.lookup(phone)) {
      is RecipientRepository.PhoneLookupResult.Found -> {
        internalUiState.update { it.copy(isLookingUpRecipient = false) }
        true
      }

      // Fork: someone who isn't on Signal can still join a group text (MMS).
      is RecipientRepository.PhoneLookupResult.NotFound if ExternalMessagingPreferences.isEnabled -> {
        internalUiState.update { it.copy(isLookingUpRecipient = false) }
        true
      }

      is RecipientRepository.LookupResult.Failure -> {
        internalUiState.update {
          it.copy(
            isLookingUpRecipient = false,
            userMessage = UserMessage.RecipientLookupFailed(failure = lookupResult)
          )
        }
        false
      }
    }
  }

  fun onSelectionChanged(newSelections: List<SelectedContact>) {
    internalUiState.update {
      it.copy(
        searchQuery = "",
        newSelections = newSelections
      )
    }
  }

  fun selectRecipient(id: RecipientId) {
    internalUiState.update {
      it.copy(pendingRecipientSelections = it.pendingRecipientSelections + id)
    }
  }

  fun clearPendingRecipientSelections() {
    internalUiState.update {
      it.copy(pendingRecipientSelections = emptySet())
    }
  }

  fun continueToGroupDetails() {
    viewModelScope.launch {
      internalUiState.update { it.copy(isLookingUpRecipient = true) }

      val selectedRecipientIds = uiState.value.newSelections.map { it.orCreateRecipientId }
      when (val lookupResult = RecipientRepository.lookup(recipientIds = selectedRecipientIds)) {
        is RecipientRepository.IdLookupResult.FoundAll -> {
          internalUiState.update {
            it.copy(
              isLookingUpRecipient = false,
              pendingDestination = NavTarget.AddGroupDetails(recipientIds = selectedRecipientIds)
            )
          }
        }

        // Fork: when some people aren't on Signal, make a group text (MMS) instead of a Signal group.
        is RecipientRepository.IdLookupResult.FoundSome if ExternalMessagingPreferences.isEnabled -> {
          val groupRecipientId = withContext(Dispatchers.IO) {
            val groupId = SignalDatabase.groups.getOrCreateMmsGroupForMembers(selectedRecipientIds + Recipient.self().id)
            SignalDatabase.recipients.getOrInsertFromGroupId(groupId)
          }
          internalUiState.update {
            it.copy(
              isLookingUpRecipient = false,
              pendingDestination = NavTarget.OpenConversation(recipientId = groupRecipientId)
            )
          }
        }

        is RecipientRepository.LookupResult.Failure -> {
          internalUiState.update {
            it.copy(
              isLookingUpRecipient = false,
              userMessage = UserMessage.RecipientLookupFailed(failure = lookupResult)
            )
          }
        }
      }
    }
  }

  fun clearUserMessage() {
    internalUiState.update { it.copy(userMessage = null) }
  }

  fun clearPendingDestination() {
    internalUiState.update { it.copy(pendingDestination = null) }
  }
}

data class CreateGroupUiState(
  val searchQuery: String = "",
  val selectionLimits: SelectionLimits = RemoteConfig.groupLimits.excludingSelf(),
  val newSelections: List<SelectedContact> = emptyList(),
  val isLookingUpRecipient: Boolean = false,
  val pendingRecipientSelections: Set<RecipientId> = emptySet(),
  val pendingDestination: NavTarget? = null,
  val userMessage: UserMessage? = null
) {
  sealed interface UserMessage {
    data class RecipientLookupFailed(val failure: RecipientRepository.LookupResult.Failure) : UserMessage
  }

  sealed interface NavTarget {
    data class AddGroupDetails(val recipientIds: List<RecipientId>) : NavTarget
    data class OpenConversation(val recipientId: RecipientId) : NavTarget
  }
}

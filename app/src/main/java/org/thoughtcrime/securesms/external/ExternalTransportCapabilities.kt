/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external

/**
 * What an [ExternalTransport] can carry. The conversation UI uses this to hide Signal-only features
 * (reactions, edits, remote delete, ...) for messages that went over a transport without them.
 */
data class ExternalTransportCapabilities(
  val supportsText: Boolean = true,
  val supportsAttachments: Boolean = false,
  val supportsGroupThreads: Boolean = false,
  val supportsDeliveryReports: Boolean = false,
  val supportsReadReceipts: Boolean = false,
  val supportsTypingIndicators: Boolean = false,
  val supportsReactions: Boolean = false,
  val supportsEdits: Boolean = false,
  val supportsRemoteDelete: Boolean = false,
  val supportsSubject: Boolean = false,
  /** Largest total attachment payload in bytes, or null if attachments are unsupported or unbounded. */
  val maxAttachmentBytes: Long? = null
)

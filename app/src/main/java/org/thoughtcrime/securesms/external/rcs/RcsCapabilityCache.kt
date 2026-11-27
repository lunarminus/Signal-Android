/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs

import org.thoughtcrime.securesms.external.rcs.sip.RcsContactCapabilities
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of per-contact RCS reachability with a short expiry.
 *
 * RCS reachability is not a synchronous property of a phone number: it comes from a SIP OPTIONS /
 * capability exchange that is answered over the network. The transport's `canReach`/`canSend` must
 * stay fast and non-blocking, so they read this cache; a background job refreshes it. An unknown or
 * expired entry means "don't use RCS", which makes the framework fall back to MMS/SMS.
 */
object RcsCapabilityCache {

  private const val TTL_MILLIS = 60 * 60 * 1000L // one hour, per common RCS capability caching

  private data class Entry(val capabilities: RcsContactCapabilities?, val expiresAt: Long)

  private val cache = ConcurrentHashMap<String, Entry>()

  /** Known RCS capabilities for [msisdn], or null if unknown/expired (treat as not reachable). */
  fun get(msisdn: String): RcsContactCapabilities? {
    val entry = cache[msisdn] ?: return null
    if (System.currentTimeMillis() > entry.expiresAt) {
      cache.remove(msisdn)
      return null
    }
    return entry.capabilities
  }

  /** True if we have a fresh, positive reachability answer for [msisdn]. */
  fun isReachable(msisdn: String): Boolean = get(msisdn)?.rcsChat == true

  fun put(msisdn: String, capabilities: RcsContactCapabilities?) {
    cache[msisdn] = Entry(capabilities, System.currentTimeMillis() + TTL_MILLIS)
  }

  /** True if we have any cached answer (positive or negative) that is still fresh. */
  fun isFresh(msisdn: String): Boolean {
    val entry = cache[msisdn] ?: return false
    return System.currentTimeMillis() <= entry.expiresAt
  }

  fun clear() = cache.clear()
}

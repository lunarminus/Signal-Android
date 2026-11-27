/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.rcs.RcsManager
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob

/**
 * Refreshes the cached RCS reachability for one contact (SIP OPTIONS / capability exchange), off the
 * main thread, so [org.thoughtcrime.securesms.external.rcs.RcsTransport.canReach] can stay fast and
 * synchronous. Enqueue this when a conversation with a phone-number contact is opened.
 */
class RcsCapabilityRefreshJob private constructor(
  parameters: Parameters,
  private val msisdn: String
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalRcsCapabilityRefreshJob"
    private val TAG = Log.tag(RcsCapabilityRefreshJob::class.java)
    private const val KEY_MSISDN = "msisdn"
  }

  constructor(msisdn: String) : this(
    Parameters.Builder()
      .setQueue("ExternalRcsCapabilities")
      .setMaxAttempts(1)
      .addConstraint(org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint.KEY)
      .build(),
    msisdn
  )

  override fun serialize(): ByteArray? =
    JsonJobData.Builder().putString(KEY_MSISDN, msisdn).serialize()

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    RcsManager.refreshCapabilities(msisdn)
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() = Unit

  class Factory : Job.Factory<RcsCapabilityRefreshJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): RcsCapabilityRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      return RcsCapabilityRefreshJob(parameters, data.getString(KEY_MSISDN))
    }
  }
}

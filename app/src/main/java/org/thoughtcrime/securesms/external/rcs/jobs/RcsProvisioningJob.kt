/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.jobs

import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.rcs.RcsManager
import org.thoughtcrime.securesms.external.rcs.RcsPreferences
import org.thoughtcrime.securesms.external.rcs.provisioning.RcsProvisioningClient
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.BaseJob

/**
 * Runs the RCS HTTP autoconfiguration flow (see [RcsProvisioningClient]): fetches a DroidGuard
 * token, requests the client configuration from the carrier/Jibe server, stores it, and triggers a
 * SIP registration. If the server demands an OTP, it records that so the settings UI can collect the
 * code and re-run this job with [otp] set.
 */
class RcsProvisioningJob private constructor(
  parameters: Parameters,
  private val otp: String?
) : BaseJob(parameters) {

  companion object {
    const val KEY = "ExternalRcsProvisioningJob"
    private val TAG = Log.tag(RcsProvisioningJob::class.java)
    private const val KEY_OTP = "otp"

    const val PREF_OTP_PENDING = "rcs_otp_pending"
  }

  constructor(otp: String? = null) : this(
    Parameters.Builder()
      .setQueue("ExternalRcsProvisioning")
      .setMaxAttempts(3)
      .addConstraint(org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint.KEY)
      .build(),
    otp
  )

  override fun serialize(): ByteArray? =
    JsonJobData.Builder().putString(KEY_OTP, otp).serialize()

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    if (!RcsPreferences.isEnabled) {
      Log.i(TAG, "RCS disabled; skipping provisioning.")
      return
    }

    val result = runBlocking { RcsProvisioningClient(context).requestConfiguration(otp) }
    when (result) {
      is RcsProvisioningClient.Result.Provisioned -> {
        Log.i(TAG, "RCS provisioned (config version ${result.config.version}).")
        RcsPreferences.configXml = result.rawXml
        RcsPreferences.configVersion = result.config.version
        RcsPreferences.userMsisdn = result.config.userMsisdn
        RcsPreferences.recordConfigFetched(result.config.validitySeconds)
        RcsPreferences.lastRegistrationFailure = null
        RcsManager.register()
      }
      is RcsProvisioningClient.Result.OtpRequired -> {
        Log.i(TAG, "RCS provisioning needs an OTP (sent to ${result.sentToMsisdn ?: "device"}).")
        RcsPreferences.lastRegistrationFailure = "otp_pending"
      }
      is RcsProvisioningClient.Result.Failed -> {
        Log.w(TAG, "RCS provisioning failed: ${result.reason}")
        RcsPreferences.lastRegistrationFailure = result.reason
      }
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    Log.w(TAG, "RCS provisioning job failed.")
  }

  class Factory : Job.Factory<RcsProvisioningJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): RcsProvisioningJob {
      val data = JsonJobData.deserialize(serializedData)
      return RcsProvisioningJob(parameters, data.getStringOrDefault(KEY_OTP, null))
    }
  }
}

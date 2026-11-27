/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.rcs.provisioning

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.external.rcs.attestation.DroidGuardClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Drives the GSMA RCS HTTP configuration ("autoconfig") flow against the carrier/Jibe configuration
 * server, as specified in RCC.14 §2.6:
 *
 *  1. GET the autoconfiguration URL with the device/client parameters and a fresh Google DroidGuard
 *     attestation token (see [DroidGuardClient]). Jibe rejects the request without a valid token.
 *  2. If the server answers HTTP 511 with an OTP request, it sends a one-time code to the device by
 *     SMS. The caller supplies that code and we repeat the GET with the OTP, completing it.
 *  3. On HTTP 200 the body is the client-configuration XML, parsed by [RcsConfigParser].
 *
 * This is the step GrapheneOS reported being blocked on ("an unclear reg error response from Jibe").
 * The HTTP shape here follows the public RCC.14 spec; the exact Jibe host, required header set and
 * the DroidGuard "flow"/challenge binding are what still need to be confirmed against live traffic
 * on a device that can produce a token. See docs/rcs/RCS.md.
 */
class RcsProvisioningClient(private val context: Context) {

  companion object {
    private val TAG = Log.tag(RcsProvisioningClient::class.java)

    /**
     * Jibe's generic autoconfiguration host. Most carriers that outsource RCS to Google provision
     * through `config.rcs.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org`, which resolves to Jibe. We build
     * the per-operator host from the SIM's MCC/MNC when available, else fall back to this.
     */
    private const val JIBE_FALLBACK_HOST = "config.messaging.google.com"

    private const val RCS_VERSION = "UP3.0"
    private const val RCS_PROFILE = "UP_2.4" // Universal Profile the client implements
    private const val CLIENT_VENDOR = "SGNL"
    private const val CLIENT_VERSION = "RCSAndrd-1.0"

    private const val HTTP_OTP_REQUIRED = 511
  }

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()
  }

  sealed interface Result {
    /** Provisioning completed; the parsed configuration is ready. */
    data class Provisioned(val config: RcsConfig, val rawXml: String) : Result

    /** The server sent an OTP by SMS; call [requestConfiguration] again with [otp] set. */
    data class OtpRequired(val sentToMsisdn: String?) : Result

    /** Terminal failure (no attestation, wrong device, server refusal). */
    data class Failed(val reason: String) : Result
  }

  /**
   * Performs one autoconfig request. Pass [otp] = null for the initial request; if the result is
   * [Result.OtpRequired], call again with the code the user received by SMS.
   */
  suspend fun requestConfiguration(otp: String? = null): Result {
    val telephony = context.getSystemService(TelephonyManager::class.java)
      ?: return Result.Failed("No telephony service")

    val imsi = runCatching { telephony.subscriberId }.getOrNull()
    val msisdn = runCatching { telephony.line1Number }.getOrNull()
    val mccMnc = telephony.simOperator?.takeIf { it.length >= 5 }

    val token = try {
      val challenge = buildChallenge(imsi, mccMnc)
      DroidGuardClient.requestToken(context, flow = "provisioning", challenge = challenge, nonce = challenge)
    } catch (e: DroidGuardClient.DroidGuardException) {
      Log.w(TAG, "Could not obtain DroidGuard token", e)
      return Result.Failed("Google attestation unavailable: ${e.message}")
    }

    val url = buildAutoconfigUrl(host = autoconfigHost(mccMnc), imsi = imsi, msisdn = msisdn, token = token, otp = otp)

    val request = Request.Builder()
      .url(url)
      .header("User-Agent", "$CLIENT_VENDOR $CLIENT_VERSION ${Build.MANUFACTURER} ${Build.MODEL}")
      .get()
      .build()

    return try {
      httpClient.newCall(request).execute().use { response ->
        when (response.code) {
          200 -> {
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
              Result.Failed("Empty configuration body")
            } else {
              try {
                Result.Provisioned(RcsConfigParser.parse(body), body)
              } catch (e: RcsConfigParser.InvalidConfigException) {
                Log.w(TAG, "Could not parse configuration", e)
                Result.Failed("Invalid configuration: ${e.message}")
              }
            }
          }
          HTTP_OTP_REQUIRED -> Result.OtpRequired(msisdn)
          403 -> Result.Failed("Server refused the client (403): attestation or client not allowlisted")
          else -> Result.Failed("Autoconfig HTTP ${response.code}")
        }
      }
    } catch (e: IOException) {
      Log.w(TAG, "Autoconfig request failed", e)
      Result.Failed("Network error: ${e.message}")
    }
  }

  private fun autoconfigHost(mccMnc: String?): String {
    if (mccMnc == null || mccMnc.length < 5) return JIBE_FALLBACK_HOST
    val mcc = mccMnc.substring(0, 3)
    val mnc = mccMnc.substring(3)
    return "config.rcs.mnc%03d.mcc%s.pub.3gppnetwork.org".format(mnc.toIntOrNull() ?: 0, mcc)
  }

  private fun buildChallenge(imsi: String?, mccMnc: String?): String =
    "${imsi ?: "none"}:${mccMnc ?: "none"}:${System.currentTimeMillis()}"

  private fun buildAutoconfigUrl(host: String, imsi: String?, msisdn: String?, token: String, otp: String?): HttpUrl {
    val builder = "https://$host/rcs/".toHttpUrl().newBuilder()
      .addQueryParameter("vers", org.thoughtcrime.securesms.external.rcs.RcsPreferences.configVersion)
      .addQueryParameter("rcs_version", RCS_VERSION)
      .addQueryParameter("rcs_profile", RCS_PROFILE)
      .addQueryParameter("client_vendor", CLIENT_VENDOR)
      .addQueryParameter("client_version", CLIENT_VERSION)
      .addQueryParameter("terminal_vendor", Build.MANUFACTURER)
      .addQueryParameter("terminal_model", Build.MODEL)
      .addQueryParameter("terminal_sw_version", Build.VERSION.RELEASE)
      .addQueryParameter("provisioning_version", "0")
      .addQueryParameter("token", token)

    imsi?.let { builder.addQueryParameter("IMSI", it) }
    msisdn?.let { builder.addQueryParameter("msisdn", it) }
    otp?.let { builder.addQueryParameter("OTP", it) }
    return builder.build()
  }
}

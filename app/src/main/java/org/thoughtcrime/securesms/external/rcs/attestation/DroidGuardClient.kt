/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * The Binder/SafeParcel wire format and DroidGuard flow in this file are adapted from the CC0
 * proof-of-concept posted to GrapheneOS/Messaging issue #456 ("Google Jibe RCS research"). That
 * snippet is dedicated to the public domain (CC0), so it is reproduced here.
 */

package org.thoughtcrime.securesms.external.rcs.attestation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import org.signal.core.util.logging.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Obtains a Google DroidGuard attestation token from the device's Google Play services.
 *
 * Google Jibe (the backend behind carrier RCS for every major US carrier) refuses to provision an
 * RCS identity unless the provisioning request carries a fresh DroidGuard token. DroidGuard is an
 * internal Play-services API with no public surface: a normal app cannot call it, because the only
 * entry point is the GMS "service broker" binder, which authenticates the *caller's* package name
 * and signature.
 *
 * This client talks to that broker directly over Binder, presenting the package identity of Google
 * Messages ("com.google.android.apps.messaging"). On a stock device that identity check fails. It
 * only succeeds where the OS lets this app present another package's identity to GMS, which on
 * GrapheneOS means either:
 *   - the user has enabled sandboxed Google Play **and** a signature-spoofing allowance for this app
 *     (GrapheneOS's own RCS plan spoofs the signature of one of its system apps), or
 *   - microG with its DroidGuard compatibility layer is installed and allowlisted.
 *
 * See [org.thoughtcrime.securesms.external.rcs.provisioning.RcsProvisioningClient] for how the token
 * is used, and `docs/rcs/RCS.md` for the device prerequisites and the legal/feasibility caveats.
 */
object DroidGuardClient {

  private val TAG = Log.tag(DroidGuardClient::class.java)

  /** The package identity we present to GMS. Jibe only issues RCS tokens to the Messages identity. */
  private const val SPOOFED_PACKAGE = "com.google.android.apps.messaging"

  private const val GMS_PACKAGE = "com.google.android.gms"
  private const val DROIDGUARD_ACTION = "com.google.android.gms.droidguard.service.START"

  private const val BROKER_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsServiceBroker"
  private const val CALLBACK_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsCallbacks"

  /** IGmsServiceBroker transaction id for getService(). */
  private const val TXN_GET_SERVICE = 46

  /** IGmsCallbacks transaction id for onPostInitComplete(). */
  private const val GMS_CODE_ON_POST_INIT_COMPLETE = 1

  /** Play-services service id for DroidGuard. */
  private const val SERVICE_ID_DROIDGUARD = 25

  /** The Play-services client version the Messages identity reports. */
  private const val CLIENT_VERSION = 12451000

  private const val GET_SERVICE_REQUEST_VERSION = 6
  private const val SAFE_PARCEL_MAGIC = 0x4f45

  private const val BIND_TIMEOUT_MS = 10_000L

  /** Raised when GMS is missing, refuses the caller, or returns a non-zero init status. */
  class DroidGuardException(message: String, cause: Throwable? = null) : Exception(message, cause)

  /**
   * Fetches a DroidGuard token for the given [flow] ("provisioning" for RCS registration), binding
   * the challenge/nonce the autoconfiguration server handed us into the token.
   *
   * @throws DroidGuardException if Play services is unavailable or rejects the request.
   */
  @Throws(DroidGuardException::class)
  suspend fun requestToken(context: Context, flow: String, challenge: String, nonce: String): String {
    val realService = bindDroidGuardService(context)
    try {
      val handle = realService.handle()
      try {
        handle.init(flow)
        val tokenBytes = handle.snapshot(
          mapOf(
            "challenge" to challenge,
            "nonce" to nonce,
            "provisioning_engine_provisioning_session_id_key" to UUID.randomUUID().toString()
          )
        )
        return Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
      } finally {
        handle.close()
      }
    } finally {
      realService.release()
    }
  }

  /**
   * Binds to GMS, drives the service-broker handshake presenting the Messages identity, and returns
   * the real DroidGuard service binder.
   */
  private suspend fun bindDroidGuardService(context: Context): DroidGuardService = suspendCancellableCoroutine { continuation ->
    val intent = Intent(DROIDGUARD_ACTION).apply { setPackage(GMS_PACKAGE) }

    val connection = object : ServiceConnection {
      private var resumed = false

      override fun onServiceConnected(name: ComponentName?, brokerBinder: IBinder?) {
        if (resumed) return
        if (brokerBinder == null) {
          resumed = true
          continuation.resumeWithException(DroidGuardException("Null GMS broker binder"))
          return
        }
        try {
          val serviceBinder = requestServiceBinder(brokerBinder)
          resumed = true
          continuation.resume(DroidGuardService(context, this, serviceBinder))
        } catch (e: Exception) {
          resumed = true
          runCatching { context.unbindService(this) }
          continuation.resumeWithException(e)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        if (!resumed) {
          resumed = true
          continuation.resumeWithException(DroidGuardException("GMS disconnected before init"))
        }
      }
    }

    val bound = try {
      context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    } catch (e: SecurityException) {
      continuation.resumeWithException(DroidGuardException("Not allowed to bind GMS", e))
      return@suspendCancellableCoroutine
    }

    if (!bound) {
      continuation.resumeWithException(DroidGuardException("Google Play services not installed"))
      return@suspendCancellableCoroutine
    }

    continuation.invokeOnCancellation { runCatching { context.unbindService(connection) } }
  }

  /**
   * Performs the IGmsServiceBroker.getService() transaction for DROID_GUARD and blocks until the
   * onPostInitComplete() callback hands back the real service binder.
   */
  private fun requestServiceBinder(brokerBinder: IBinder): IBinder {
    val latch = CountDownLatch(1)
    val result = arrayOfNulls<IBinder>(1)
    val status = intArrayOf(-1)

    val callback = object : android.os.Binder() {
      override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == GMS_CODE_ON_POST_INIT_COMPLETE) {
          data.enforceInterface(CALLBACK_DESCRIPTOR)
          status[0] = data.readInt()
          result[0] = data.readStrongBinder()
          latch.countDown()
          return true
        }
        return super.onTransact(code, data, reply, flags)
      }
    }

    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
      data.writeInterfaceToken(BROKER_DESCRIPTOR)
      data.writeStrongBinder(callback)
      data.writeInt(1) // GetServiceRequest is present
      writeGetServiceRequest(data)
      brokerBinder.transact(TXN_GET_SERVICE, data, reply, 0)
      reply.readException()
    } finally {
      data.recycle()
      reply.recycle()
    }

    if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      throw DroidGuardException("GMS init timed out")
    }
    val binder = result[0]
    if (status[0] != 0 || binder == null) {
      throw DroidGuardException("GMS init failed: status=${status[0]}")
    }
    return binder
  }

  /**
   * Writes a GetServiceRequest SafeParcel: version, DROID_GUARD service id, client version and the
   * spoofed package name, wrapped in the SafeParcel object header.
   */
  private fun writeGetServiceRequest(parcel: Parcel) {
    val start = parcel.dataPosition()
    parcel.writeInt(0) // placeholder for the object header, patched below

    writeSafeParcelInt(parcel, field = 1, value = GET_SERVICE_REQUEST_VERSION)
    writeSafeParcelInt(parcel, field = 2, value = SERVICE_ID_DROIDGUARD)
    writeSafeParcelInt(parcel, field = 3, value = CLIENT_VERSION)
    writeSafeParcelString(parcel, field = 4, value = SPOOFED_PACKAGE)

    val end = parcel.dataPosition()
    val length = end - start - 4
    parcel.setDataPosition(start)
    parcel.writeInt((length shl 16) or SAFE_PARCEL_MAGIC)
    parcel.setDataPosition(end)
  }

  private fun writeSafeParcelInt(parcel: Parcel, field: Int, value: Int) {
    parcel.writeInt((4 shl 16) or field)
    parcel.writeInt(value)
  }

  private fun writeSafeParcelString(parcel: Parcel, field: Int, value: String) {
    val headerPos = parcel.dataPosition()
    parcel.writeInt((0xffff shl 16) or field) // length filled in by writeString's framing
    val start = parcel.dataPosition()
    parcel.writeString(value)
    val end = parcel.dataPosition()
    parcel.setDataPosition(headerPos)
    parcel.writeInt(((end - start) shl 16) or field)
    parcel.setDataPosition(end)
  }

  /**
   * Thin wrapper over the real DroidGuard service binder that exposes the two calls we need
   * (open a handle, then init + snapshot) without pulling in the GMS client AIDL stubs.
   */
  private class DroidGuardService(
    private val context: Context,
    private val connection: ServiceConnection,
    private val binder: IBinder
  ) {
    private val descriptor = "com.google.android.gms.droidguard.internal.IDroidGuardService"
    private val handleDescriptor = "com.google.android.gms.droidguard.internal.IDroidGuardHandle"

    fun handle(): DroidGuardHandle {
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(descriptor)
        binder.transact(1, data, reply, 0)
        reply.readException()
        val handleBinder = reply.readStrongBinder() ?: throw DroidGuardException("Null DroidGuard handle")
        return DroidGuardHandle(handleBinder, handleDescriptor)
      } finally {
        data.recycle()
        reply.recycle()
      }
    }

    fun release() {
      runCatching { context.unbindService(connection) }
    }
  }

  private class DroidGuardHandle(private val binder: IBinder, private val descriptor: String) {
    fun init(flow: String) {
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(descriptor)
        data.writeString(flow)
        binder.transact(1, data, reply, 0)
        reply.readException()
      } finally {
        data.recycle()
        reply.recycle()
      }
    }

    fun snapshot(params: Map<String, String>): ByteArray {
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(descriptor)
        data.writeInt(params.size)
        params.forEach { (k, v) ->
          data.writeString(k)
          data.writeString(v)
        }
        binder.transact(2, data, reply, 0)
        reply.readException()
        return reply.createByteArray() ?: throw DroidGuardException("Empty DroidGuard snapshot")
      } finally {
        data.recycle()
        reply.recycle()
      }
    }

    fun close() {
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(descriptor)
        binder.transact(3, data, reply, 0)
      } catch (e: Exception) {
        Log.w(TAG, "DroidGuard handle close failed", e)
      } finally {
        data.recycle()
        reply.recycle()
      }
    }
  }
}

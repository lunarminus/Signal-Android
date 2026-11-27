/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.thoughtcrime.securesms.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands raw MMS PDUs to and from the platform MMS service. The phone process reads outgoing PDUs from
 * here and writes downloaded ones back. Files live in the app's private cache and are deleted as soon
 * as they've been processed.
 */
class MmsFileProvider : ContentProvider() {

  companion object {
    private const val AUTHORITY = BuildConfig.APPLICATION_ID + ".mmsfiles"
    private const val DIRECTORY = "external_mms"
    private const val PHONE_PACKAGE = "com.android.phone"

    private fun directory(context: Context): File {
      return File(context.cacheDir, DIRECTORY).apply { mkdirs() }
    }

    /** Creates a new, empty file for a PDU and returns its file and content Uri. */
    @JvmStatic
    fun create(context: Context, name: String): Pair<File, Uri> {
      val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
      val file = File(directory(context), safeName)
      val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(safeName).build()
      return file to uri
    }

    @JvmStatic
    fun fileFor(context: Context, uri: Uri): File? {
      val name = uri.lastPathSegment ?: return null
      return File(directory(context), name).takeIf { it.parentFile == directory(context) }
    }

    /** Lets the phone process read and write [uri]. The platform also does this, but not on every Android version. */
    @JvmStatic
    fun grantToPhone(context: Context, uri: Uri) {
      try {
        context.grantUriPermission(PHONE_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      } catch (e: Exception) {
        // Not fatal: SmsManager grants access itself on current Android versions.
      }
    }
  }

  override fun onCreate(): Boolean = true

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val context = context ?: throw FileNotFoundException()
    val file = fileFor(context, uri) ?: throw FileNotFoundException(uri.toString())
    val fileMode = if (mode.contains('w')) {
      ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
    } else {
      ParcelFileDescriptor.MODE_READ_ONLY
    }
    return ParcelFileDescriptor.open(file, fileMode)
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

  override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

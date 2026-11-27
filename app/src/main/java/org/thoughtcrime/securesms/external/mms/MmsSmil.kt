/*
 * Copyright 2026 Signal SMS fork contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.external.mms

import org.thoughtcrime.securesms.external.mms.pdu.PduBody

/**
 * Builds the minimal SMIL presentation document many carriers and older phones expect in an MMS: one
 * slide per part, image/video in an "Image" region and text in a "Text" region.
 */
object MmsSmil {

  fun build(body: PduBody): ByteArray {
    val slides = StringBuilder()

    for (i in 0 until body.partsNum) {
      val part = body.getPart(i)
      val location = part.contentLocation?.let { String(it, Charsets.UTF_8) } ?: continue
      val type = part.contentType?.let { String(it, Charsets.UTF_8) } ?: continue

      val element = when {
        type.startsWith("image/") -> "<img src=\"${escape(location)}\" region=\"Image\"/>"
        type.startsWith("video/") -> "<video src=\"${escape(location)}\" region=\"Image\"/>"
        type.startsWith("audio/") -> "<audio src=\"${escape(location)}\"/>"
        type.startsWith("text/") -> "<text src=\"${escape(location)}\" region=\"Text\"/>"
        else -> "<ref src=\"${escape(location)}\"/>"
      }

      slides.append("<par dur=\"5000ms\">").append(element).append("</par>")
    }

    val smil = "<smil><head><layout><root-layout/>" +
      "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
      "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
      "</layout></head><body>" + slides + "</body></smil>"

    return smil.toByteArray(Charsets.UTF_8)
  }

  private fun escape(value: String): String {
    return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
  }
}

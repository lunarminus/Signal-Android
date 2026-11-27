"""Generates Switchboard's launcher, splash and logo drawables from one original glyph."""
import os, sys
res = sys.argv[1]

# Glyph in a 108x108 adaptive-icon space: a speech bubble holding two connected nodes (a patch cord).
BUBBLE = "M41,33 H67 A11,11 0 0 1 78,44 V58 A11,11 0 0 1 67,69 H52 L37.5,78.5 L40.5,69 H41 A11,11 0 0 1 30,58 V44 A11,11 0 0 1 41,33 Z"
BUBBLE_INNER = "M41,36.5 H67 A7.5,7.5 0 0 1 74.5,44 V58 A7.5,7.5 0 0 1 67,65.5 H50.5 L43.5,70.5 L45,65.5 H41 A7.5,7.5 0 0 1 33.5,58 V44 A7.5,7.5 0 0 1 41,36.5 Z"
NODES = "M37.5,46 a4.5,4.5 0 1 0 9,0 a4.5,4.5 0 1 0 -9,0 Z M61.5,56 a4.5,4.5 0 1 0 9,0 a4.5,4.5 0 1 0 -9,0 Z"
CORD = "M42,46 C55,46 53,56 66,56"
CX, CY, SPAN = 54.0, 55.5, 48.0

BG_TOP, BG_BOTTOM = "#FF14B08F", "#FF0A6B5C"
ACCENT = "#FF0A6B5C"
WHITE = "#FFFFFFFF"

def glyph(bubble, inner, mono=False, stroke_w=3.6):
    if mono:
        # Outline version, so the details survive single-color (themed) icons.
        return f'''    <path android:fillColor="{bubble}" android:fillType="evenOdd" android:pathData="{BUBBLE} {BUBBLE_INNER}"/>
    <path android:fillColor="{bubble}" android:pathData="{NODES}"/>
    <path android:strokeColor="{bubble}" android:strokeWidth="{stroke_w}" android:strokeLineCap="round" android:pathData="{CORD}"/>
'''
    return f'''    <path android:fillColor="{bubble}" android:pathData="{BUBBLE}"/>
    <path android:fillColor="{inner}" android:pathData="{NODES}"/>
    <path android:strokeColor="{inner}" android:strokeWidth="{stroke_w}" android:strokeLineCap="round" android:pathData="{CORD}"/>
'''

def vector(width_dp, viewport, body, fraction=None):
    head = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Switchboard icon. Generated; original artwork for this fork. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width_dp}dp"
    android:height="{width_dp}dp"
    android:viewportWidth="{viewport}"
    android:viewportHeight="{viewport}">
'''
    if fraction is None:
        return head + body + "</vector>\n"
    s = viewport * fraction / SPAN
    tx, ty = viewport / 2 - CX * s, viewport / 2 - CY * s
    return head + f'  <group android:scaleX="{s:.4f}" android:scaleY="{s:.4f}" android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">\n' + body + "  </group>\n</vector>\n"

def background(top, bottom):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Switchboard icon background. Generated. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
  <path android:pathData="M0,0h108v108h-108z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" android:endY="108">
        <item android:color="{top}" android:offset="0"/>
        <item android:color="{bottom}" android:offset="1"/>
      </gradient>
    </aapt:attr>
  </path>
</vector>
'''

def write(name, text):
    with open(os.path.join(res, "drawable", name + ".xml"), "w") as f:
        f.write(text)

# Main adaptive icon.
write("ic_launcher_background", background(BG_TOP, BG_BOTTOM))
write("ic_launcher_foreground", vector(108, 108, glyph(WHITE, ACCENT)))
write("ic_launcher_monochrome", vector(108, 108, glyph("#FF000000", None, mono=True)))

# The "Signal" alternate app icons become Switchboard variants on their existing backgrounds.
write("ic_launcher_alt_signal_color_foreground", vector(108, 108, glyph(WHITE, ACCENT)))
write("ic_launcher_alt_signal_dark_foreground", vector(108, 108, glyph(WHITE, "#FF020727")))
write("ic_launcher_alt_signal_dark_variant_foreground", vector(108, 108, glyph(WHITE, "#FF15040E")))
write("ic_launcher_alt_signal_white_foreground", vector(108, 108, glyph("#FF0F8C74", WHITE)))

# Splash screens and in-app logos: the colored bubble works on light and dark backgrounds.
for name in ("ic_splash", "ic_splash_light"):
    write(name, vector(128, 296, glyph("#FF0F8C74", WHITE, stroke_w=3.6), fraction=0.42))
write("ic_splash_dark", vector(128, 296, glyph(WHITE, "#FF0A6B5C", stroke_w=3.6), fraction=0.42))
write("ic_signal_logo_large", vector(128, 128, glyph("#FF0F8C74", WHITE), fraction=0.8))
write("ic_signal_logo_small", vector(16, 138, glyph(WHITE, None, mono=True), fraction=0.92))

# An SVG of the full legacy icon, for rendering the PNG launcher icons.
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="18 18 72 72">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#14B08F"/><stop offset="1" stop-color="#0A6B5C"/></linearGradient>
    <clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath>
  </defs>
  <g clip-path="url(#c)">
    <rect x="0" y="0" width="108" height="108" fill="url(#g)"/>
    <path d="{BUBBLE}" fill="#FFFFFF"/>
    <path d="{NODES}" fill="#0A6B5C"/>
    <path d="{CORD}" fill="none" stroke="#0A6B5C" stroke-width="3.6" stroke-linecap="round"/>
  </g>
</svg>
'''
open(sys.argv[2], "w").write(svg)
print("ok")

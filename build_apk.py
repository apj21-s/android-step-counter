#!/usr/bin/env python3
"""Pack the app screen from preview/ into the Android app, then build the APK."""
import os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
PREVIEW = os.path.join(ROOT, "preview")
WWW = os.path.join(ROOT, "app/src/main/assets/www")

def app_block(html):
    """Return the <div class="screen app" id="app"> element with all its children."""
    start = html.index('<div class="screen app" id="app">')
    depth, i = 0, start
    for m in re.finditer(r"<div\b|</div>", html[start:]):
        depth += 1 if m.group(0) == "<div" else -1
        if depth == 0:
            return html[start:start + m.end()]
    raise SystemExit("could not find end of app screen")

html = open(os.path.join(PREVIEW, "index.html"), encoding="utf-8").read()
defs = html[html.index('<svg width="0" height="0"'):]
defs = defs[:defs.index("</svg>") + 6]

shutil.rmtree(WWW, ignore_errors=True)
os.makedirs(WWW)
shutil.copytree(os.path.join(PREVIEW, "fonts"), os.path.join(WWW, "fonts"))
for f in ("style.css", "app.js"):
    shutil.copy(os.path.join(PREVIEW, f), WWW)
open(os.path.join(WWW, "device.css"), "w").write("""
html, body { height: 100%; overflow: hidden; }
body { background: #121212; }
* { -webkit-user-select: none; user-select: none; }
.screen.app { position: fixed; inset: 0; width: auto; height: auto; border-radius: 0; }
.island, .statusbar, .home-ind { display: none !important; }
.views { top: 0; }
.bg-glow { top: 240px; }
.tabbar { bottom: 14px; }
""")
open(os.path.join(WWW, "index.html"), "w", encoding="utf-8").write(
    '<!doctype html><html lang="en"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no">'
    '<link rel="stylesheet" href="fonts/fonts.css"><link rel="stylesheet" href="style.css"><link rel="stylesheet" href="device.css">'
    '</head><body class="device">' + defs + app_block(html) + '<script src="app.js"></script></body></html>')
print("app screen packed into assets/www")

home = os.path.expanduser("~")
env = dict(os.environ, JAVA_HOME=f"{home}/.jdks/jbr-21.0.11-1", ANDROID_HOME=f"{home}/Android/Sdk")
dists = f"{home}/.gradle/wrapper/dists/gradle-8.14.3-all"
gradle = next(os.path.join(dists, d, "gradle-8.14.3/bin/gradle") for d in os.listdir(dists)
              if os.path.exists(os.path.join(dists, d, "gradle-8.14.3/bin/gradle")))
subprocess.run([gradle, "-p", ROOT, "assembleRelease", "--console=plain"] + sys.argv[1:], env=env, check=True)
apk = os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk")
out_dir = f"{home}/Downloads/utilities"
os.makedirs(out_dir, exist_ok=True)
shutil.copy(apk, os.path.join(out_dir, "Stride.apk"))
print("APK:", os.path.join(out_dir, "Stride.apk"))

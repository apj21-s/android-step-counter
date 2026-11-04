# Stride — Android step counter + aesthetic widget

## Context
User wants a step counter app on their phone, with a good-looking home-screen widget, accurate to 95–98%.
Assumption: **Android phone** (Android SDK + an existing app `~/AndroidStudioProjects/HoichoiSplit` are on this machine; iPhone apps can't be built on Linux).

How the accuracy target is met: don't write our own step detection from raw motion data (homemade algorithms land around 85–92%). Use Android's **hardware step counter** (`Sensor.TYPE_STEP_COUNTER`) — the low-power chip the phone maker tunes, the same source Samsung Health / Google Fit use. It's typically 95–99% for normal walking, and it keeps counting while the app is closed or the phone is asleep. An in-app "100-step test" lets the user check accuracy themselves.

## Step 0 — Preview on the laptop first (localhost)
Before building the Android app, make a clickable preview to view in the laptop browser and sign off on the look.
- Folder `~/AndroidStudioProjects/Stride/preview/` with `index.html` + `style.css` + `app.js` (no build tools; fonts bundled locally).
- Served with `python3 -m http.server 5173 --bind 127.0.0.1` → open **http://localhost:5173**.
- Shows a phone frame (~390×844) with:
  - The **Today**, **History** and **Settings** screens, switchable like the real app, with all the motion from the aesthetics plan (rolling numbers, ring spring + glow, goal burst, staggered bars).
  - A fake **home screen** with all four widgets in every style (Obsidian / AMOLED / Frosted / Match wallpaper), with a wallpaper switcher.
  - A **demo control panel** next to the phone: "Walk" button (adds steps live), a step slider, "jump to goal" (triggers the burst), "next day", and a 30-day fake history so the charts look real.
- Each tweak shows up after a page reload. Once the look is approved, the same colors, sizes and timings get copied into the Compose/Glance code so the phone app matches the preview.
- Optional later: also run the real APK in the Android emulator on the laptop (the emulator is already in `~/Android/Sdk/emulator`; it needs a ~1.5 GB system image download).

## Project
New project `~/AndroidStudioProjects/Stride`, package `com.arco.stride`, Kotlin + Jetpack Compose (app UI) + Jetpack Glance (widget). Reuse HoichoiSplit's build setup: AGP 8.13.0, Gradle 8.14.3 (cached in `~/.gradle/wrapper/dists`), JBR 21 at `~/.jdks/jbr-21.0.11-1`, compileSdk/targetSdk 36, minSdk 29 (activity-recognition permission), debug-signed release. A `build_apk.py` modeled on `HoichoiSplit/build_apk.py` builds and copies the APK to `~/Downloads/utilities/Stride.apk`.

## How counting works
- `StepRepository`: reads the cumulative step sensor value (counts since phone boot). Saves a "start of day" baseline in DataStore; today's steps = current − baseline.
- Day rollover: at the first reading after midnight, close out yesterday into history (Room table `day(date, steps)`) and set a new baseline.
- Reboot: sensor resets to 0 → detect value < last value, add the pre-reboot count to today's offset. `BOOT_COMPLETED` receiver re-arms things.
- `StepService` (foreground service, type `health`, quiet low-priority notification "1,234 steps"): listens to the sensor with batching so the widget stays live with little battery cost. Toggle in settings; with it off, a WorkManager job refreshes every 15 min instead.
- Permissions: `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_HEALTH`, `RECEIVE_BOOT_COMPLETED`. Prompt to exempt from battery optimization (some phones kill background apps).
- Fallback: if the phone has no step-counter chip (rare), show a clear message rather than silently guess.

## App screens (obsidian #121212, crimson #DC143C, neon #FF2D55 accents)
1. **Today** — big step count inside a crimson progress ring toward a daily goal (default 8,000), distance (from stride length), calories estimate, active minutes.
2. **History** — 7-day / 30-day bar chart, streaks, best day.
3. **Settings** — goal, height → stride length, live-widget toggle, **100-step test** (tap start, walk exactly 100 steps, tap stop → shows the % accuracy).

## Widgets (Glance)
- **Ring widget (2×2)**: dark glassy card, crimson gradient progress ring, step count center, small "of 8,000" under it.
- **Wide widget (4×1)**: count + slim progress bar + mini 7-day bars.
- Tapping opens the app. Updated by `StepService` on change (throttled to ~once per 30 s) or by the 15-min job.

## Aesthetics plan
Goal: it should feel premium and calm, like a luxury watch face, not a busy fitness app.

**Palette (tokens in `ui/theme/Theme.kt`, shared with widgets)**
- Background obsidian `#121212`, raised cards `#1C1B1D`, hairline borders `#FFFFFF` at 6%.
- Accent crimson `#DC143C` → neon `#FF2D55` gradient (rings, bars, buttons).
- Text: white 92% primary, 55% secondary, 30% hints. Success glow when the goal is hit: warm `#FF6B81`.
- Pure-black AMOLED variant (`#000000` background) as an option to save battery.

**Typography**
- Big numbers: **Space Grotesk** (bundled font), tabular figures so digits don't jump while counting.
- Labels: **Inter**, small caps-style tracking (+8%) for "STEPS", "KM", "KCAL".
- Hierarchy: step count 72sp, stats 20sp, labels 11sp.

**Motion**
- Step count rolls up digit by digit (odometer style) when it changes.
- Progress ring fills with a spring and keeps a soft pulsing glow at its tip.
- Goal reached: one-time crimson particle burst + gentle haptic tick; the ring turns into a full glowing circle for that day.
- Screen changes: shared-element slide between Today and History; history bars grow in one after another (staggered, 30 ms each).
- Respects the phone's "remove animations" setting.

**Today screen layout**
- Top: date in small caps + streak flame chip ("🔥 5 days").
- Center: large ring with step count; under it "of 8,000" and % done.
- Bottom: three glass cards (distance, calories, active minutes) with thin crimson icons.
- Background: very faint radial crimson glow behind the ring that grows brighter as you get closer to the goal.

**History screen**
- Rounded bars with the gradient; today's bar glows; days that hit the goal get a small dot on top.
- Tap a bar → tooltip card with that day's details. Monthly heatmap calendar (darker → brighter crimson) like a contributions grid.

**Widgets (several styles, picked in widget settings)**
- *Ring* (2×2): glass card, gradient ring, count in Space Grotesk, tiny streak flame.
- *Wide* (4×1): count + slim glowing progress bar + 7 mini day bars.
- *Minimal* (2×1): just the number and a thin underline that fills; transparent background for clean home screens.
- *Big* (4×2): ring + distance/calories + week bars.
- Each widget has a style choice: Obsidian (default), AMOLED black, Frosted (translucent), and "Match wallpaper" (Material You colors).
- Rounded corners follow the phone's widget corner radius; the layout adapts to the widget's size.
- Technical note: home-screen widgets can't animate, so the ring and glow are drawn as a sharp image (bitmap) each update so they still look smooth and high-end.

**Small touches**
- Custom app icon: crimson footprint inside a ring on obsidian, plus an adaptive/themed-icon version.
- Splash screen: the ring draws itself once, then the app opens.
- Persistent notification styled to match: "6,240 steps · 78%" with a small progress bar.
- Light haptic on button presses; milestone vibrations at 25/50/75/100% (can turn off).
- Empty states with friendly text ("Take a walk — your first steps show up here").

## Key files
- `app/src/main/java/com/arco/stride/data/StepRepository.kt`, `db/DayDao.kt`
- `service/StepService.kt`, `service/BootReceiver.kt`, `work/RefreshWorker.kt`
- `ui/TodayScreen.kt`, `ui/HistoryScreen.kt`, `ui/SettingsScreen.kt`, `ui/theme/Theme.kt`
- `widget/RingWidget.kt`, `widget/WideWidget.kt`, `widget/MinimalWidget.kt`, `widget/BigWidget.kt`, `widget/RingRenderer.kt` (draws ring image), `widget/WidgetStyleActivity.kt` (style picker)
- `ui/anim/RollingNumber.kt`, `ui/anim/GoalBurst.kt`, `res/font/` (Space Grotesk, Inter), `res/mipmap-anydpi/ic_launcher.xml`
- `build_apk.py`

## Verification
0. Preview: start the server, open http://localhost:5173, click through every screen, widget style and demo control; check there are no console errors. User approves the look before step 1.
1. Build with `python3 build_apk.py` — must succeed with no errors.
2. Unit tests for the baseline/midnight/reboot math in `StepRepository` (`gradle test`).
3. Install on phone: plug in with USB debugging and run `~/Android/Sdk/platform-tools/adb install -r ~/Downloads/utilities/Stride.apk` (or copy the APK over and tap it).
4. On the phone: grant permissions, add both widgets, run the 100-step test 2–3 times (target 95–100 counted), check the widget changes while walking with the app closed.

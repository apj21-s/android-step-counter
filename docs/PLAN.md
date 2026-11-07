# Stride: plan for best accuracy and lowest battery

Replaces the original build plan for everything about counting. Updated 2026-11-07, version 3.1 (commit 092e205+).
"Works" below means tested; "built, unproven" means it compiles and passes unit tests but hasn't been seen working on a real walk.

## 1. What "best" means (how we'll know)
- **Accuracy:** for each recorded walk, Stride's stored total vs your hand count. Report the signed error per walk and per phone position, plus the worst walk. Target: within ±2% per walk in pocket, hand, bag and typing, and 0 extra steps from a bus or car ride. These are targets, not results yet.
- **Battery:** Stride's own share from Android's battery stats over a normal day (tools/phone/battery.sh, read-only). Target: no wake lock held while idle, and the CPU asleep during walks on phones that support it.

## 2. Status
| Part | Status | Evidence |
|---|---|---|
| Step totals (Google Recording API) | works | counts with Stride closed; survived the overnight kill |
| Motion detector (MotionCounter) | works on research data | 3.8% mean error on 117 Cambridge walks (motion only, no chip) |
| Recording a walk (calibration + lab), screen off | works | tested on the Edge 60 Pro: 45 s screen-off, no gaps; file saved to Download/Stride lab |
| Recovery after a kill mid-recording | works | reinstall mid-recording -> saved as _unfinished.csv |
| Calibration (steady hand, 50+ steps) | built, unproven | code path runs; no real calibration walk yet |
| Chip vs motion by phone position | built, unproven | unit tests only; needs your walks with chip counts |
| Vehicle step removal (±30 s) | built, unproven | unit + storage tests; needs a real bus/car recording |
| Paced low-power walking (no wake lock) | built, unproven | your phone qualifies (wake-up step detector, 3,000-sample accel buffer); not yet seen on a walk |
| Service restart after battery-saver kill | built, unproven | hourly job restarts it; Motorola Sleep mode killed it at 03:46 on 7 Nov |
| Step model (GRU) | works, not used | 7.3% on held-out people vs 3.8% for the detector, so it doesn't count steps |
| Model training from your walks | works (laptop) | tools/phone/sync.sh --train; only replaces the model if it beats the app on your held-out walks |
| Health Connect sharing | off | permission not granted on the phone |

## 3. How counting works
1. **Always:** Google's Recording API counts steps inside Play services (no Stride process needed). This is the base total.
2. **While walking (Max accuracy, on by default):** a step wakes Stride; the accelerometer runs at 25 Hz for the walk; Stride decides per walk:
   - pocket / bag / swinging hand / upright -> the chip's count
   - steady hand (screen up, under ~53° tilt) -> motion count (peaks + rhythm) if higher than the chip
   - seconds inside a vehicle (Activity Recognition, 30 s margin) -> chip steps there removed, motion there ignored
3. **Storage:** 15-minute buckets, largest source wins; vehicle steps come off chip sources only (Buckets.value).

## 4. Battery: what runs when
| State | Running | Cost |
|---|---|---|
| Idle (sitting, sleeping) | wake-up step detector (sensor hub), hourly sync job | lowest: no wake lock, no accelerometer |
| Walking, paced phones (yours) | accelerometer 25 Hz into its buffer, CPU woken ~every 10 s to read it, proximity on change | CPU mostly asleep |
| Walking, other phones | same, but a wake lock while walking | CPU awake during walks |
| After a walk | alarm closes the bout ~75 s after the last step | one wake |
| Recording a walk (you start it) | accel + gyro 50 Hz, wake lock, max 2 h | only while you record |
| App open | step counter live for the display | only while visible |

Next battery steps, in order (each needs a measurement before and after, from battery.sh):
1. Measure a normal day as it is now.
2. If idle wakes show up: batch the step counter during walks (it's registered unbatched while walking).
3. If walks cost more than expected: turn off proximity on phones that don't support it with the screen off (check in your lab files: P rows).
4. Never trade accuracy for battery without a measurement showing the gain.

## 5. Accuracy: plan driven by your recordings
Record (Settings -> Calibrate & record a walk), counting every step:
| Recording | Count | What it decides |
|---|---|---|
| Front pocket, 200+ steps | 2 | chip accuracy in pocket |
| Back pocket, 200+ steps | 1 | same |
| Steady hand, tilted like reading, 100+ steps | 3 | "motion if higher" vs chip-only in hand; also calibrates |
| Swinging hand, 100+ steps | 2 | swing detection (no research data for this) |
| Bag, 100+ steps | 1 | handbag misread as hand (4 of 70 in research data) |
| Short walks, ~20 steps | 3 | first steps credited back |
| Stairs | 1 | stairs cadence |
| Bus or car ride, then a short walk | 1 | vehicle removal, 30 s margin |

Then on the laptop: `tools/phone/sync.sh` (pulls and scores; read-only on the phone).
Decisions, each only if your recordings support it:
- **Hand rule:** keep "motion if higher" only if its stored error beats chip-only (the app/chipH column).
- **Position:** if pocket/bag walks read as "hand", tighten the screen-up rule or require proximity far.
- **Vehicle:** if real steps after getting off are removed, widen the margin; if fake ones remain, narrow it.
- **Detector:** re-tune MotionCounter on Cambridge + your walks; keep only changes that improve both.
- **Model:** `sync.sh --train`; switch counting to the model only if it beats the detector on your held-out walks.

## 6. Open items
- Motorola Sleep mode kills background apps at night; Recording API still counts, but walks during that time get no motion help. Phone setting, not app code.
- Most of 7 Nov's steps show in the midnight hour: check whether that was real walking or a bucket-time bug (debug line in Settings shows sources).
- Proximity with the screen off, swinging hand, and paced mode are unproven until your recordings and a normal day of use.

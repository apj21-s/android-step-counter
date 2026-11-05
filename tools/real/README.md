# Real-walk testing

Scores the motion detector and step model on labelled real walks instead of simulations.

## Data
Cambridge "Walk detection and step counting on unconstrained smartphones" (Brajdic & Harle, UbiComp 2013):
27 people, 117 labelled walks, phone in hand, typing, trouser/shirt pocket, backpack or handbag,
accelerometer + gyroscope at ~100 Hz. GPL-3.0, not stored in this repo.
https://www.repository.cam.ac.uk/handle/1810/266947

    python3 -I tools/real/convert.py <ubicomp13 dir> <out dir>     # -> 25 Hz CSVs + index.csv
    tools/real/run.sh <out dir> [-v]                               # detector scores per placement
    MAIN=Tune tools/real/run.sh <out dir>                          # settings search (odd people), checked on even people
    MAIN=NetCheck tools/real/run.sh <out dir> <weights prefix>     # step model, each person scored by a model that never saw them

Your own walks: hold "Accuracy check" in the app for a second to open Lab mode, record a walk you
counted by hand, copy `Download/Stride lab/*.csv` off the phone, then

    MAIN=LabReplay tools/real/run.sh <lab files...>    # per recording: truth, chip, peaks, hand, model,
                                                      # position, vehicle steps removed, app total, signed error,
                                                      # and the app total with chip-only in a steady hand
    python3 -I tools/real/lab2csv.py <out dir> <lab files...>   # or: score them like the Cambridge walks
    tools/real/run.sh <out dir> -v

## Step model
    python3 -m venv ~/.venvs/stride-ml && ~/.venvs/stride-ml/bin/pip install torch numpy --index-url https://download.pytorch.org/whl/cpu
    ~/.venvs/stride-ml/bin/python -I tools/ml/train.py <out dir> folds <prefix>   # 3 person-held-out models, for NetCheck
    ~/.venvs/stride-ml/bin/python -I tools/ml/train.py <out dir> final tools/ml/stepnet.txt
    python3 -I tools/ml/export_java.py tools/ml/stepnet.txt app/src/main/java/com/arco/stride/StepNetWeights.java

## Results (117 walks, mean absolute error of the step count)
Motion side only: these 2013 phones logged no step counter, so chip/fusion can't be scored here.
Detector settings were searched on odd-numbered people; even-numbered people alone scored 3.7%.

| method                                   | hand | typing | pocket | bag  | all   |
|------------------------------------------|------|--------|--------|------|-------|
| old AccelPedometer (v2.0)                | 8.2% | 5.5%   | 45.6%  | 7.8% | 22.3% |
| MotionCounter peaks                      | 3.3% | 3.0%   | 5.5%   | 2.6% | 4.0%  |
| app pipeline (Placement, then peaks + rhythm in hand) | 2.7% | 2.7% | 5.5% | 2.5% | 3.8% |
| StepNet GRU, person-held-out (3 folds)   | 7.1% | 8.4%   | 6.8%   | 7.4% | 7.3%  |

All scoring runs at 25 Hz (convert.py averages the 100 Hz data into 40 ms bins), like the app.
App motion pipeline, signed: hand +0.9% (worst walk -8.2%), typing -0.1% (worst -19.8%),
pocket -4.2% (worst -34.9%), bag +1.8% (worst +8.1%). In the app, pocket and bag use the chip.

Position (app decision: steady hand or not), 117 walks, no proximity data in this dataset:
hand recall 46/47 (97.9%), hand precision 46/50 (92.0%; 3 handbags + 1 backpack called hand),
pockets not-hand 46/46, bags not-hand 20/24, overall 112/117 (95.7%). Swinging hand: no data.
Hand walks were held tilted 25° from flat (median; 90% under 37°); "screen up" allows up to 53°.

The GRU (16 units, ~1,000 weights, trained on walk totals only) is less accurate than the peak counter
on people it never saw, so the app doesn't count with it (StepEngine.HAND_MOTION_NET = false); Lab mode
shows its count. More labelled walks (Lab mode) are the way to make it competitive.

Tried and dropped: heel-strike rhythm as a vehicle check (about 5% of real walks would fail it),
gyroscope for the peak counter (no gain in hand, worse in typing/pocket/bag; a gyroscope version of
the GRU was not trained), darkness = pocket
(a night walk with the phone in hand is dark too).

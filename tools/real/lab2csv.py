"""Lab-mode recordings (Download/Stride lab/*.csv) -> 25 Hz CSVs + index.csv that tools/real/run.sh scores.
The whole recording counts as the walk (start Lab mode, walk, stop). Usage: python3 -I lab2csv.py <out dir> <lab files...>"""
import os, re, sys
import numpy as np

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
idx = os.path.join(out, 'index.csv')
new = not os.path.exists(idx)
with open(idx, 'a') as fo:
    if new: fo.write('name,uid,exp,placement,steps,start_s,end_s,raw_hz,has_gyro\n')
    for path in sys.argv[2:]:
        head = open(path).readline()
        m = dict(re.findall(r'(\w+)=(\S+)', head))
        rows = [l.split(',') for l in open(path) if not l.startswith('#')]
        A = np.array([[float(r[0]), *map(float, r[2:5])] for r in rows if r[1] == 'A'])
        G = np.array([[float(r[0]), *map(float, r[2:5])] for r in rows if r[1] == 'G']) if any(r[1] == 'G' for r in rows) else np.zeros((0, 4))
        t0 = A[0, 0]; ta = (A[:, 0] - t0) / 1e9
        n = int(ta[-1] / 0.04); bins = np.minimum((ta / 0.04).astype(int), n - 1); cnt = np.bincount(bins, minlength=n); ok = cnt > 0
        acc = np.stack([np.bincount(bins, A[:, k], n) for k in (1, 2, 3)], 1)[ok] / cnt[ok][:, None]
        gyr = np.zeros((n, 3))
        if len(G):
            gb = np.clip(((G[:, 0] - t0) / 1e9 / 0.04).astype(int), 0, n - 1); gc = np.bincount(gb, minlength=n)
            gyr = np.stack([np.bincount(gb, G[:, k], n) for k in (1, 2, 3)], 1) / np.maximum(gc, 1)[:, None]
        gyr = gyr[ok]; t = (np.arange(n)[ok] + 1) * 0.04
        name = 'lab_' + os.path.basename(path).rsplit('.', 1)[0]
        np.savetxt(os.path.join(out, name + '.csv'), np.column_stack([np.round(t * 1e9), acc, gyr]), fmt='%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f')
        place = {'hand': 'Hand_held', 'typing': 'Handheld_using', 'pocket_front': 'Trousers_front_pocket',
                 'pocket_back': 'Trousers_back_pocket', 'bag': 'Backpack'}.get(m.get('place'), m.get('place', 'unknown'))
        fo.write(f"{name},900,lab,{place},{int(m['truth'])},0.000,{t[-1]:.3f},{len(A) / ta[-1]:.1f},{int(len(G) > 0)}\n")
        print(f"{name}: truth {m['truth']} chip {m.get('chip')} ({place}, {t[-1]:.0f} s)")

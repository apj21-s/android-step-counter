"""Convert the Cambridge ubicomp13 traces to 25 Hz CSVs + an index. Usage: python3 -I convert.py <ubicomp13 dir> <out dir>"""
import os, re, struct, sys
import numpy as np

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)

def parse(path):
    rows = []
    if path.endswith('.out'):
        data = open(path, 'rb').read()
        rec = struct.Struct('>qbfff')
        for i in range(0, len(data) - rec.size + 1, rec.size):
            rows.append(rec.unpack_from(data, i))
    else:
        for line in open(path):
            d = line.split()
            if len(d) == 5:
                rows.append((int(d[0]), int(d[1]), float(d[2]), float(d[3]), float(d[4])))
    a = np.array(rows, dtype=np.float64)
    return a

def truth():
    steps, win = {}, {}
    for line in open(os.path.join(src, 'groundtruth_SC.txt')):
        m = re.match(r'(p\d+\.\d+)\s+(\d+)', line.strip())
        if m: steps[m.group(1)] = int(m.group(2))
    for line in open(os.path.join(src, 'groundtruth_WD.txt')):
        m = re.match(r'(p\d+\.\d+)(\S+)\s+\((\d+) (\d+)\)', line.strip())
        if m: win[(m.group(1), m.group(2))] = (int(m.group(3)), int(m.group(4)))
    return steps, win

steps, win = truth()
index = []
for f in sorted(os.listdir(src)):
    m = re.match(r'(p(\d+)\.\d+)_\w+?_\d+-\d+_\d+-\d+cm_(\w+)\.(out|dat)$', f)
    if not m: continue
    exp, uid, place = m.group(1), m.group(2), m.group(3)
    key = (exp, place.replace('_', '') if (exp, place) not in win else place)
    w = win.get((exp, place)) or next((v for k, v in win.items() if k[0] == exp and k[1].replace('_', '').lower() == place.replace('_', '').lower()), None)
    if exp not in steps or w is None:
        print('skip (no truth):', f); continue
    a = parse(os.path.join(src, f))
    acc, gyr = a[a[:, 1] == 1], a[a[:, 1] == 2]
    t0 = acc[0, 0]
    ts, tg = (acc[:, 0] - t0) / 1e9, (gyr[:, 0] - t0) / 1e9
    s0, s1 = w
    if s1 >= len(acc): print('skip (window past end):', f); continue
    w0, w1 = ts[s0], ts[s1]
    # 25 Hz: average each 40 ms bin (like a phone's low-rate sensor output)
    n = int(ts[-1] / 0.04)
    bins = np.minimum((ts / 0.04).astype(int), n - 1)
    cnt = np.bincount(bins, minlength=n)
    ok = cnt > 0
    A = np.stack([np.bincount(bins, acc[:, k], n) for k in (2, 3, 4)], 1)
    G = np.zeros((n, 3))
    if len(gyr):
        gb = np.clip((tg / 0.04).astype(int), 0, n - 1)
        gc = np.bincount(gb, minlength=n)
        G = np.stack([np.bincount(gb, gyr[:, k], n) for k in (2, 3, 4)], 1) / np.maximum(gc, 1)[:, None]
    A = A[ok] / cnt[ok][:, None]; G = G[ok]
    t = (np.arange(n)[ok] + 1) * 0.04
    name = f.rsplit('.', 1)[0]
    np.savetxt(os.path.join(out, name + '.csv'), np.column_stack([np.round(t * 1e9), A, G]), fmt='%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f')
    rate = len(acc) / ts[-1]
    index.append((name, uid, exp, place, steps[exp], w0, w1, rate, len(gyr) > 0))
with open(os.path.join(out, 'index.csv'), 'w') as fo:
    fo.write('name,uid,exp,placement,steps,start_s,end_s,raw_hz,has_gyro\n')
    for r in index:
        fo.write('%s,%s,%s,%s,%d,%.3f,%.3f,%.1f,%d\n' % r)
print(len(index), 'traces written')

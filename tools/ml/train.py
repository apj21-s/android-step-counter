"""Small GRU step model trained on the Cambridge walks (25 Hz CSVs from tools/real/convert.py).

Labels are one step count per walk, so the model outputs steps-per-sample and the loss compares
each walk's sum with its count (no per-step labels needed). Still/noise sequences teach it zero.

  python -I train.py <data dir> cv [gyro]      leave-people-out cross-validation (3 folds by person)
  python -I train.py <data dir> final <out>    train on everyone, write weights for StepNet.java
"""
import csv, math, sys
import numpy as np
import torch, torch.nn as nn

torch.set_num_threads(4)
DT = 0.04
H = 16

def lp(x, hz):
    a = 1 - math.exp(-2 * math.pi * hz * DT)
    y = np.empty_like(x); s = x[0]
    for i in range(len(x)):
        s = s + a * (x[i] - s); y[i] = s
    return y

def features(acc, gyr=None):
    """Same maths as StepNet.features(): dynamic magnitude, vertical and horizontal parts (÷3), [gyro magnitude ÷3]."""
    g = np.stack([lp(acc[:, k], 0.5) for k in range(3)], 1)
    gn = np.linalg.norm(g, axis=1, keepdims=True) + 1e-6
    u = g / gn
    dyn = acc - g
    vert = (dyn * u).sum(1)
    hor = np.linalg.norm(dyn - vert[:, None] * u, axis=1)
    mag = np.linalg.norm(acc, axis=1) - gn[:, 0]
    f = [mag / 3, vert / 3, hor / 3]
    if gyr is not None:
        f.append(np.linalg.norm(gyr, axis=1) / 3)
    return np.stack(f, 1).astype(np.float32)

class Net(nn.Module):
    def __init__(self, nin):
        super().__init__()
        self.gru = nn.GRU(nin, H, batch_first=True)
        self.out = nn.Linear(H, 1)
    def forward(self, x):
        h, _ = self.gru(x)
        return nn.functional.softplus(self.out(h)).squeeze(-1) * 0.2   # steps per sample, ≤ ~5 steps/s at init scale

def load(d, use_gyro):
    walks = []
    for r in csv.DictReader(open(d + '/index.csv')):
        a = np.loadtxt(f"{d}/{r['name']}.csv", delimiter=',', dtype=np.float64)
        t = a[:, 0] / 1e9
        f = features(a[:, 1:4], a[:, 4:7] if use_gyro else None)
        s0 = int(np.searchsorted(t, float(r['start_s']))); s1 = int(np.searchsorted(t, float(r['end_s'])))
        walks.append(dict(uid=int(r['uid']), place=r['placement'], x=f, s0=s0, s1=s1, steps=int(r['steps']), name=r['name']))
    return walks

def negatives(nin, rng, n=24):
    """Zero-step sequences: still phone (sensor noise), slow tilting, random bumps, road vibration."""
    out = []
    for i in range(n):
        T = 1500
        t = np.arange(T) * DT
        acc = np.tile([0, 0, 9.81], (T, 1)) + 0.03 * rng.standard_normal((T, 3))
        kind = i % 4
        if kind == 1:
            th = 0.6 * np.sin(2 * np.pi * 0.05 * t + rng.uniform(0, 6))
            acc = np.stack([9.81 * np.sin(th), 0.02 * rng.standard_normal(T), 9.81 * np.cos(th)], 1)
        elif kind == 2:
            for b in rng.uniform(0, T * DT, 80):
                acc[:, 2] += 2.0 * np.exp(-(t - b) ** 2 / 0.003)
        elif kind == 3:
            ar = np.zeros(T)
            for k in range(1, T): ar[k] = 0.9 * ar[k - 1] + 0.25 * rng.standard_normal()
            acc[:, 2] += ar
        gyr = 0.02 * rng.standard_normal((T, 3)) if nin == 4 else None
        out.append(dict(uid=-1, place='none', x=features(acc, gyr), s0=0, s1=T, steps=0, name='neg%d' % i))
    return out

def augment(w, rng):
    x = w['x'].copy()
    x *= rng.uniform(0.8, 1.25)                         # stronger or weaker swing
    lead = max(0, w['s0'] - int(rng.integers(50, 200)))  # 2-8 s warm-up before the walk
    tail = min(len(x), w['s1'] + rng.integers(0, 50))
    return x[lead:tail], w['s0'] - lead, w['s1'] - lead

def train(walks, nin, epochs=150, seed=0):
    torch.manual_seed(seed); rng = np.random.default_rng(seed)
    net = Net(nin)
    opt = torch.optim.Adam(net.parameters(), lr=3e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, epochs)
    for ep in range(epochs):
        order = rng.permutation(len(walks))
        for b in range(0, len(order), 16):
            batch = [augment(walks[i], rng) + (walks[i]['steps'],) for i in order[b:b + 16]]
            T = max(len(x) for x, *_ in batch)
            X = torch.zeros(len(batch), T, nin); M = torch.zeros(len(batch), T); Y = torch.zeros(len(batch))
            for j, (x, s0, s1, n) in enumerate(batch):
                X[j, :len(x)] = torch.from_numpy(x); M[j, s0:s1] = 1; Y[j] = n
            pred = (net(X) * M).sum(1)
            loss = (((pred - Y) / (Y + 20)) ** 2).mean()      # relative error; +20 keeps zero-step sequences sane
            opt.zero_grad(); loss.backward(); nn.utils.clip_grad_norm_(net.parameters(), 1.0); opt.step()
        sched.step()
        if ep % 50 == 49: print(f"    epoch {ep + 1}", flush=True)
    return net

def count(net, w):
    with torch.no_grad():
        y = net(torch.from_numpy(w['x'])[None])[0].numpy()
    return float(y[w['s0']:w['s1']].sum()), float(y[:w['s0']].sum() + y[w['s1'] + 50:].sum())

def group(p):
    return {'Hand_held': 'hand', 'Handheld_using': 'typing', 'Backpack': 'bag', 'Handbag': 'bag'}.get(p, 'pocket')

def save(net, path):
    sd = {k: v.numpy() for k, v in net.state_dict().items()}
    with open(path, 'w') as f:
        for k in ['gru.weight_ih_l0', 'gru.weight_hh_l0', 'gru.bias_ih_l0', 'gru.bias_hh_l0', 'out.weight', 'out.bias']:
            f.write(k + ' ' + ' '.join('%.8g' % v for v in sd[k].ravel()) + '\n')
    print('wrote', path, sum(v.size for v in sd.values()), 'weights', flush=True)

if __name__ == '__main__':
    d, mode = sys.argv[1], sys.argv[2]
    gyro = 'gyro' in sys.argv[3:]
    nin = 4 if gyro else 3
    walks = load(d, gyro)
    rng = np.random.default_rng(1)
    if mode == 'cv':
        res = {}
        for fold in range(3):
            tr = [w for w in walks if w['uid'] % 3 != fold] + negatives(nin, rng)
            te = [w for w in walks if w['uid'] % 3 == fold]
            net = train(tr, nin)
            for w in te:
                got, extra = count(net, w)
                res.setdefault(group(w['place']), []).append((100 * (got - w['steps']) / w['steps'], extra))
            negs = negatives(nin, np.random.default_rng(100 + fold), 8)
            print(f"fold {fold}: zero-step test sequences counted {sum(count(net, n)[0] for n in negs):.1f} steps in total", flush=True)
        allv = []
        for g, v in sorted(res.items()):
            e = np.array([x[0] for x in v]); allv += list(np.abs(e))
            print(f"  {g:7s} n={len(e):3d}  mean |err| {np.abs(e).mean():4.1f}%  bias {e.mean():+5.1f}%  worst {np.abs(e).max():5.1f}%  extra/trace {np.mean([x[1] for x in v]):4.1f}")
        print(f"  ALL     n={len(allv)}  mean |err| {np.mean(allv):4.1f}%  ({'with' if gyro else 'no'} gyro)")
    elif mode == 'folds':                 # one model per fold, for the Java harness: <out>.fold0.txt ...
        for fold in range(3):
            net = train([w for w in walks if w['uid'] % 3 != fold] + negatives(nin, rng), nin)
            save(net, f"{sys.argv[3]}.fold{fold}.txt")
    else:
        net = train(walks + negatives(nin, rng), nin, seed=0)
        save(net, sys.argv[3])

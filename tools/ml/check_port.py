"""PyTorch total for one walk with saved weights, to compare with StepNet.java. Usage: python -I check_port.py <weights.txt> <trace.csv>"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, torch
from train import Net, features
m = {}
for line in open(sys.argv[1]):
    p = line.split(); m[p[0]] = np.array([float(v) for v in p[1:]], dtype=np.float32)
h = len(m['gru.bias_hh_l0']) // 3; nin = len(m['gru.weight_ih_l0']) // (3 * h)
net = Net(nin)
sd = {'gru.weight_ih_l0': m['gru.weight_ih_l0'].reshape(3 * h, nin), 'gru.weight_hh_l0': m['gru.weight_hh_l0'].reshape(3 * h, h),
      'gru.bias_ih_l0': m['gru.bias_ih_l0'], 'gru.bias_hh_l0': m['gru.bias_hh_l0'], 'out.weight': m['out.weight'].reshape(1, h), 'out.bias': m['out.bias']}
net.load_state_dict({k: torch.from_numpy(v) for k, v in sd.items()})
d = np.loadtxt(sys.argv[2], delimiter=',')
x = features(d[:, 1:4], d[:, 4:7] if nin == 4 else None)
with torch.no_grad(): y = net(torch.from_numpy(x)[None])[0].numpy()
print('pytorch total %.3f' % y.sum())

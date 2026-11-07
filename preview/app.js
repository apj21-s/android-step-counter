(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const fmt = n => Math.round(n).toLocaleString('en-US');
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const DAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const app = $('#app'), home = $('#home');
  const A = window.Android, DEVICE = !!A;
  if (DEVICE) document.body.classList.add('device');
  const readState = () => { try { return JSON.parse(A.state()); } catch (e) { return null; } };
  const parseDay = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
  const isoDay = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

  // ---------- State: fake 40-day history so the charts have data ----------
  let seed = 11;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const today0 = new Date(); today0.setHours(0, 0, 0, 0);
  const S = { goal: 8000, height: 175, steps: 0, day: today0, history: [],
    live: true, amoled: false, haptics: true, boost: true, share: false, hours: null, auto: false, hit: new Set(), range: 7, view: 'today' };
  // On the phone, real data comes from the app; in the laptop preview, fake history.
  function loadHistory(st) {
    const map = new Map(st.history), first = parseDay(st.firstDay), out = [];
    for (let i = 60; i >= 1; i--) {
      const d = new Date(S.day); d.setDate(d.getDate() - i);
      if (d >= first) out.push({ date: d, steps: map.get(isoDay(d)) || 0 });
    }
    return out;
  }
  function loadDevice(st) {
    Object.assign(S, { goal: st.goal, height: st.height, amoled: st.amoled, haptics: st.haptics, boost: st.boost, share: st.share, walklog: st.walklog, hours: st.hours, steps: st.steps });
    S.day = parseDay(st.today);
    S.history = loadHistory(st);
  }
  const DS = DEVICE ? readState() : null;
  if (DS) loadDevice(DS);
  else for (let i = 40; i >= 1; i--) {
    const d = new Date(today0); d.setDate(d.getDate() - i);
    S.history.push({ date: d, steps: Math.round(i <= 5 ? 8300 + rnd() * 4200 : 2200 + rnd() * 10800) });
  }
  const START_STEPS = DS ? DS.steps : 5240;

  // Estimates only: stride ≈ 0.415 × height, ~0.04 kcal per step, ~105 steps per active minute.
  const km = s => s * S.height * 0.415 / 100 / 1000;
  const kcal = s => s * 0.04;
  const mins = s => s / 105;
  const days = () => [...S.history, { date: S.day, steps: S.steps, today: true }];
  function streak() {
    let n = 0;
    for (let i = S.history.length - 1; i >= 0 && S.history[i].steps >= S.goal; i--) n++;
    return n + (S.steps >= S.goal ? 1 : 0);
  }

  // ---------- Rolling digits ----------
  function roll(el, text) {
    if (el.dataset.v === text) return;
    const chars = [...text];
    let slots = [...el.children];
    const same = slots.length === chars.length && slots.every((s, i) => s.classList.contains('dg') === /\d/.test(chars[i]));
    if (!same) {
      el.textContent = '';
      for (const c of chars) {
        const s = document.createElement('span');
        if (/\d/.test(c)) {
          s.className = 'dg';
          s.innerHTML = '<span class="col">' + '0123456789'.split('').map(x => `<span>${x}</span>`).join('') + '</span>';
        } else { s.className = 'sep'; s.textContent = c; }
        el.appendChild(s);
      }
      slots = [...el.children];
      void el.offsetWidth;
    }
    chars.forEach((c, i) => {
      if (!/\d/.test(c)) return;
      const col = slots[i].firstChild;
      col.style.transitionDelay = (chars.length - 1 - i) * 35 + 'ms';
      col.style.transform = `translateY(${-Number(c) * 10}%)`;
    });
    el.dataset.v = text;
    el.setAttribute('aria-label', text);
  }

  // ---------- Ring (spring motion) ----------
  const R = 128, C = 2 * Math.PI * R;
  const arc = $('#arc'), arcGlow = $('#arcGlow'), tip = $('#tip'), tipPulse = $('#tipPulse');
  for (const c of [arc, arcGlow]) { c.style.strokeDasharray = C; c.style.strokeDashoffset = C; }
  let tk = '';
  for (let i = 0; i < 60; i++) {
    const a = i / 60 * 2 * Math.PI - Math.PI / 2, r1 = 147, r2 = i % 5 ? 143 : 140;
    tk += `<line x1="${150 + r1 * Math.cos(a)}" y1="${150 + r1 * Math.sin(a)}" x2="${150 + r2 * Math.cos(a)}" y2="${150 + r2 * Math.sin(a)}"${i % 5 ? '' : ' class="major"'}/>`;
  }
  $('#ticks').innerHTML = tk;
  const tickEls = [...$('#ticks').children];

  function drawRing(p) {
    const pc = clamp(p, 0, 1), off = C * (1 - pc);
    arc.style.strokeDashoffset = off; arcGlow.style.strokeDashoffset = off;
    const a = pc * 2 * Math.PI - Math.PI / 2, x = 150 + R * Math.cos(a), y = 150 + R * Math.sin(a);
    for (const t of [tip, tipPulse]) { t.setAttribute('cx', x); t.setAttribute('cy', y); }
    const vis = pc > 0.004;
    tip.style.opacity = vis ? 1 : 0; tipPulse.style.display = vis ? '' : 'none';
    arc.style.opacity = vis ? 1 : 0; arcGlow.style.visibility = vis ? 'visible' : 'hidden';
    const lit = Math.round(pc * 60);
    tickEls.forEach((t, i) => t.classList.toggle('lit', i < lit));
    $('#bgGlow').style.opacity = 0.12 + 0.55 * pc;
  }
  const ring = { p: 0, v: 0, target: 0 };
  let raf = 0, lastT = 0;
  function setRingTarget(t) { ring.target = t; if (!raf) raf = requestAnimationFrame(tick); }
  function tick(ts) {
    const dt = lastT ? Math.min(0.032, (ts - lastT) / 1000) : 0.016;
    lastT = ts;
    if (reduce) { ring.p = ring.target; ring.v = 0; }
    else { ring.v += (90 * (ring.target - ring.p) - 15 * ring.v) * dt; ring.p += ring.v * dt; }
    if (Math.abs(ring.target - ring.p) < 0.0005 && Math.abs(ring.v) < 0.0005) {
      ring.p = ring.target; ring.v = 0; drawRing(ring.p); raf = 0; lastT = 0; return;
    }
    drawRing(ring.p);
    raf = requestAnimationFrame(tick);
  }

  // ---------- Goal burst ----------
  const cv = $('#burst'), g2 = cv.getContext('2d');
  let bRaf = 0;
  function burst() {
    if (reduce) return;
    const dpr = devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
    cv.width = w * dpr; cv.height = h * dpr; g2.setTransform(dpr, 0, 0, dpr, 0, 0);
    const cx = w / 2, cy = h / 2, r = R * (290 / 300), cs = getComputedStyle(document.documentElement), cols = ['--crimson', '--neon', '--glow'].map(v => cs.getPropertyValue(v).trim()).concat('#ffffff');
    const parts = Array.from({ length: 96 }, (_, i) => {
      const a = Math.random() * Math.PI * 2, sp = 50 + Math.random() * 230;
      return { x: cx + Math.cos(a) * r, y: cy + Math.sin(a) * r, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp,
        life: 0, max: 0.8 + Math.random() * 0.9, size: 1.3 + Math.random() * 2.8, c: cols[i % 4] };
    });
    let lt = 0;
    cancelAnimationFrame(bRaf);
    const step = ts => {
      const dt = lt ? Math.min(0.033, (ts - lt) / 1000) : 0.016; lt = ts;
      g2.clearRect(0, 0, w, h); g2.globalCompositeOperation = 'lighter';
      let alive = 0;
      for (const p of parts) {
        p.life += dt; if (p.life > p.max) continue; alive++;
        p.vx *= 0.955; p.vy = p.vy * 0.955 + 150 * dt; p.x += p.vx * dt; p.y += p.vy * dt;
        const k = 1 - p.life / p.max;
        g2.globalAlpha = k; g2.fillStyle = p.c;
        g2.beginPath(); g2.arc(p.x, p.y, p.size * (0.5 + 0.5 * k), 0, Math.PI * 2); g2.fill();
      }
      g2.globalAlpha = 1;
      if (alive) bRaf = requestAnimationFrame(step); else g2.clearRect(0, 0, w, h);
    };
    bRaf = requestAnimationFrame(step);
  }

  // ---------- Feedback ----------
  let toastT = 0;
  function toast(html) {
    const t = $('#toast'); t.innerHTML = html; t.classList.add('show');
    clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), 2400);
  }
  function buzz(ms = 14) {
    if (!S.haptics) return;
    try { if (DEVICE) A.vibrate(ms); else if (navigator.vibrate) navigator.vibrate(ms); } catch (e) { /* unsupported */ }
    if (reduce) return;
    app.classList.remove('nudge'); void app.offsetWidth; app.classList.add('nudge');
  }
  function checkHits(silent) {
    const pc = S.steps / S.goal * 100;
    for (const m of [...S.hit]) if (pc < m) S.hit.delete(m);
    for (const m of [25, 50, 75, 100]) {
      if (pc < m || S.hit.has(m)) continue;
      S.hit.add(m);
      if (silent) continue;
      if (m === 100) { burst(); buzz(30); toast(`<svg><use href="#flame"/></svg><span>Goal reached · ${fmt(S.goal)} steps</span>`); }
      else { buzz(); toast(`<span class="tdot"></span><span>${m}% of today's goal</span>`); }
    }
  }

  // ---------- Render ----------
  function render(silent = false) {
    const s = S.steps, pc = s / S.goal, d = S.day, st = streak();
    roll($('#stepCount'), fmt(s));
    roll($('#kmV'), km(s).toFixed(2));
    roll($('#kcalV'), fmt(kcal(s)));
    roll($('#minV'), fmt(mins(s)));
    $('#goalLabel').textContent = fmt(S.goal);
    $('#pctLabel').textContent = Math.floor(pc * 100) + '%';
    $('#streakChip').textContent = st + (st === 1 ? ' day' : ' days');
    $('#dateLabel').textContent = `${DAY[d.getDay()]} · ${d.getDate()} ${MON[d.getMonth()]}`;
    if (home) {
      $('#homeDate').textContent = `${DAY[d.getDay()]}, ${d.getDate()} ${MON[d.getMonth()]}`;
      $('#calDay').textContent = d.getDate();
    }
    app.classList.toggle('done', s >= S.goal);
    app.classList.toggle('empty-state', s === 0);
    setRingTarget(pc);
    checkHits(silent);
    renderHours();
    renderWidgets();
    renderNotif();
    if (S.view === 'history') renderHistory(false);
    const sl = $('#stepSlider');
    if (sl) {
      if (document.activeElement !== sl) { sl.value = Math.min(s, sl.max); fillRange(sl); }
      $('#sliderVal').textContent = fmt(s);
    }
  }

  // ---------- Today by hour ----------
  const HOUR_SHAPE = [0, 0, 0, 0, 0, 0, 1, 4, 8, 5, 3, 3, 6, 4, 3, 3, 4, 7, 9, 6, 3, 2, 1, 0];
  function renderHours() {
    const el = $('#hours'); if (!el) return;
    let hrs = S.hours;
    const nowH = new Date().getHours();
    if (!hrs || !DEVICE) {          // laptop preview: spread today's steps over a typical day
      const w = HOUR_SHAPE.map((v, i) => (i <= nowH ? v : 0)), sum = w.reduce((a, b) => a + b, 0) || 1;
      hrs = w.map(v => Math.round(S.steps * v / sum));
    }
    const max = Math.max(1, ...hrs);
    if (el.children.length !== hrs.length) el.innerHTML = '<i></i>'.repeat(hrs.length);
    [...el.children].forEach((b, i) => {
      b.style.height = Math.max(4, hrs[i] / max * 100) + '%';
      b.classList.toggle('on', hrs[i] > 0);
      b.classList.toggle('now', i === nowH);
      b.title = `${i}:00 · ${fmt(hrs[i])} steps`;
    });
  }

  // ---------- Widgets (no animation, like real widgets) ----------
  let tk2 = '';
  for (let i = 0; i < 12; i++) {
    const a = i / 12 * 2 * Math.PI, r1 = 47.5, r2 = i % 3 ? 49 : 50;
    tk2 += `<line x1="${50 + r1 * Math.sin(a)}" y1="${50 - r1 * Math.cos(a)}" x2="${50 + r2 * Math.sin(a)}" y2="${50 - r2 * Math.cos(a)}"/>`;
  }
  if (home) $$('.wb-ticks', home).forEach(g => { g.innerHTML = tk2; });
  function renderWidgets() {
    if (!home) return;
    const s = S.steps, pc = clamp(s / S.goal, 0, 1), vis = pc > 0.004 ? 1 : 0;
    $$('.w-arc', home).forEach(c => {
      const L = 2 * Math.PI * c.r.baseVal.value;
      c.style.strokeDasharray = L; c.style.strokeDashoffset = L * (1 - pc); c.style.opacity = vis;
    });
    const a = pc * 2 * Math.PI - Math.PI / 2;
    $$('.w-tip', home).forEach(t => {
      const r = Number(t.dataset.r);
      t.setAttribute('cx', 50 + r * Math.cos(a)); t.setAttribute('cy', 50 + r * Math.sin(a)); t.style.opacity = vis;
    });
    const set = (k, v) => $$(`[data-w=${k}]`, home).forEach(e => { e.textContent = v; });
    set('steps', fmt(s)); set('goal', fmt(S.goal)); set('pct', Math.floor(s / S.goal * 100) + '%');
    set('km', km(s).toFixed(1)); set('kcal', fmt(kcal(s)));
    $$('[data-w=bar]', home).forEach(e => { e.style.width = pc * 100 + '%'; e.style.opacity = vis; });
    const wk = days().slice(-7);
    $$('.w-dots', home).forEach(el => {
      if (el.children.length !== 7) el.innerHTML = '<i></i>'.repeat(7);
      [...el.children].forEach((b, i) => {
        b.classList.toggle('hit', wk[i].steps >= S.goal);
        b.classList.toggle('now', !!wk[i].today);
      });
    });
  }
  function renderNotif() {
    if (!$('#notif')) return;
    $('#notif').classList.toggle('off', !S.live);
    $('#nText').textContent = `${fmt(S.steps)} steps · ${Math.floor(S.steps / S.goal * 100)}%`;
    $('#nBar').style.width = clamp(S.steps / S.goal, 0, 1) * 100 + '%';
  }

  // ---------- History ----------
  function renderHistory(animate) {
    const list = days().slice(-S.range), max = Math.max(S.goal * 1.25, ...list.map(d => d.steps));
    const chart = $('#chart'), xs = $('#chartX'), dense = S.range > 7;
    chart.classList.toggle('dense', dense); xs.classList.toggle('dense', dense);
    let bars = $$('.bar', chart);
    if (animate || bars.length !== list.length) {
      bars.forEach(b => b.remove());
      list.forEach((d, i) => {
        const b = document.createElement('button');
        b.className = 'bar';
        b.innerHTML = '<span class="fill"></span><span class="dot"></span>';
        b.querySelector('.fill').style.transitionDelay = animate && !reduce ? `${i * 30}ms, 0ms, 0ms` : '0ms';
        chart.appendChild(b);
      });
      bars = $$('.bar', chart);
      if (animate && !reduce) { chart.classList.add('pre'); void chart.offsetWidth; requestAnimationFrame(() => chart.classList.remove('pre')); }
      xs.innerHTML = list.map((d, i) => {
        const lab = dense ? ((list.length - 1 - i) % 5 === 0 ? d.date.getDate() : '') : DAY[d.date.getDay()][0];
        return `<span class="${d.today ? 'today' : ''}">${lab}</span>`;
      }).join('');
      $('#barTip').classList.remove('show');
    }
    list.forEach((d, i) => {
      const b = bars[i], h = Math.max(1.5, d.steps / max * 100), dot = b.querySelector('.dot');
      b.querySelector('.fill').style.height = h + '%';
      dot.style.bottom = h + '%'; dot.style.display = d.steps >= S.goal ? '' : 'none';
      b.classList.toggle('today', !!d.today);
      b.classList.toggle('under', d.steps < S.goal);
      b._d = d;
    });
    $('#goalLine').style.bottom = (S.goal / max * 100) + '%';
    const sum = list.reduce((a, d) => a + d.steps, 0);
    const best = list.reduce((a, d) => (d.steps > a.steps ? d : a), list[0]);
    $('#hStreak').textContent = streak();
    $('#hBest').textContent = fmt(best.steps);
    $('#hAvg').textContent = fmt(sum / list.length);
    $('#hRangeLabel').textContent = `Last ${S.range} days`;
    renderHeat();
  }
  function renderHeat() {
    const map = new Map(days().map(d => [d.date.toDateString(), d]));
    const t = S.day, dow = (t.getDay() + 6) % 7;
    const start = new Date(t); start.setDate(t.getDate() - dow - 28);
    let h = ['M', 'T', 'W', 'T', 'F', 'S', 'S'].map(x => `<span class="dow">${x}</span>`).join('');
    for (let i = 0; i < 35; i++) {
      const d = new Date(start); d.setDate(start.getDate() + i);
      const rec = map.get(d.toDateString());
      let cls = 'c';
      if (d > t) cls += ' future';
      else if (rec) {
        const r = rec.steps / S.goal;
        cls += r >= 1 ? ' l4' : r >= 0.8 ? ' l3' : r >= 0.5 ? ' l2' : r > 0 ? ' l1' : '';
        if (rec.today) cls += ' today';
      }
      h += `<span class="${cls}" title="${d.getDate()} ${MON[d.getMonth()]}${rec ? ': ' + fmt(rec.steps) + ' steps' : ''}"></span>`;
    }
    $('#heat').innerHTML = h;
    $('#heatTitle').textContent = `${start.getDate()} ${MON[start.getMonth()]} – ${t.getDate()} ${MON[t.getMonth()]}`;
  }
  $('#chart').addEventListener('click', e => {
    const b = e.target.closest('.bar'), tipEl = $('#barTip');
    $$('.bar.sel').forEach(x => x.classList.remove('sel'));
    if (!b) { tipEl.classList.remove('show'); return; }
    b.classList.add('sel');
    const d = b._d;
    const label = d.today ? 'Today' : `${DAY[d.date.getDay()]} ${d.date.getDate()} ${MON[d.date.getMonth()]}`;
    tipEl.innerHTML = `<b>${fmt(d.steps)}</b>${label} · ${km(d.steps).toFixed(1)} km`;
    const card = $('.chart-card'), br = b.getBoundingClientRect(), cr = card.getBoundingClientRect();
    const scale = cr.width / card.offsetWidth;
    tipEl.style.left = clamp((br.left + br.width / 2 - cr.left) / scale, 70, card.offsetWidth - 70) + 'px';
    tipEl.classList.add('show');
  });
  $('#rangeSeg').addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    S.range = Number(b.dataset.range);
    $$('#rangeSeg button').forEach(x => x.classList.toggle('on', x === b));
    $('#rangeSeg').classList.toggle('r30', S.range === 30);
    renderHistory(true);
  });

  // ---------- Navigation ----------
  const ORDER = ['today', 'history', 'settings'];
  function go(v) {
    if (v === S.view) return;
    const to = ORDER.indexOf(v);
    $$('.view').forEach(el => {
      const i = ORDER.indexOf(el.dataset.view);
      el.classList.toggle('active', i === to);
      el.classList.toggle('left', i < to);
    });
    $$('.tab').forEach(t => t.classList.toggle('on', t.dataset.go === v));
    $('.tab-ind').style.transform = `translateX(${to * 100}%)`;
    S.view = v;
    if (v === 'history') renderHistory(true);
  }
  $$('.tab').forEach(t => t.addEventListener('click', () => go(t.dataset.go)));
  if (home) $$('.w', home).forEach(w => w.addEventListener('click', () => {
    go('today');
    if (!reduce) app.animate([{ transform: 'scale(.93)', opacity: 0.5 }, { transform: 'none', opacity: 1 }], { duration: 480, easing: 'cubic-bezier(.2,.9,.25,1)' });
  }));

  // ---------- Settings ----------
  function fillRange(r) { r.style.setProperty('--p', ((r.value - r.min) / (r.max - r.min) * 100) + '%'); }
  const goalRange = $('#goalRange');
  goalRange.addEventListener('input', () => {
    S.goal = Number(goalRange.value);
    $('#goalVal').textContent = fmt(S.goal);
    fillRange(goalRange);
    if (DEVICE) A.setGoal(S.goal);
    render(true);
  });
  function setHeight(h) {
    S.height = clamp(h, 140, 210);
    $('#heightVal').textContent = S.height + ' cm';
    $('#strideHint').textContent = `Stride ${Math.round(S.height * 0.415)} cm`;
    if (DEVICE) A.setHeight(S.height);
    render(true);
  }
  $('#hMinus').addEventListener('click', () => setHeight(S.height - 1));
  $('#hPlus').addEventListener('click', () => setHeight(S.height + 1));

  function setSwitch(key, on) {
    S[key] = on;
    $$(`.switch[data-key=${key}]`).forEach(sw => sw.setAttribute('aria-checked', String(on)));
    if (key === 'amoled') { app.classList.toggle('amoled', on); setSeg('theme', on ? 'amoled' : 'obsidian'); }
    if (key === 'auto') startWalker();
    if (key === 'live') { renderWidgets(); renderNotif(); }
    if (DEVICE && key === 'live') A.setLive(on);
    if (DEVICE && key === 'amoled') A.setAmoled(on);
    if (DEVICE && key === 'haptics') A.setHaptics(on);
    if (DEVICE && key === 'boost') A.setBoost(on);
    if (DEVICE && key === 'share') A.setShare(on);
    if (DEVICE && key === 'walklog') A.setWalkLog(on);
  }
  $$('.switch').forEach(sw => sw.addEventListener('click', () => setSwitch(sw.dataset.key, sw.getAttribute('aria-checked') !== 'true')));

  // 100-step test (simulated: the counter stops somewhere between 96 and 100)
  let testRun = false, testCount = 0, testTarget = 0, testT = 0;
  $('#testBtn').addEventListener('click', () => {
    const btn = $('#testBtn'), res = $('#testResult');
    if (!testRun) {
      testRun = true; testCount = 0; testTarget = 96 + Math.floor(Math.random() * 5);
      $('#testCount').textContent = '0'; res.textContent = ''; res.className = 't-result';
      $('#testStatus').textContent = DEVICE ? 'Walk 100 steps, pause a moment, then tap Stop.' : 'Walking… tap Stop after your 100th step.';
      btn.textContent = 'Stop';
      if (DEVICE) {
        const base = readState().liveCounter;
        testT = setInterval(() => {
          const st = readState(); if (!st) return;
          if (st.liveCounter < 0) { $('#testStatus').textContent = "This phone has no step counter to test."; return; }
          $('#testCount').textContent = testCount = st.liveCounter - base;
          $('#testDebug').textContent = st.debug;
        }, 300);
      } else testT = setInterval(() => { if (testCount < testTarget) $('#testCount').textContent = ++testCount; }, reduce ? 5 : 55);
    } else {
      clearInterval(testT); testRun = false;
      const acc = Math.max(0, 100 - Math.abs(testCount - 100));
      res.textContent = `${testCount} of 100 counted · ${acc}% accurate`;
      res.className = 't-result ' + (acc >= 95 ? 'good' : 'meh');
      $('#testStatus').textContent = acc >= 95 ? 'Inside the 95–98% target.' : 'Try again walking at a steady pace.';
      btn.textContent = 'Run again';
      buzz();
    }
  });

  // ---------- Accent colour (any colour) ----------
  function hex2hsl(hex) {
    const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255);
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
    let h = 0, s = 0;
    if (mx !== mn) {
      const d = mx - mn;
      s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      h = 60 * (mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4);
    }
    return [h, s * 100, l * 100];
  }
  function hsl2hex(h, s, l) {
    s /= 100; l /= 100;
    const k = n => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
    const f = n => l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1));
    return '#' + [f(0), f(8), f(4)].map(x => Math.round(x * 255).toString(16).padStart(2, '0')).join('');
  }
  function setAccent(hex) {
    hex = hex.toLowerCase();
    const [h, s, l] = hex2hsl(hex), base = hex === '#dc143c';
    const a2 = base ? '#ff2d55' : hsl2hex(h, Math.min(100, s + 15), Math.min(l + 12, 74));
    const glow = base ? '#ff6b81' : hsl2hex(h, Math.min(100, s + 10), Math.min(l + 24, 84));
    const [r, g, b] = [1, 3, 5].map(i => parseInt(a2.slice(i, i + 2), 16) / 255);
    const rs = document.documentElement.style;
    rs.setProperty('--crimson', hex); rs.setProperty('--neon', a2); rs.setProperty('--glow', glow);
    rs.setProperty('--on-accent', 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.62 ? '#141414' : '#fff');
    const preset = $$('.sw[data-c]').find(x => x.dataset.c.toLowerCase() === hex);
    $$('.sw[data-c]').forEach(x => x.classList.toggle('on', x === preset));
    const custom = $('.sw-custom');
    custom.classList.toggle('on', !preset); custom.style.setProperty('--c', hex);
    $('#accentName').textContent = preset ? preset.dataset.n : hex.toUpperCase();
    $('#customColor').value = hex;
    try { localStorage.setItem('stride-accent', hex); } catch (e) { /* storage blocked */ }
    if (DEVICE) A.setAccent(hex, a2, glow);
  }
  $$('.sw[data-c]').forEach(x => x.addEventListener('click', () => setAccent(x.dataset.c)));
  $('#customColor').addEventListener('input', e => setAccent(e.target.value));
  let savedAccent = null;
  try { savedAccent = localStorage.getItem('stride-accent'); } catch (e) { /* storage blocked */ }
  setAccent(DS ? DS.accent : /^#[0-9a-f]{6}$/i.test(savedAccent || '') ? savedAccent : '#DC143C');

  // ---------- Panel (laptop preview only) ----------
  if (!DEVICE) {
  let pending = 0, walkT = 0;
  function startWalker() { if (!walkT && (pending > 0 || S.auto)) walkT = setInterval(walkTick, 90); }
  function walkTick() {
    const take = pending > 0 ? Math.min(pending, Math.max(1, Math.ceil(pending / 12))) : 0;
    pending -= take;
    const add = take + (S.auto ? 3 : 0);
    if (add) { S.steps += add; render(); }
    if (!pending && !S.auto) { clearInterval(walkT); walkT = 0; }
  }
  function walk(n) { pending += n; startWalker(); }
  $('#walkBtn').addEventListener('click', () => walk(50));
  $('#goalBtn').addEventListener('click', () => { if (S.steps + pending < S.goal) walk(S.goal - S.steps - pending + 12); });
  const slider = $('#stepSlider');
  slider.addEventListener('input', () => { pending = 0; S.steps = Number(slider.value); fillRange(slider); render(); });
  $('#nextDay').addEventListener('click', () => {
    S.history.push({ date: S.day, steps: S.steps });
    if (S.history.length > 60) S.history.shift();
    const d = new Date(S.day); d.setDate(d.getDate() + 1);
    S.day = d; S.steps = 0; pending = 0; S.hit.clear();
    render(true);
    if (S.view === 'history') renderHistory(true);
    toast('<span class="tdot"></span><span>New day · counter reset at midnight</span>');
  });
  $('#resetDay').addEventListener('click', () => { S.steps = 0; pending = 0; render(true); });
  }

  function setSeg(group, val) { $$(`.pseg[data-group=${group}] button`).forEach(b => b.classList.toggle('on', b.dataset.val === val)); }
  $$('.pseg').forEach(seg => seg.addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    const g = seg.dataset.group, v = b.dataset.val;
    setSeg(g, v);
    if (g === 'theme') setSwitch('amoled', v === 'amoled');
    if (g === 'wstyle' && home) home.className = home.className.replace(/\bws-\w+/, 'ws-' + v);
    if (g === 'wall' && home) home.className = home.className.replace(/\bwp-\w+/, 'wp-' + v);
    if (g === 'wstyle' && DEVICE) A.setWidgetStyle(v);
  }));

  // ---------- Splash ----------
  function splash(then) {
    const sp = $('#splash');
    sp.innerHTML = sp.innerHTML;               // restarts the CSS animations
    sp.classList.remove('hide');
    setTimeout(() => { sp.classList.add('hide'); then(); }, reduce ? 150 : 1500);
  }
  if ($('#replay')) $('#replay').addEventListener('click', () => {
    go('today');
    ring.p = 0; ring.v = 0; drawRing(0);
    $$('.roll', app).forEach(el => { el.textContent = ''; el.dataset.v = ''; });
    splash(() => render(true));
  });

  // ---------- Clock + scale phones to the window ----------
  function clock() {
    const n = new Date();
    $$('.sb-time').forEach(e => { e.textContent = `${n.getHours()}:${String(n.getMinutes()).padStart(2, '0')}`; });
  }
  function fit() {
    const s = innerWidth < 900 ? Math.min(1, (innerWidth - 32) / 390) : clamp((innerHeight - 90) / 844, 0.6, 1);
    document.documentElement.style.setProperty('--s', s.toFixed(3));
  }
  addEventListener('resize', fit);
  fit(); clock(); setInterval(clock, 15000);
  fillRange(goalRange);

  // ---------- Phone: settings from storage, live updates, notices ----------
  function notice(st) {
    const n = $('#notice');
    const msg = !st.hasSensor ? "This phone has no step-counter chip, so Stride can't count steps."
      : !st.permission ? 'Allow Physical activity so Stride can count your steps.' : '';
    n.classList.toggle('show', !!msg);
    $('#noticeText').textContent = msg;
    $('#noticeBtn').style.display = st.hasSensor && !st.permission ? '' : 'none';
    const SRC = { recording: 'Google Recording API · low power, works when Stride is closed', counter: 'Phone step counter',
      detector: 'Phone step detector', accel: 'Motion detector (this phone has no step sensor)', none: 'No step sensor found' };
    $('#sourceHint').textContent = (SRC[st.source] || st.source) + (st.boost && st.source !== 'accel' ? ' + Max accuracy' : '');
    $('#hcRow').style.setProperty('display', st.hcSupported ? '' : 'none', 'important');
    let ok = false; try { ok = A.batteryOk(); } catch (e) { /* older bridge */ }
    $('#batteryBtn').textContent = ok ? 'On' : 'Allow';
    $('#batteryBtn').disabled = ok;
  }
  if (DEVICE) {
    goalRange.value = S.goal; $('#goalVal').textContent = fmt(S.goal); fillRange(goalRange);
    $('#heightVal').textContent = S.height + ' cm';
    $('#strideHint').textContent = `Stride ${Math.round(S.height * 0.415)} cm`;
    for (const k of ['amoled', 'haptics', 'boost', 'share', 'walklog']) $$(`.switch[data-key=${k}]`).forEach(sw => sw.setAttribute('aria-checked', String(S[k])));
    app.classList.toggle('amoled', S.amoled); setSeg('theme', S.amoled ? 'amoled' : 'obsidian');
    setSeg('wstyle', DS.wstyle);
    $('#noticeBtn').addEventListener('click', () => A.requestPermission());
    $('#batteryBtn').addEventListener('click', () => A.batteryExempt());
    $('#guideBtn').addEventListener('click', () => A.openBatteryGuide());
    notice(DS);
    // The app pushes a full snapshot after each sync, and step counts at most once a second.
    window.onState = st => {
      notice(st);
      if (st.today !== isoDay(S.day)) { loadDevice(st); S.hit.clear(); render(true); if (S.view === 'history') renderHistory(true); return; }
      S.hours = st.hours;
      S.history = loadHistory(st);
      for (const k of ['boost', 'share']) { S[k] = st[k]; $$(`.switch[data-key=${k}]`).forEach(sw => sw.setAttribute('aria-checked', String(st[k]))); }
      stepTo(st.steps);
      renderHours();
    };
    // Calibrate & record. No prompt()/confirm(): Android's WebView ignores them without extra code.
    const mmss = n => `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`;
    const showList = () => {
      const l = JSON.parse(A.recList());
      $('#recList').textContent = l.length ? `Saved in Download/Stride lab: ${l.length} · latest ${l[0].name} (${l[0].kb} KB)` : '';
    };
    let recTimer = 0;
    const recUi = () => {
      const st = JSON.parse(A.recStatus());
      const counting = !$('#recCountRow').hidden;
      $('#recBtn').textContent = st.on ? 'Stop' : 'Start';
      $('#recBtn').hidden = counting;
      $('#recPlace').disabled = st.on;
      if (st.on && !counting) $('#recMsg').textContent = `Recording ${mmss(st.seconds)} · chip ${st.chip} · motion ${st.peaks}`;
      clearTimeout(recTimer);
      if (st.on) recTimer = setTimeout(recUi, 1000);
    };
    $('#recBtn').addEventListener('click', () => {
      const st = JSON.parse(A.recStatus());
      if (!st.on) {
        const err = A.recStart($('#recPlace').value);
        $('#recMsg').textContent = err || 'Recording. Walk and count your steps.';
      } else if ($('#recPlace').value === 'car') {
        finish(0);
      } else {
        $('#recCountRow').hidden = false;            // keeps recording until the count is saved
        $('#recMsg').textContent = 'How many steps did you count?';
        $('#recCount').focus();
        setTimeout(() => $('#recCount').scrollIntoView({block: 'center', behavior: 'smooth'}), 350);   // after the keyboard opens
      }
      recUi();
    });
    const finish = t => {
      const place = $('#recPlace').value;
      const r = JSON.parse(A.recStop(t, true));
      $('#recCountRow').hidden = true; $('#recCount').value = '';
      $('#recMsg').textContent = r.error ? 'Not saved: ' + r.error
        : `You ${t} · chip ${r.chip} · motion ${place.startsWith('hand') || place === 'typing' ? r.hand : r.peaks}`
          + (r.calibrated ? ' · calibrated' : '') + ` · saved ${r.file}`;
      showList(); recUi();
    };
    $('#recSave').addEventListener('click', () => {
      const t = parseInt($('#recCount').value, 10);
      if (t > 0) finish(t); else $('#recMsg').textContent = 'Type the number of steps you counted.';
    });
    $('#recCount').addEventListener('keydown', e => { if (e.key === 'Enter') $('#recSave').click(); });
    $('#recCount').addEventListener('focus', () => {
      document.body.classList.add('typing');          // the tab bar would sit on top of the card
      setTimeout(() => $('#recCount').scrollIntoView({block: 'center'}), 350);
    });
    $('#recCount').addEventListener('blur', () => document.body.classList.remove('typing'));
    $('#recCancel').addEventListener('click', () => {
      A.recCancel(); $('#recCountRow').hidden = true; $('#recMsg').textContent = 'Discarded.'; recUi();
    });
    let resetArmed = 0;
    $('#learnReset').addEventListener('click', () => {
      if (!resetArmed) {
        $('#learnReset').textContent = 'Tap again to reset';
        resetArmed = setTimeout(() => { resetArmed = 0; $('#learnReset').textContent = 'Reset what Stride learned'; }, 4000);
        return;
      }
      clearTimeout(resetArmed); resetArmed = 0; $('#learnReset').textContent = 'Reset what Stride learned';
      A.learnReset(); $('#recMsg').textContent = 'Back to the default settings.';
    });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { recUi(); showList(); } });
    recUi(); showList();
  }

  // Count up one step at a time instead of jumping in batches.
  let target = S.steps, tweenT = 0;
  function stepTo(n) {
    target = n;
    if (document.hidden || n < S.steps || n - S.steps > 400) { clearTimeout(tweenT); tweenT = 0; S.steps = n; render(); return; }
    if (!tweenT && n > S.steps) tweenT = setTimeout(tweenTick, 0);
  }
  function tweenTick() {
    tweenT = 0;
    if (S.steps >= target) return;
    S.steps++;
    render();
    const left = target - S.steps;
    if (left > 0) tweenT = setTimeout(tweenTick, clamp(900 / left, 35, 320));
  }
  window.onSteps = n => stepTo(Number(n));

  // ---------- Boot ----------
  render(true);
  splash(() => { S.steps = START_STEPS; checkHits(true); render(true); });
})();

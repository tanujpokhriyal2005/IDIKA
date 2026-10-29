// Shared helpers: safe HTML templates, API calls, formatting, toasts and dialogs.

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ESC[c]);

// html`...` escapes every interpolated value unless it came from html`` or raw().
export const raw = (s) => ({ __html: String(s) });
const part = (v) => {
  if (v == null || v === false || v === true) return '';
  if (Array.isArray(v)) return v.map(part).join('');
  if (typeof v === 'object' && '__html' in v) return v.__html;
  return esc(v);
};
export function html(strings, ...vals) {
  let out = '';
  strings.forEach((s, i) => { out += s + (i < vals.length ? part(vals[i]) : ''); });
  return raw(out);
}
export const render = (el, tpl) => { el.innerHTML = tpl.__html; };
export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

// ---------- API ----------

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed (${status})`);
    this.status = status;
    this.code = body?.error;
    this.body = body || {};
  }
}

let onAuthLost = () => {};
export const setAuthLost = (f) => { onAuthLost = f; };

export async function api(path, { method = 'GET', body, signal, quiet = false } = {}) {
  const opt = { method, signal, credentials: 'same-origin', headers: { 'X-Fieldproof': '1' } };
  if (body !== undefined) {
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, opt);
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError(0, { message: 'Could not reach the server. Check the connection and try again.' });
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { message: text }; }
  if (!res.ok) {
    const err = new ApiError(res.status, data);
    if (!quiet && (res.status === 401 || err.code === 'password_change_required')) onAuthLost(err);
    throw err;
  }
  return data;
}

export const qs = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') p.set(k, v); });
  const s = p.toString();
  return s ? `?${s}` : '';
};

// ---------- formatting ----------

export const fmtInt = (n) => (n ?? 0).toLocaleString('en-IN');
export const fmtDate = (s) => (s ? new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
export const fmtDateTime = (s) => (s ? new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
export function ago(s) {
  if (!s) return '—';
  const d = (Date.now() - new Date(s).getTime()) / 1000;
  if (d < 60) return 'just now';
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  const day = new Date(s); const today = new Date();
  const days = Math.floor((new Date(today.toDateString()) - new Date(day.toDateString())) / 86400000);
  if (days === 0) return `today ${day.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })}`;
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days} days ago`;
  return fmtDate(s);
}
export const daysSince = (s) => (s ? (Date.now() - new Date(s).getTime()) / 86400000 : Infinity);
export const acres = (m2) => m2 / 4046.8564224;
export const phone = (p) => (p && p.length === 10 ? `+91 ${p.slice(0, 5)} ${p.slice(5)}` : p || '');

export const DOCS = [
  { key: 'aadhaar', param: 'aadhaar', label: 'Aadhaar', short: 'Aadhaar' },
  { key: 'land_record', param: 'land', label: 'Land record (7/12)', short: 'Land record' },
  { key: 'water_source', param: 'water', label: 'Water source photo', short: 'Water source' },
  { key: 'boundary', param: 'boundary', label: 'Farm boundary (KML)', short: 'Boundary' },
];
export const STATUS_LABEL = { new: 'New', approved: 'Approved', rejected: 'Rejected', pending: 'Pending', missing: 'Not uploaded' };
export const pill = (status) => html`<span class="pill p-${status}">${STATUS_LABEL[status] || status}</span>`;
// Payment and plantation steps, as named on the farmer list and the farmer page.
export const PAY_LABEL = { locked: 'Not open yet', none: 'Not paid', waiting: 'To approve', approved: 'Approved', rejected: 'Rejected', pending: 'Pending' };
const PAY_CLASS = { locked: 'off', none: 'off', waiting: 'new', approved: 'approved', rejected: 'rejected', pending: 'pending' };
export const PLANT_LABEL = {
  locked: 'Not started', pre_walk: 'Boundary to walk', delivery: 'Waiting for saplings', short: 'Delivery short', otp: "Farmer's OTP",
  planting: 'Planting', post_walk: 'Boundary after planting', to_review: 'To review', rejected: 'Rejected', pending: 'Pending', done: 'Approved',
  land_review: 'Land change', land_fix: 'Land change sent back',
};
const PLANT_CLASS = { locked: 'off', short: 'rejected', to_review: 'new', rejected: 'rejected', pending: 'pending', done: 'approved', land_review: 'rejected', land_fix: 'pending' };
export const rupees = (n) => `₹${(n ?? 0).toLocaleString('en-IN')}`;
export const payPill = (st, extra = '') => html`<span class="pill p-${PAY_CLASS[st] || 'off'}">${PAY_LABEL[st] || st}${extra ? ` · ${extra}` : ''}</span>`;
export const plantPill = (st, extra = '') => html`<span class="pill p-${PLANT_CLASS[st] || 'step'}">${PLANT_LABEL[st] || st}${extra ? ` · ${extra}` : ''}</span>`;
export const ROLE_LABEL = { admin: 'Admin', state_lead: 'State lead', ngo: 'NGO', surveyor: 'Surveyor' };

export const icon = {
  plus: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M5 12h14M12 5v14"/></svg>'),
  leaf: raw('<svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 20h10M10 20c5.5-2.5.8-6.4 3-10M9.5 9.4c1.1.8 1.8 2.2 2.3 3.7-2 .4-3.5.4-4.8-.3-1.2-.6-2.3-1.9-3-4.2 2.8-.5 4.4 0 5.5.8zM14.1 6a7 7 0 0 0-1.1 4c1.9-.1 3.3-.6 4.3-1.4 1-1 1.6-2.3 1.7-4.6-2.7.1-4 1-4.9 2z"/></svg>'),
  rotate: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#1C1C1E" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/></svg>'),
  back: raw('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>'),
  menu: raw('<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg>'),
  id: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="11" r="2"/><path d="M6.5 16c.5-1.3 1.4-2 2.5-2s2 .7 2.5 2M14 10h4M14 13h3"/></svg>'),
  doc: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4M16 13H8M16 17H8"/></svg>'),
  drop: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5S5 13 5 15a7 7 0 0 0 7 7z"/></svg>'),
  map: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3zM9 3v15M15 6v15"/></svg>'),
  pin: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/></svg>'),
  home: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/></svg>'),
  user: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/></svg>'),
  users: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c1-3.5 3.6-5.5 6.5-5.5s5.5 2 6.5 5.5M16 4.5a3.5 3.5 0 0 1 0 7M18 14.6c1.8.7 3 2.6 3.5 5.4"/></svg>'),
  lock: raw('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Locked"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>'),
  check: raw('<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#1F7A3A" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-label="Passed" role="img" class="ck-i"><path d="M20 6 9 17l-5-5"/></svg>'),
  cross: raw('<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#B42318" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-label="Failed" role="img" class="ck-i"><path d="M18 6 6 18M6 6l12 12"/></svg>'),
  rupee: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h12M6 8h12M6 13l8.5 8M6 13h3c6.7 0 6.7-10 0-10"/></svg>'),
  sprout: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 20h10M12 20v-8M12 12c0-3-2-5-6-5 0 3 2 5 6 5zM12 12c0-3 2-6 6-6 0 3-2 6-6 6z"/></svg>'),
  flag: raw('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 22V4c5-3 9 3 16 0v10c-7 3-11-3-16 0"/></svg>'),
};

// ---------- toast, confirm, lightbox ----------

let toastTimer;
export function toast(msg, bad = false) {
  const el = $('#toast');
  render(el, html`<div class="${bad ? 'bad' : ''}">${msg}</div>`);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.innerHTML = ''; }, bad ? 6000 : 4000);
}

export function confirmBox({ title, text, ok = 'OK', cancel = 'Cancel', danger = false }) {
  return new Promise((resolve) => {
    const bg = document.createElement('div');
    bg.className = 'modal-bg';
    render(bg, html`<div class="modal" role="dialog" aria-modal="true" aria-labelledby="mt"><h3 id="mt">${title}</h3><p>${text}</p>
      <div class="row"><button class="btn" data-a="no">${cancel}</button><button class="btn ${danger ? 'danger' : 'primary'}" data-a="yes">${ok}</button></div></div>`);
    const done = (v) => { bg.remove(); document.removeEventListener('keydown', onKey); resolve(v); };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    bg.addEventListener('click', (e) => {
      if (e.target === bg) done(false);
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a) done(a === 'yes');
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(bg);
    $('[data-a="yes"]', bg).focus();
  });
}

export function infoBox(title, body) {
  const bg = document.createElement('div');
  bg.className = 'modal-bg';
  render(bg, html`<div class="modal" role="dialog" aria-modal="true"><h3>${title}</h3>${body}<div class="row" style="margin-top:18px"><button class="btn primary" data-a="close">Done</button></div></div>`);
  bg.addEventListener('click', (e) => { if (e.target === bg || e.target.closest('[data-a="close"]')) bg.remove(); });
  document.body.appendChild(bg);
  return bg;
}

export function lightbox(src, rotation = 0) {
  let rot = rotation;
  const o = document.createElement('div');
  o.className = 'overlay';
  render(o, html`<img alt="Document photo" src="${src}" style="transform:rotate(${rot}deg)">
    <div class="bar"><button class="btn" data-a="rot">${icon.rotate} Rotate</button><a class="btn" href="${src}" target="_blank" rel="noopener">Open original</a><button class="btn" data-a="close">Close</button></div>`);
  const img = $('img', o);
  const close = () => { o.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  o.addEventListener('click', (e) => {
    const a = e.target.closest('[data-a]')?.dataset.a;
    if (a === 'rot') { rot = (rot + 90) % 360; img.style.transform = `rotate(${rot}deg)`; }
    if (a === 'close' || e.target === o) close();
  });
  document.addEventListener('keydown', onKey);
  document.body.appendChild(o);
}

export const debounce = (f, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => f(...a), ms); }; };

export const errorBox = (e, retry) => html`<div class="error-box">${e.message || String(e)} ${retry ? html`<button class="linkbtn" data-retry style="margin-left:8px">Try again</button>` : ''}</div>`;

export async function copyText(t) {
  try { await navigator.clipboard.writeText(t); toast('Copied'); } catch { toast('Could not copy. Select the text and copy it.', true); }
}

// Chip-style dropdown used by every filter panel.
export function chip({ id, label, icon: ic, value, options, placeholder = 'Any', disabled = false, title }) {
  const on = value !== '' && value != null;
  const has = options.some((o) => String(o.value) === String(value));
  const opts = [{ value: '', label: placeholder }, ...options];
  if (on && !has) opts.push({ value, label: '(not available)' });
  return html`<label class="chip ${on ? 'on' : ''} ${disabled ? 'dis' : ''}" for="${id}" ${title ? raw(`title="${esc(title)}"`) : ''}>${ic || ''}<span class="k">${label}</span>
    <select id="${id}" data-f="${id}" ${disabled ? 'disabled' : ''}>${opts.map((o) => html`<option value="${o.value}" ${String(o.value) === String(value ?? '') ? 'selected' : ''}>${o.label}</option>`)}</select></label>`;
}

// ---------- dropdowns ----------
// Native <select> lists can't be styled, so on desktop the list opens as our own
// popover. The <select> stays the source of truth: choosing an option sets its
// value and fires the usual change event, so every page works unchanged.
// Touch devices keep their own picker.

let dd = null; // { sel, pop, items, active, anchor }

function ddOptions(sel) {
  return [...sel.options].map((o, i) => ({ i, value: o.value, label: o.textContent, disabled: o.disabled, selected: o.selected }));
}

function ddClose(focus = true) {
  if (!dd) return;
  const { pop, sel, anchor } = dd;
  dd = null;
  anchor.classList.remove('dd-open');
  sel.setAttribute('aria-expanded', 'false');
  pop.classList.remove('show');
  setTimeout(() => pop.remove(), 150);
  if (focus) sel.focus({ preventScroll: true });
}

function ddChoose(i) {
  const { sel } = dd;
  const changed = sel.selectedIndex !== i;
  ddClose();
  if (!changed) return;
  sel.selectedIndex = i;
  sel.dispatchEvent(new Event('input', { bubbles: true }));
  sel.dispatchEvent(new Event('change', { bubbles: true }));
}

function ddSetActive(n, scroll = true) {
  const vis = dd.items.filter((li) => !li.hidden && !li.classList.contains('dis'));
  if (!vis.length) return;
  const idx = Math.max(0, Math.min(vis.length - 1, n));
  dd.items.forEach((li) => li.classList.remove('active'));
  vis[idx].classList.add('active');
  dd.active = vis[idx];
  if (scroll) vis[idx].scrollIntoView({ block: 'nearest' });
}

function ddMove(step) {
  const vis = dd.items.filter((li) => !li.hidden && !li.classList.contains('dis'));
  const cur = vis.indexOf(dd.active);
  ddSetActive(cur < 0 ? 0 : cur + step);
}

function ddPlace() {
  const { pop, anchor } = dd;
  const r = anchor.getBoundingClientRect();
  const width = Math.max(r.width, 180);
  pop.style.minWidth = `${width}px`;
  const h = pop.offsetHeight;
  const below = window.innerHeight - r.bottom;
  const up = below < h + 12 && r.top > below;
  pop.classList.toggle('up', up);
  pop.style.top = `${up ? Math.max(8, r.top - h - 6) : r.bottom + 6}px`;
  pop.style.left = `${Math.max(8, Math.min(r.left, window.innerWidth - pop.offsetWidth - 8))}px`;
}

function ddOpen(sel) {
  if (dd) ddClose(false);
  const opts = ddOptions(sel);
  const anchor = sel.closest('.chip') || sel;
  const pop = document.createElement('div');
  pop.className = 'dd-pop';
  pop.setAttribute('role', 'listbox');
  const search = opts.length > 9;
  render(pop, html`${search ? html`<input class="dd-search" type="text" placeholder="Search…" aria-label="Search options">` : ''}
    <ul>${opts.map((o) => html`<li role="option" data-i="${o.i}" aria-selected="${String(o.selected)}" class="${o.disabled ? 'dis' : ''} ${o.value === '' ? 'ph' : ''}">
      <svg class="ck" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg><span>${o.label}</span></li>`)}</ul>
    <div class="dd-empty" hidden>No matches</div>`);
  document.body.appendChild(pop);
  dd = { sel, pop, anchor, items: $$('li', pop), active: null };
  anchor.classList.add('dd-open');
  sel.setAttribute('aria-expanded', 'true');
  ddPlace();
  const cur = dd.items.find((li) => li.getAttribute('aria-selected') === 'true');
  const vis = dd.items.filter((li) => !li.classList.contains('dis'));
  ddSetActive(cur ? vis.indexOf(cur) : 0);
  void pop.offsetWidth; // start from the hidden state so the fade-in runs
  pop.classList.add('show');

  pop.addEventListener('mousedown', (e) => { if (!e.target.closest('.dd-search')) e.preventDefault(); });
  pop.addEventListener('click', (e) => {
    const li = e.target.closest('li');
    if (li && !li.classList.contains('dis')) ddChoose(Number(li.dataset.i));
  });
  pop.addEventListener('mousemove', (e) => {
    const li = e.target.closest('li');
    if (!li || li === dd?.active || li.classList.contains('dis')) return;
    dd.items.forEach((x) => x.classList.remove('active'));
    li.classList.add('active');
    dd.active = li;
  });
  if (search) {
    const inp = $('.dd-search', pop);
    inp.focus();
    inp.addEventListener('input', () => {
      const q = inp.value.trim().toLowerCase();
      let any = false;
      dd.items.forEach((li) => { li.hidden = q !== '' && !li.textContent.toLowerCase().includes(q); any = any || !li.hidden; });
      $('.dd-empty', pop).hidden = any;
      ddSetActive(0);
    });
    inp.addEventListener('keydown', ddKeys);
  }
}

function ddKeys(e) {
  if (!dd) return;
  switch (e.key) {
    case 'ArrowDown': e.preventDefault(); ddMove(1); break;
    case 'ArrowUp': e.preventDefault(); ddMove(-1); break;
    case 'Home': if (e.target.tagName !== 'INPUT') { e.preventDefault(); ddSetActive(0); } break;
    case 'End': if (e.target.tagName !== 'INPUT') { e.preventDefault(); ddSetActive(1e9); } break;
    case 'Enter': e.preventDefault(); if (dd.active) ddChoose(Number(dd.active.dataset.i)); break;
    case 'Escape': e.preventDefault(); e.stopPropagation(); ddClose(); break;
    case 'Tab': ddClose(false); break;
    default:
      // Type a letter to jump to the first matching option.
      if (e.key.length === 1 && e.target.tagName !== 'INPUT' && /\S/.test(e.key)) {
        const k = e.key.toLowerCase();
        const vis = dd.items.filter((li) => !li.hidden && !li.classList.contains('dis'));
        const n = vis.findIndex((li) => li.textContent.trim().toLowerCase().startsWith(k));
        if (n >= 0) ddSetActive(n);
      }
  }
}

const ddUsable = (sel) => sel && !sel.disabled && !sel.multiple && !sel.dataset.native && sel.closest('#app');

export function initDropdowns() {
  if (window.matchMedia('(pointer: coarse)').matches) return;
  document.addEventListener('mousedown', (e) => {
    // The whole chip (icon and label too) opens its list.
    const sel = e.target.closest('select') || (e.target.closest('.dd-pop') ? null : e.target.closest('.chip')?.querySelector('select'));
    if (ddUsable(sel) && e.button === 0) {
      e.preventDefault();
      sel.focus({ preventScroll: true });
      if (dd && dd.sel === sel) ddClose(); else ddOpen(sel);
      return;
    }
    if (dd && !e.target.closest('.dd-pop')) ddClose(false);
  }, true);
  document.addEventListener('keydown', (e) => {
    if (dd) { if (e.target === dd.sel || dd.pop.contains(e.target)) ddKeys(e); return; }
    const sel = e.target;
    if (sel.tagName !== 'SELECT' || !ddUsable(sel)) return;
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key) || (e.altKey && e.key === 'ArrowDown')) {
      e.preventDefault();
      ddOpen(sel);
    }
  }, true);
  window.addEventListener('resize', () => ddClose(false));
  document.addEventListener('scroll', (e) => { if (dd && !dd.pop.contains(e.target)) ddClose(false); }, true);
}

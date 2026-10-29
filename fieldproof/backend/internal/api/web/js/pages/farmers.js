import { api, html, render, $, $$, fmtInt, fmtDate, pill, DOCS, STATUS_LABEL, chip, icon, errorBox, qs, debounce, toast, payPill, plantPill, rupees } from '../lib.js';

// Filter keys kept in the URL, so a filtered list can be bookmarked, shared,
// reloaded, and returned to from a farmer's review page.
const KEYS = ['aadhaar', 'land', 'water', 'boundary', 'payment', 'plantation', 'state', 'district', 'block', 'village', 'ngo', 'surveyor', 'review', 'q', 'sort', 'page'];
// Changing a filter clears the ones that depend on it.
const CHILDREN = { state: ['district', 'block', 'village', 'ngo', 'surveyor'], district: ['block', 'village'], block: ['village'], ngo: ['surveyor'] };
const STATUS_OPTS = ['new', 'approved', 'pending', 'rejected', 'missing'].map((v) => ({ value: v, label: STATUS_LABEL[v] }));
const REVIEW_LABEL = { waiting: 'Waiting for review', sent_back: 'Sent back to surveyor', approved: 'Fully approved' };
const PAY_OPTS = [
  { value: 'waiting', label: 'To approve' }, { value: 'none', label: 'Not paid yet' }, { value: 'sent_back', label: 'Rejected or pending' },
  { value: 'approved', label: 'Approved' }, { value: 'locked', label: 'Onboarding not approved' },
];
const PLANT_OPTS = [
  { value: 'to_review', label: 'To review' }, { value: 'in_progress', label: 'In progress' }, { value: 'short', label: 'Delivery short' },
  { value: 'land_change', label: 'Land change' },
  { value: 'sent_back', label: 'Rejected or pending' }, { value: 'done', label: 'Approved' }, { value: 'locked', label: 'Not started' },
];
// Quick tabs above the filters: each sets one of the three status filters.
const TABS = [
  { key: 'all', label: 'All', count: 'farmers', set: {} },
  { key: 'onb', label: 'Onboarding to review', count: 'waiting', set: { review: 'waiting' } },
  { key: 'pay', label: 'Payment to approve', count: 'payments', set: { payment: 'waiting' } },
  { key: 'short', label: 'Delivery short', count: 'short', set: { plantation: 'short' } },
  { key: 'land', label: 'Land change', count: 'landChanges', set: { plantation: 'land_change' } },
  { key: 'plant', label: 'Plantation to review', count: 'plantations', set: { plantation: 'to_review' } },
];
const TAB_KEYS = ['review', 'payment', 'plantation'];

// One pill for the four onboarding documents.
export function onboardingPill(docs) {
  const vals = DOCS.map((d) => [d, docs[d.key]]);
  if (vals.every(([, s]) => s === 'approved')) return pill('approved');
  const which = (st) => vals.filter(([, s]) => s === st).map(([d]) => d.short.toLowerCase()).join(', ');
  for (const st of ['rejected', 'pending']) {
    if (which(st)) return html`<span class="pill p-${st}">${STATUS_LABEL[st]} · ${which(st)}</span>`;
  }
  if (which('new')) return html`<span class="pill p-new">To review</span>`;
  return html`<span class="pill p-missing">Not uploaded · ${which('missing')}</span>`;
}

function payExtra(x) {
  if (x.payment === 'waiting') return rupees(x.amountDue);
  if (x.payment === 'approved') return rupees(x.amountPaid);
  if ((x.payment === 'rejected' || x.payment === 'pending') && x.amountPaid < x.amountDue) return `${rupees(x.amountDue - x.amountPaid)} short`;
  return '';
}
const PAGE_SIZE = 25;

export function readFilters(query) {
  const f = {};
  KEYS.forEach((k) => { f[k] = query.get(k) || ''; });
  return f;
}

export async function downloadFile(url, fallbackName) {
  try {
    const res = await fetch(url, { credentials: 'same-origin' });
    if (!res.ok) {
      let msg = `Download failed (${res.status})`;
      try { msg = (await res.json()).message || msg; } catch { /* not json */ }
      toast(msg, true);
      return;
    }
    const blob = await res.blob();
    const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') || '')?.[1] || fallbackName;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  } catch {
    toast('Could not reach the server.', true);
  }
}

export default async function farmersPage({ el, app, query, navigate }) {
  const me = app.me;
  let f = readFilters(query);
  let seq = 0;
  let opts = null;
  let optsKey = null;

  const subtitle = me.role === 'ngo' ? `Onboarded by ${me.fullName}'s surveyors` : me.role === 'state_lead' ? `All farmers in ${me.state}` : 'All farmers in India';
  render(el, html`
    <div class="head">
      <div><h1>Farmers</h1><div class="sub">${subtitle} · open a farmer to review onboarding, payment and plantation</div></div>
      <div class="actions">
        <label class="sr" for="q">Search farmers</label>
        <input id="q" class="input search" type="search" placeholder="Search name, farmer ID, village or mobile" value="${f.q}" maxlength="100">
        <button class="btn" id="kml">Download KML</button>
        <button class="btn" id="csv">Download CSV</button>
      </div>
    </div>
    <div class="tabs" id="qtabs" role="tablist" aria-label="Quick views"></div>
    <div class="card fp" id="filters"><div class="loading" style="padding:18px">Loading filters…</div></div>
    <div class="card" id="list"><div class="loading">Loading farmers…</div></div>`);

  const listParams = () => {
    const p = {};
    KEYS.forEach((k) => { if (f[k] && k !== 'page') p[k] = f[k]; });
    return p;
  };
  const syncURL = (push) => {
    const url = '/dash/farmers' + qs({ ...listParams(), page: f.page && f.page !== '1' ? f.page : '' });
    if (push) history.pushState({}, '', url); else history.replaceState({}, '', url);
    sessionStorage.setItem('fp.farmersUrl', url);
  };
  sessionStorage.setItem('fp.farmersUrl', location.pathname + location.search);

  function filterPanel() {
    const o = opts || { states: [], districts: [], blocks: [], villages: [], ngos: [], surveyors: [] };
    const anyFilter = KEYS.some((k) => !['sort', 'page', 'q'].includes(k) && f[k]) || f.q;
    const showState = me.role === 'admin' && o.states.length > 0;
    return html`
      <div class="fr"><span class="gl">Document status</span><div class="chips">
        ${chip({ id: 'aadhaar', label: 'Aadhaar', icon: icon.id, value: f.aadhaar, options: STATUS_OPTS })}
        ${chip({ id: 'land', label: 'Land record', icon: icon.doc, value: f.land, options: STATUS_OPTS })}
        ${chip({ id: 'water', label: 'Water source', icon: icon.drop, value: f.water, options: STATUS_OPTS })}
        ${chip({ id: 'boundary', label: 'Boundary', icon: icon.map, value: f.boundary, options: STATUS_OPTS })}
      </div></div>
      <div class="fr"><span class="gl">Payment, plantation</span><div class="chips">
        ${chip({ id: 'payment', label: 'Payment', icon: icon.rupee, value: f.payment, options: PAY_OPTS })}
        ${chip({ id: 'plantation', label: 'Plantation step', icon: icon.sprout, value: f.plantation, options: PLANT_OPTS })}
      </div></div>
      <div class="fr"><span class="gl">Where and who</span><div class="chips">
        ${showState ? chip({ id: 'state', label: 'State', icon: icon.flag, value: f.state, options: o.states.map((s) => ({ value: s, label: s })), placeholder: 'All' }) : ''}
        ${chip({ id: 'district', label: 'District', icon: icon.pin, value: f.district, options: o.districts.map((d) => ({ value: d, label: d })), placeholder: 'All' })}
        ${chip({ id: 'block', label: 'Block', icon: icon.pin, value: f.block, options: o.blocks.map((b) => ({ value: b, label: b })), placeholder: f.district ? 'All' : 'Pick a district', disabled: !f.district, title: f.district ? '' : 'Choose a district first' })}
        ${chip({ id: 'village', label: 'Village', icon: icon.home, value: f.village, options: o.villages.map((v) => ({ value: v.id, label: v.name })), placeholder: f.block ? 'All' : 'Pick a block', disabled: !f.block, title: f.block ? '' : 'Choose a block first' })}
        ${me.role !== 'ngo' ? chip({ id: 'ngo', label: 'NGO', icon: icon.users, value: f.ngo, options: o.ngos.map((n) => ({ value: n.id, label: n.name })), placeholder: 'All' }) : ''}
        ${chip({ id: 'surveyor', label: 'Surveyor', icon: icon.user, value: f.surveyor, options: o.surveyors.map((s) => ({ value: s.id, label: s.name })), placeholder: 'All' })}
      </div></div>
      <div class="ff">
        <span id="count"><b>…</b> farmers match</span>
        ${f.review ? html`<span class="tagx">${REVIEW_LABEL[f.review] || f.review}<button data-clear="review" aria-label="Remove this filter">×</button></span>` : ''}
        <span class="right">
          <label class="muted" for="sort">Sort</label>
          <select id="sort" data-f="sort"><option value="">Newest first</option><option value="oldest" ${f.sort === 'oldest' ? 'selected' : ''}>Oldest first</option><option value="name" ${f.sort === 'name' ? 'selected' : ''}>Name A–Z</option></select>
          <button class="linkbtn" id="clear" ${anyFilter ? '' : 'disabled'}>Clear all</button>
        </span>
      </div>`;
  }

  function table(data) {
    const showState = me.role === 'admin';
    const showNgo = me.role !== 'ngo';
    if (!data.items.length) {
      const filtered = KEYS.some((k) => !['sort', 'page'].includes(k) && f[k]);
      if (data.total > 0 && f.page > 1) {
        return html`<div class="empty"><b>This page is empty</b><button class="btn" data-page="1">Go to the first page</button></div>`;
      }
      return filtered
        ? html`<div class="empty"><b>No farmers match these filters</b>Try removing one of them. <button class="linkbtn" id="clear2">Clear all filters</button></div>`
        : html`<div class="empty"><b>No farmers yet</b>A farmer appears once the surveyor finishes onboarding and the phone syncs.</div>`;
    }
    const page = Number(f.page || 1);
    const from = (page - 1) * PAGE_SIZE + 1;
    const to = from + data.items.length - 1;
    const last = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
    return html`<div class="tw"><table>
      <tr><th>Farmer</th><th>Village · Block</th>${showState ? html`<th>State</th>` : ''}<th>Surveyor${showNgo ? ' · NGO' : ''}</th><th>Area</th><th>Onboarding</th><th>Payment</th><th>Plantation</th><th>Saplings</th><th>Updated</th></tr>
      ${data.items.map((x) => html`<tr class="click" data-href="/dash/farmers/${x.id}">
        <td><a href="/dash/farmers/${x.id}"><b>${x.fullName}</b></a><div class="code">${x.code}</div></td>
        <td>${x.village} · ${x.block}</td>${showState ? html`<td>${x.state || '—'}</td>` : ''}<td>${x.surveyor}${showNgo && x.ngo ? html`<div class="code">${x.ngo}</div>` : ''}</td>
        <td>${x.acres.toFixed(2)} ac</td><td>${onboardingPill(x.docs)}</td><td>${payPill(x.payment, payExtra(x))}</td>
        <td>${plantPill(x.plantation, x.plantation === 'short' && x.counted < x.saplings ? fmtInt(x.saplings - x.counted) : '')}</td>
        <td>${x.plantation === 'short' ? `${fmtInt(x.counted)} of ${fmtInt(x.saplings)}` : fmtInt(x.saplings)}</td>
        <td class="muted">${fmtDate(x.updatedAt || x.submittedAt)}</td></tr>`)}
    </table></div>
    <div class="pager"><span>${fmtInt(from)}–${fmtInt(to)} of ${fmtInt(data.total)}</span>
      <span class="actions"><button class="btn sm" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>Previous</button><span>Page ${page} of ${last}</span><button class="btn sm" data-page="${page + 1}" ${page >= last ? 'disabled' : ''}>Next</button></span></div>`;
  }

  let counts = null;
  function tabs() {
    const active = TABS.find((t) => TAB_KEYS.every((k) => (f[k] || '') === (t.set[k] || '')));
    return html`${TABS.map((t) => html`<button class="tab ${active === t ? 'on' : ''}" role="tab" aria-selected="${active === t}" data-tab="${t.key}">${t.label}${counts ? ` · ${fmtInt(counts[t.count])}` : ''}</button>`)}`;
  }
  const drawTabs = () => render($('#qtabs', el), tabs());
  async function loadCounts() {
    try { counts = await api('/v1/dash/counts'); } catch { counts = null; }
    drawTabs();
  }

  async function load() {
    const my = ++seq;
    drawTabs();
    const key = JSON.stringify([f.state, f.district, f.block, f.ngo]);
    const optsReq = key === optsKey && opts ? Promise.resolve(opts) : api('/v1/dash/options' + qs({ state: f.state, district: f.district, block: f.block, ngo: f.ngo }));
    const listReq = api('/v1/dash/farmers' + qs({ ...listParams(), page: f.page || 1, size: PAGE_SIZE }));
    $('#list', el).style.opacity = '.55';
    try {
      const [o, data] = await Promise.all([optsReq, listReq]);
      if (my !== seq) return; // a newer filter change is on its way
      opts = o; optsKey = key;
      render($('#filters', el), filterPanel());
      render($('#list', el), table(data));
      $('#count', el).innerHTML = `<b>${fmtInt(data.total)}</b> farmer${data.total === 1 ? '' : 's'} match`;
    } catch (e) {
      if (my !== seq) return;
      render($('#list', el), errorBox(e, true));
      if (!opts) render($('#filters', el), filterPanel());
    } finally {
      if (my === seq) $('#list', el).style.opacity = '';
    }
  }

  function set(key, value, push = true) {
    f[key] = value;
    (CHILDREN[key] || []).forEach((c) => { f[c] = ''; });
    if (key !== 'page') f.page = '';
    syncURL(push);
    load();
  }

  el.addEventListener('change', (e) => {
    const k = e.target.dataset.f;
    if (k) set(k, e.target.value);
  });
  el.addEventListener('click', (e) => {
    const t = e.target;
    if (t.closest('#clear') || t.closest('#clear2')) {
      KEYS.forEach((k) => { if (k !== 'sort') f[k] = ''; });
      $('#q', el).value = '';
      syncURL(true);
      load();
      return;
    }
    const c = t.closest('[data-clear]');
    if (c) { set(c.dataset.clear, ''); return; }
    const tb = t.closest('[data-tab]');
    if (tb) {
      const tab = TABS.find((x) => x.key === tb.dataset.tab);
      TAB_KEYS.forEach((k) => { f[k] = tab.set[k] || ''; });
      f.page = '';
      syncURL(true);
      load();
      return;
    }
    const pg = t.closest('[data-page]');
    if (pg && !pg.disabled) { set('page', pg.dataset.page === '1' ? '' : pg.dataset.page); window.scrollTo(0, 0); return; }
    if (t.closest('[data-retry]')) { load(); return; }
    const tr = t.closest('tr[data-href]');
    if (tr && !t.closest('a')) navigate(tr.dataset.href);
  });
  const onSearch = debounce((v) => { if (v !== f.q) set('q', v, false); }, 350);
  $('#q', el).addEventListener('input', (e) => onSearch(e.target.value.trim()));
  $('#kml', el).addEventListener('click', () => downloadFile('/v1/dash/farmers.kml' + qs(listParams()), 'fieldproof-farmers.kml'));
  $('#csv', el).addEventListener('click', () => downloadFile('/v1/dash/farmers.csv' + qs(listParams()), 'fieldproof-farmers.csv'));

  // Browser back/forward re-runs the page from the URL (see app.js), so filters come back too.
  loadCounts();
  await load();
}

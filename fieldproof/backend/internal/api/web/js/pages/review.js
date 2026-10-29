import { api, html, raw, render, $, $$, pill, DOCS, STATUS_LABEL, icon, errorBox, fmtDateTime, fmtDate, acres, phone, lightbox, toast, esc, payPill, plantPill, rupees } from '../lib.js';
import { downloadFile, onboardingPill } from './farmers.js';
import { paymentTab, plantationTab, plantLayers, saveBar as planSaveBar, MAX_MSG as PLAN_MAX } from './plan.js';
import { refreshCounts } from '../app.js';

const MAX_MSG = 1000;
const DECISIONS = [
  { value: 'approved', label: 'Approve' },
  { value: 'rejected', label: 'Reject' },
  { value: 'pending', label: 'Pending' },
];
const EVENT_TEXT = { approved: 'approved', rejected: 'rejected', pending: 'marked Pending', reuploaded: 'sent a new' };
const DOC_NAME = Object.fromEntries(DOCS.map((d) => [d.key, d.label]));

export default async function reviewPage(ctx) {
  const { el, params, setGuard } = ctx;
  const id = params.id;
  render(el, html`<div class="loading">Loading farmer…</div>`);
  let f;
  try {
    f = await api(`/v1/dash/farmers/${encodeURIComponent(id)}`);
  } catch (e) {
    if (e.status === 404) {
      render(el, html`<a class="crumb" href="${backUrl()}">${icon.back}Farmers</a><div class="card"><div class="empty"><b>Farmer not found</b>This farmer doesn't exist, hasn't finished onboarding, or isn't in your area.</div></div>`);
    } else {
      render(el, errorBox(e, true));
      $('[data-retry]', el)?.addEventListener('click', () => reviewPage(ctx));
    }
    return;
  }

  // draft[doc] = { decision, message } as shown on screen; starts from the saved state.
  const draft = {};
  const initial = {};
  for (const d of DOCS) {
    const cur = f.docs[d.key];
    const start = ['approved', 'rejected', 'pending'].includes(cur.status) ? { decision: cur.status, message: cur.status === 'approved' ? '' : cur.message } : { decision: '', message: '' };
    draft[d.key] = { ...start };
    initial[d.key] = { ...start };
  }
  const rot = {};
  let tried = false;
  let saving = false;
  const changed = () => DOCS.filter((d) => draft[d.key].decision && (draft[d.key].decision !== initial[d.key].decision || draft[d.key].message.trim() !== initial[d.key].message.trim()));

  // Payment and plantation decisions, same rules as the documents.
  const TABS = ['onboarding', 'payment', 'plantation'];
  let tab = TABS.includes(new URLSearchParams(location.search).get('tab')) ? new URLSearchParams(location.search).get('tab') : defaultTab(f);
  const ui = { draft: {}, initial: {}, tried: { payment: false, plantation: false, land: false }, saving: false, rot, proofIdx: null };
  const planStart = () => {
    const pay = f.plan.pay, pl = f.plan.plant;
    const st = (s, m) => (['approved', 'rejected', 'pending'].includes(s) ? { decision: s, message: s === 'approved' ? '' : m || '' } : { decision: '', message: '' });
    const lc = pl?.landChange;
    ui.initial = { payment: st(pay?.status, pay?.message), plantation: st(pl?.reviewStatus, pl?.reviewMessage), land: st(lc?.status === 'pending' ? 'pending' : '', lc?.message) };
    ui.draft = { payment: { ...ui.initial.payment }, plantation: { ...ui.initial.plantation }, land: { ...ui.initial.land } };
  };
  planStart();
  ui.changed = (sec) => !!ui.draft[sec].decision && (ui.draft[sec].decision !== ui.initial[sec].decision || ui.draft[sec].message.trim() !== ui.initial[sec].message.trim());
  setGuard(() => (changed().length || ui.changed('payment') || ui.changed('plantation') || ui.changed('land') ? 'You have review decisions that are not saved yet.' : null));

  const files = f.docFiles || [];
  const latest = (kinds, landId) => files.find((x) => kinds.includes(x.kind) && (landId === undefined || !x.landId || x.landId === landId));
  const older = (kinds, shown) => files.filter((x) => kinds.includes(x.kind) && !shown.includes(x.id));

  const shot = (file, caption) => {
    if (!file) return html`<div class="shot none">${caption} · not uploaded</div>`;
    if (file.mime === 'application/pdf') return html`<div class="shot none pdf"><a href="${file.url}" target="_blank" rel="noopener">Open ${caption} (PDF)</a></div>`;
    return html`<div class="shot"><img src="${file.url}" alt="${caption}" data-file="${file.id}" style="transform:rotate(${rot[file.id] || 0}deg)" loading="lazy">
      <button class="rot" data-rot="${file.id}" title="Rotate" aria-label="Rotate ${caption}">${icon.rotate}</button><span class="cap">${caption} · ${fmtDateTime(file.capturedAt || file.uploadedAt)}</span></div>`;
  };
  const stamp = (file) => (file && file.lat != null ? `${file.lat.toFixed(5)}, ${file.lng.toFixed(5)} · ${fmtDateTime(file.capturedAt)}${file.accuracy ? ` · ±${Math.round(file.accuracy)} m` : ''}` : '—');
  const olderList = (list) => (list.length ? html`<details class="older"><summary>Earlier uploads (${list.length})</summary>
    <div class="shots">${list.map((x) => shot(x, `${x.kind.replace(/_/g, ' ')} · earlier`))}</div></details>` : '');

  function decisionBlock(key) {
    const cur = f.docs[key];
    const d = draft[key];
    const needMsg = d.decision === 'rejected' || d.decision === 'pending';
    const err = tried && needMsg && !d.message.trim();
    const missing = cur.status === 'missing';
    return html`<div>
      <div class="dec-lbl">Decision</div>
      <div class="decide" role="group" aria-label="Decision for ${DOC_NAME[key]}">
        ${DECISIONS.map((x) => html`<button data-doc="${key}" data-dec="${x.value}" class="${d.decision === x.value ? `on-${x.value}` : ''}" aria-pressed="${d.decision === x.value}"
          ${x.value === 'approved' && missing ? raw('disabled title="Nothing was uploaded, so it can\'t be approved"') : ''}>${x.label}</button>`)}
      </div>
      ${needMsg ? html`<div class="field ${err ? 'err' : ''}" style="margin-top:12px">
        <label class="lbl" for="m-${key}">Message to the surveyor <span style="color:var(--red)">(required)</span></label>
        <textarea id="m-${key}" data-msg="${key}" maxlength="${MAX_MSG}" placeholder="What is wrong and what should they do?">${d.message}</textarea>
        <div class="hint ${err ? 'bad' : ''}" data-hint="${key}">${err ? 'Write a message: the surveyor needs to know what to fix.' : `${d.message.length}/${MAX_MSG}`}</div>
      </div>` : ''}
      ${cur.updatedAt && cur.status !== 'new' && cur.status !== 'missing' ? html`<div class="lastdec"><b>${STATUS_LABEL[cur.status]}</b> by ${cur.reviewer || 'a reviewer'} · ${fmtDateTime(cur.updatedAt)}${cur.message ? html`<br>“${cur.message}”` : ''}</div>` : ''}
      ${cur.status === 'new' && cur.updatedAt ? html`<div class="lastdec">The surveyor sent a new upload after it was sent back. Review it again.</div>` : ''}
    </div>`;
  }

  function aadhaarCard() {
    const fr = latest(['aadhaar_front']), bk = latest(['aadhaar_back']);
    return html`<div class="card doc" id="doc-aadhaar"><div>
      <h3>Aadhaar card ${pill(f.docs.aadhaar.status)}</h3>
      <div class="shots">${shot(fr, 'Front')}${shot(bk, 'Back')}</div>
      <div class="facts"><div><span>Name matches</span>${f.aadhaarNameMatch ? 'Yes, ticked by surveyor' : 'Not ticked'}</div>
        <div><span>KYC consent</span>${f.aadhaarConsent ? 'Given' : 'Not given'}</div>
        <div><span>Front stamped</span>${stamp(fr)}</div><div><span>Back stamped</span>${stamp(bk)}</div></div>
      ${olderList(older(['aadhaar_front', 'aadhaar_back'], [fr?.id, bk?.id]))}
    </div>${decisionBlock('aadhaar')}</div>`;
  }

  function landCard() {
    const shown = [];
    const lands = f.lands || [];
    return html`<div class="card doc" id="doc-land_record"><div>
      <h3>Land record (7/12) ${pill(f.docs.land_record.status)}</h3>
      ${lands.length ? lands.map((l, i) => {
        const ph = latest(['land_record'], l.id), pdf = latest(['land_record_pdf'], l.id);
        shown.push(ph?.id, pdf?.id);
        return html`${lands.length > 1 ? html`<div style="margin-top:14px;font-weight:600">Land ${i + 1}</div>` : ''}
          <div class="shots">${shot(ph, 'Page photo')}${pdf ? shot(pdf, '7/12') : html`<div class="shot none">No PDF attached</div>`}</div>
          <div class="facts"><div><span>Survey / Gat no.</span>${l.surveyNo}</div><div><span>Area on 7/12</span>${l.areaHa.toFixed(2)} ha · ${(l.areaHa * 2.4710538).toFixed(2)} ac</div>
            <div><span>Registering</span>${l.registerAcres.toFixed(2)} ac</div><div><span>Owner</span>${l.ownerType === 'family' ? 'Family member' : 'Farmer'}${l.recordsConsent ? ' · records consent given' : ''}</div></div>`;
      }) : html`<div class="shots">${shot(latest(['land_record']), 'Page photo')}</div>`}
      ${olderList(older(['land_record', 'land_record_pdf'], shown))}
    </div>${decisionBlock('land_record')}</div>`;
  }

  function waterCard() {
    const ph = latest(['water_source']);
    const src = f.farmDetails?.waterSources || [];
    return html`<div class="card doc" id="doc-water_source"><div>
      <h3>Water source photo ${pill(f.docs.water_source.status)}</h3>
      <div class="shots">${shot(ph, src.length ? src.join(', ') : 'Water source')}</div>
      <div class="facts"><div><span>Source</span>${src.length ? src.map(cap).join(', ') : '—'}</div><div><span>Stamped</span>${stamp(ph)}</div></div>
      ${olderList(older(['water_source'], [ph?.id]))}
    </div>${decisionBlock('water_source')}</div>`;
  }

  function boundaryCard() {
    const bs = f.boundaries || [];
    const walked = bs.reduce((a, b) => a + b.areaM2, 0);
    const record = (f.lands || []).reduce((a, l) => a + l.areaHa * 10000, 0);
    const diff = record > 0 && walked > 0 ? Math.round(((walked - record) / record) * 100) : null;
    const pts = bs.reduce((a, b) => a + b.pointCount, 0);
    const acc = bs.length ? Math.max(...bs.map((b) => b.accuracyM || 0)) : 0;
    const dist = bs.reduce((a, b) => a + (b.walkedM || 0), 0);
    return html`<div class="card doc" id="doc-boundary"><div>
      <h3>Farm boundary (KML) ${pill(f.docs.boundary.status)}</h3>
      ${bs.length ? html`<div class="map" id="map" aria-label="Walked boundary on a satellite map"></div>
        <div class="facts"><div><span>Walked area</span>${acres(walked).toFixed(2)} ac · ${Math.round(walked).toLocaleString('en-IN')} m²</div>
          <div><span>Land record</span>${record ? `${acres(record).toFixed(2)} ac` : '—'}${diff != null ? html` · <b style="color:${Math.abs(diff) > 15 ? 'var(--amber-d)' : 'var(--green-text)'}">${diff > 0 ? `${diff}% more` : diff < 0 ? `${-diff}% less` : 'same'}</b>` : ''}</div>
          <div><span>Points · GPS accuracy</span>${pts} · ±${Math.round(acc)} m</div><div><span>Walked distance</span>${Math.round(dist)} m${bs.length > 1 ? ` · ${bs.length} parcels` : ''}</div></div>
        <div style="margin-top:12px"><button class="linkbtn" id="kml">Download KML</button></div>`
      : html`<div class="shots"><div class="shot none">No boundary walked yet</div></div>`}
    </div>${decisionBlock('boundary')}</div>`;
  }

  function saveBar() {
    const ch = changed();
    const tasks = ch.filter((d) => ['rejected', 'pending'].includes(draft[d.key].decision)).map((d) => d.short.toLowerCase());
    const approves = ch.filter((d) => draft[d.key].decision === 'approved').map((d) => d.short.toLowerCase());
    let text;
    if (!ch.length) text = html`Choose Approve, Reject or Pending for a document, then save.`;
    else {
      const parts = [];
      if (approves.length) parts.push(html`approves <b>${approves.join(', ')}</b>`);
      if (tasks.length) parts.push(html`sends one task to <b>${f.surveyor.name}</b>, who onboarded this farmer, for: <b>${tasks.join(', ')}</b>. No other surveyor sees it`);
      text = html`Saving ${parts.map((p, i) => html`${i ? '; ' : ''}${p}`)}.`;
    }
    return html`<span class="small" style="flex:1;min-width:240px;color:#3A3A3C">${text}</span>
      <span class="actions">${ch.length ? html`<button class="btn" id="undo">Undo changes</button>` : ''}<button class="btn primary big" id="save" ${!ch.length || saving ? 'disabled' : ''}>${saving ? 'Saving…' : 'Save review'}</button></span>`;
  }

  function history() {
    const ev = f.history || [];
    return html`<div class="card"><div class="ch"><h2>History</h2></div><div class="pad"><ul class="history">
      <li>${fmtDateTime(f.submittedAt || f.createdAt)} · <b>${f.surveyor.name}</b> finished onboarding${f.ngo.name ? ` (${f.ngo.name})` : ''}.</li>
      ${ev.map((e) => html`<li>${fmtDateTime(e.at)} · <b>${e.by || 'Someone'}</b> ${EVENT_TEXT[e.event] || e.event} ${e.event === 'reuploaded' ? DOC_NAME[e.doc]?.toLowerCase() : DOC_NAME[e.doc]}${e.message ? html`: “${e.message}”` : ''}</li>`)}
    </ul></div></div>`;
  }

  function tabs() {
    const p = f.plan;
    const payExtra = p.pay && p.payment === 'approved' ? rupees(p.pay.amountPaid) : p.payment === 'waiting' && p.pay ? rupees(p.pay.amountDue) : '';
    const t = (key, label, badge) => html`<a role="tab" class="ftab ${tab === key ? 'on' : ''}" aria-selected="${tab === key}" href="?tab=${key}" data-tab="${key}">${label} ${badge}</a>`;
    return html`<div class="ftabs" role="tablist" aria-label="Farmer sections">
      ${t('onboarding', 'Onboarding', onboardingPill(Object.fromEntries(DOCS.map((d) => [d.key, f.docs[d.key].status]))))}
      ${t('payment', 'Payment', payPill(p.payment, payExtra))}
      ${t('plantation', 'Plantation', plantPill(p.plantation))}
      <span role="tab" aria-selected="false" aria-disabled="true" class="ftab locked" title="Audit is planned for later">Audit ${icon.lock}</span>
    </div>`;
  }

  function page() {
    const v = f.village;
    const fd = f.farmDetails;
    let body;
    if (tab === 'payment') body = paymentTab(f, ui);
    else if (tab === 'plantation') body = plantationTab(f, ui);
    else {
      body = html`${aadhaarCard()}${landCard()}${waterCard()}${boundaryCard()}
        <div class="savebar" id="savebar">${saveBar()}</div>
        ${history()}`;
    }
    render(el, html`
      <a class="crumb" href="${backUrl()}">${icon.back}Farmers</a>
      <div class="head">
        <div><h1>${f.fullName} <span class="muted" style="font-size:20px;font-weight:600">${f.code}</span></h1>
          <div class="sub">${v.name}, ${v.block}, ${v.district}${f.state ? `, ${f.state}` : ''} · onboarded ${fmtDate(f.submittedAt)} by <b style="color:var(--text)">${f.surveyor.name}</b>${f.ngo.name ? ` (${f.ngo.name})` : ''} · mobile ${phone(f.mobile)}${mapsLink(f)}</div></div>
        ${fd && tab === 'onboarding' ? html`<div class="card pad small" style="max-width:420px"><b>Farm details:</b> ${(fd.waterSources || []).map(cap).join(', ')}${fd.timberSpecies?.length ? ` · ${[...fd.timberSpecies, ...(fd.fruitSpecies || [])].map(cap).join(', ')}` : ''} · ${fd.timberPercent}/${fd.fruitPercent} timber/fruit</div>` : ''}
      </div>
      ${tabs()}
      ${body}`);
    const box = $('#map', el);
    if (box) mapBoundaries(box, f.boundaries || []);
    const pbox = $('#plantmap', el);
    if (pbox) mapLayers(pbox, plantLayers(f));
  }

  const updatePlanBar = (sec) => {
    const bar = $(`#savebar-${sec}`, el);
    if (bar) render(bar, planSaveBar(sec, ui.draft[sec], ui.changed(sec), ui.saving, f.surveyor.name, f.plan.plant?.landChange?.surveyNo || ''));
  };

  async function savePlan(sec) {
    const d = ui.draft[sec];
    if (!ui.changed(sec) || ui.saving) return;
    if (d.decision !== 'approved' && !d.message.trim()) {
      ui.tried[sec] = true;
      page();
      $(`#pm-${sec}`, el)?.focus();
      toast('Reject and Pending need a message to the surveyor.', true);
      return;
    }
    ui.saving = true;
    updatePlanBar(sec);
    try {
      const res = await api(`/v1/dash/farmers/${f.id}/${sec === 'land' ? 'land-change' : sec}-review`, { method: 'POST', body: { decision: d.decision, message: d.message.trim() } });
      f = res.farmer;
      ui.saving = false;
      ui.tried[sec] = false;
      planStart();
      const who = f.surveyor.name;
      toast(d.decision === 'approved'
        ? (sec === 'payment' ? `Payment approved. ${who}'s phone now shows the plantation steps.` : sec === 'land' ? `New land approved. ${who} can go ahead with planting.` : 'Plantation approved. This farmer is complete.')
        : `Saved. Task sent to ${who}'s phone.`);
      page();
      refreshCounts();
    } catch (ex) {
      ui.saving = false;
      updatePlanBar(sec);
      toast(ex.message, true);
    }
  }

  const updateBar = () => render($('#savebar', el), saveBar());

  function rerenderDoc(key) {
    const card = $(`#doc-${key}`, el);
    const block = card.lastElementChild;
    const tmp = document.createElement('div');
    render(tmp, decisionBlock(key));
    block.replaceWith(tmp.firstElementChild);
    updateBar();
  }

  el.addEventListener('click', async (e) => {
    const t = e.target;
    const ft = t.closest('[data-tab]');
    if (ft) {
      e.preventDefault();
      if (ft.dataset.tab === tab) return;
      tab = ft.dataset.tab;
      window.history.replaceState({}, '', `${location.pathname}?tab=${tab}`);
      page();
      return;
    }
    const pd = t.closest('[data-pd]');
    if (pd) {
      const sec = pd.dataset.pd;
      ui.draft[sec].decision = ui.draft[sec].decision === pd.dataset.v ? '' : pd.dataset.v;
      if (ui.draft[sec].decision === 'approved') ui.draft[sec].message = '';
      page();
      $(`#pm-${sec}`, el)?.focus();
      return;
    }
    const pu = t.closest('[data-pundo]');
    if (pu) { const sec = pu.dataset.pundo; ui.draft[sec] = { ...ui.initial[sec] }; ui.tried[sec] = false; page(); return; }
    const ps = t.closest('[data-psave]');
    if (ps && !ps.disabled) { await savePlan(ps.dataset.psave); return; }
    const pr = t.closest('[data-proof]');
    if (pr) { ui.proofIdx = Number(pr.dataset.proof); page(); return; }
    const op = t.closest('[data-open]');
    if (op) { const im = $(`img[data-file="${op.dataset.open}"]`, el); if (im) lightbox(im.src, rot[op.dataset.open] || 0); return; }
    if (t.closest('#kml-all')) { downloadFile(`/v1/dash/farmers/${f.id}/kml?walks=all`, `${f.code}-all-walks.kml`); return; }
    const b = t.closest('[data-dec]');
    if (b && !b.disabled) {
      const key = b.dataset.doc;
      // Clicking the chosen decision again clears it.
      draft[key].decision = draft[key].decision === b.dataset.dec ? '' : b.dataset.dec;
      if (draft[key].decision === 'approved') draft[key].message = '';
      rerenderDoc(key);
      $(`#m-${key}`, el)?.focus();
      return;
    }
    const r = t.closest('[data-rot]');
    if (r) {
      const fid = r.dataset.rot;
      rot[fid] = ((rot[fid] || 0) + 90) % 360;
      $$(`img[data-file="${fid}"]`, el).forEach((img) => { img.style.transform = `rotate(${rot[fid]}deg)`; });
      return;
    }
    const img = t.closest('img[data-file]');
    if (img) { lightbox(img.src, rot[img.dataset.file] || 0); return; }
    if (t.closest('#kml')) { downloadFile(`/v1/dash/farmers/${f.id}/kml`, `${f.code}-boundary.kml`); return; }
    if (t.closest('#undo')) {
      DOCS.forEach((d) => { draft[d.key] = { ...initial[d.key] }; });
      tried = false;
      page();
      return;
    }
    if (t.closest('#save')) await save();
  });
  el.addEventListener('input', (e) => {
    const sec = e.target.dataset.pmsg;
    if (sec) {
      ui.draft[sec].message = e.target.value;
      const hint = $(`[data-phint="${sec}"]`, el);
      const err = ui.tried[sec] && !e.target.value.trim();
      e.target.closest('.field').classList.toggle('err', err);
      hint.className = `hint ${err ? 'bad' : ''}`;
      hint.textContent = err ? 'Write a message: the surveyor needs to know what to fix.' : `${e.target.value.length}/${PLAN_MAX}`;
      updatePlanBar(sec);
      return;
    }
    const key = e.target.dataset.msg;
    if (!key) return;
    draft[key].message = e.target.value;
    const hint = $(`[data-hint="${key}"]`, el);
    const err = tried && !e.target.value.trim();
    e.target.closest('.field').classList.toggle('err', err);
    hint.className = `hint ${err ? 'bad' : ''}`;
    hint.textContent = err ? 'Write a message: the surveyor needs to know what to fix.' : `${e.target.value.length}/${MAX_MSG}`;
    updateBar();
  });

  async function save() {
    const ch = changed();
    if (!ch.length || saving) return;
    const missingMsg = ch.filter((d) => ['rejected', 'pending'].includes(draft[d.key].decision) && !draft[d.key].message.trim());
    if (missingMsg.length) {
      tried = true;
      missingMsg.forEach((d) => rerenderDoc(d.key));
      $(`#m-${missingMsg[0].key}`, el)?.focus();
      $(`#doc-${missingMsg[0].key}`, el)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      toast('Reject and Pending need a message to the surveyor.', true);
      return;
    }
    saving = true;
    updateBar();
    const decisions = {};
    ch.forEach((d) => { decisions[d.key] = { decision: draft[d.key].decision, message: draft[d.key].message.trim() }; });
    try {
      const res = await api(`/v1/dash/farmers/${f.id}/review`, { method: 'POST', body: { decisions } });
      f = res.farmer;
      for (const d of DOCS) {
        const cur = f.docs[d.key];
        const start = ['approved', 'rejected', 'pending'].includes(cur.status) ? { decision: cur.status, message: cur.status === 'approved' ? '' : cur.message } : { decision: '', message: '' };
        draft[d.key] = { ...start };
        initial[d.key] = { ...start };
      }
      tried = false;
      saving = false;
      const names = (res.tasksSent || []).map((k) => DOC_NAME[k].toLowerCase());
      toast(names.length ? `Saved. Task sent to ${res.surveyor}'s phone for: ${names.join(', ')}.` : 'Saved.');
      page();
      refreshCounts();
    } catch (ex) {
      saving = false;
      updateBar();
      toast(ex.message, true);
    }
  }

  page();
}

// Where the onboarding walk started (usually the field entrance), in Google Maps.
function mapsLink(f) {
  const pt = (f.boundaries || [])[0]?.points?.[0];
  if (!pt) return '';
  return html` · <a href="https://www.google.com/maps/search/?api=1&amp;query=${pt.lat.toFixed(6)},${pt.lng.toFixed(6)}" target="_blank" rel="noopener">Open in Google Maps</a>`;
}

// Open the tab that needs a decision; otherwise the furthest step reached.
function defaultTab(f) {
  const p = f.plan;
  if (p.payment === 'waiting') return 'payment';
  if (p.plantation === 'to_review' || p.plantation === 'land_review') return 'plantation';
  if (Object.values(f.docs).some((d) => d.status === 'new')) return 'onboarding';
  if (p.plantation !== 'locked') return 'plantation';
  if (p.onboardingOk) return 'payment';
  return 'onboarding';
}

function backUrl() {
  const u = sessionStorage.getItem('fp.farmersUrl');
  return u && u.startsWith('/dash/farmers') ? u : '/dash/farmers';
}

const cap = (s) => String(s).replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());

// ---------- map ----------

let leafletLoading;
function loadLeaflet() {
  if (window.L) return Promise.resolve(window.L);
  if (!leafletLoading) {
    leafletLoading = new Promise((resolve, reject) => {
      const css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
      document.head.appendChild(css);
      const s = document.createElement('script');
      s.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
      s.onload = () => resolve(window.L);
      s.onerror = () => { leafletLoading = null; reject(new Error('map library did not load')); };
      document.head.appendChild(s);
    });
  }
  return leafletLoading;
}

// The review page calls this after render with the farmer's boundaries.
export function mapBoundaries(box, boundaries) {
  mapLayers(box, boundaries.map((b) => ({ ring: b.points.map((p) => [p.lat, p.lng]), style: { color: '#ffffff', weight: 2, fillColor: '#2e7d32', fillOpacity: 0.35 } })));
}

// layers: [{ ring: [[lat, lng], …], style: Leaflet path options }]
export function mapLayers(box, layers) {
  layers = layers.filter((l) => l.ring.length > 2);
  if (!layers.length) { box.innerHTML = '<div class="empty">No boundary walked yet</div>'; return; }
  const rings = layers.map((l) => l.ring);
  loadLeaflet().then((L) => {
    if (!box.isConnected) return;
    const map = L.map(box, { scrollWheelZoom: false });
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 20, maxNativeZoom: 19, attribution: 'Imagery © Esri' }).addTo(map);
    layers.forEach((l) => L.polygon(l.ring, l.style).addTo(map));
    map.fitBounds(rings.flat(), { padding: [24, 24] });
  }).catch(() => {
    // Offline or blocked: draw the outline without imagery.
    const all = rings.flat();
    const minLat = Math.min(...all.map((p) => p[0])), maxLat = Math.max(...all.map((p) => p[0]));
    const minLng = Math.min(...all.map((p) => p[1])), maxLng = Math.max(...all.map((p) => p[1]));
    const k = Math.cos(((minLat + maxLat) / 2) * Math.PI / 180);
    const w = (maxLng - minLng) * k || 1e-6, h = maxLat - minLat || 1e-6;
    const sc = Math.min(560 / w, 220 / h);
    const pts = (r) => r.map((p) => `${20 + (p[1] - minLng) * k * sc},${240 - 20 - (p[0] - minLat) * sc}`).join(' ');
    const svgStyle = (s) => `fill="${s.fill === false ? 'none' : s.fillColor || '#5B8C4A'}" fill-opacity="${s.fillOpacity ?? 1}" stroke="${s.color}" stroke-width="${s.weight}" stroke-dasharray="${s.dashArray || ''}"`;
    box.innerHTML = `<svg viewBox="0 0 600 240" width="100%" height="100%" style="background:#6E8A4E">${layers.map((l) => `<polygon points="${esc(pts(l.ring))}" ${svgStyle(l.style)}/>`).join('')}</svg>`;
  });
}

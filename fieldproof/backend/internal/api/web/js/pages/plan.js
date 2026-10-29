import { html, icon, fmtInt, fmtDate, fmtDateTime, acres, phone, rupees } from '../lib.js';

// Payment and Plantation tabs of the farmer page. review.js owns the page,
// the drafts and the clicks; these functions only draw.

export const MAX_MSG = 1000;
const DECISIONS = [
  { value: 'approved', label: 'Approve' },
  { value: 'rejected', label: 'Reject' },
  { value: 'pending', label: 'Pending' },
];
const PHOTO_LABEL = { sapling_delivery: 'Saplings on tractor', plant_pits: 'Pits', plant_planted: 'Planted' };
const STATUS_TEXT = { waiting: 'Waiting for approval', approved: 'Approved', rejected: 'Rejected', pending: 'Pending' };
const STATUS_CLASS = { waiting: 'new', approved: 'approved', rejected: 'rejected', pending: 'pending' };

// What the phone is waiting for at each plantation step.
const STAGE_NOTE = {
  pre_walk: (s) => html`Payment approved. Waiting for <b>${s}</b> to walk the boundary just before planting.`,
  delivery: (s) => html`Waiting for the saplings to reach the field. <b>${s}</b> records the farmer's count and quality check.`,
  short: (s) => html`The farmer did not accept the delivery. The nursery has to send the missing saplings; then <b>${s}</b> counts again with the farmer.`,
  otp: (s) => html`The farmer counted the saplings and is satisfied. Waiting for <b>${s}</b> to enter the OTP sent to the farmer's mobile.`,
  planting: (s) => html`The farmer confirmed with the OTP. Pit digging and planting is under way; <b>${s}</b> adds photos and marks it done.`,
  post_walk: (s) => html`Planting is done. Waiting for <b>${s}</b> to walk the boundary after planting.`,
  land_fix: (s) => html`You asked <b>${s}</b> for more about the land change. It comes back here when they send a new 7/12.`,
};

const pct = (x) => `${Math.round(x * 100)}%`;
const dist = (m) => (m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1)} km`);

// Land swap check: the walk before planting compared with the onboarding land.
function landSection(f, ui) {
  const p = f.plan;
  const pl = p.plant;
  const lc = pl.landChange;
  const sv = f.surveyor.name;
  const share = pl.preOverlap;
  const flagged = lc && ['waiting', 'pending'].includes(lc.status);
  let banner = '';
  if (flagged) {
    banner = html`<div class="notice red"><b>Land swap flagged.</b> The boundary walked before planting is ${dist(pl.preDistanceM || 0)} from the land checked at onboarding, and only ${pct(share || 0)} of it overlaps. ${sv} reported a land change: “${lc.reasonText}”.</div>`;
  } else if (lc?.status === 'rejected' && p.plantation === 'pre_walk') {
    banner = html`<div class="notice"><b>Land change to Gat ${lc.surveyNo} rejected</b> by ${lc.reviewer || 'a reviewer'} · ${fmtDateTime(lc.reviewedAt)}: “${lc.message}”. Waiting for ${sv} to walk the land checked at onboarding.</div>`;
  } else if (lc?.status === 'approved') {
    banner = html`<div class="notice green"><b>Land change to Gat ${lc.surveyNo} approved</b> by ${lc.reviewer || 'a reviewer'} · ${fmtDateTime(lc.reviewedAt)}. Planting continues on the new land.</div>`;
  } else if (share != null && share < 0.7) {
    banner = html`<div class="notice" style="background:var(--amber-t);color:var(--amber-text)"><b>Partly different land.</b> Only ${pct(share)} of the boundary before planting is on the land checked at onboarding (${dist(pl.preDistanceM || 0)} apart).${pl.landNote ? html` ${sv}'s note: “${pl.landNote}”` : ''}</div>`;
  }
  if (!lc || lc.status === 'rejected' && p.plantation !== 'pre_walk') return { banner, card: '', decision: '' };
  const need = p.pay ? (p.pay.timber + p.pay.fruit) * 4 : 0;
  const card = html`<div class="pgrid">
    <div class="card"><div class="ch"><h2>Land change</h2><span class="sub">reported ${fmtDateTime(lc.at)}</span></div>
      <div class="kv"><span>Reason</span><b>${lc.reasonText}</b></div>
      <div class="kv"><span>New land</span><b>Gat ${lc.surveyNo}${lc.areaHa ? ` · ${lc.areaHa.toFixed(2)} ha on 7/12` : ''}${pl.preAreaM2 ? ` · ${acres(pl.preAreaM2).toFixed(2)} ac walked` : ''}</b></div>
      <div class="kv"><span>Owner</span><b>${lc.ownerType === 'family' ? 'Family member' : 'Farmer'}</b></div>
      ${share != null ? html`<div class="kv"><span>On the onboarding land</span><b style="color:${share < 0.3 ? 'var(--red)' : 'var(--amber-d)'}">${pct(share)}</b></div>
      <div class="kv"><span>Distance from the onboarding land</span><b>${dist(pl.preDistanceM || 0)}</b></div>` : ''}
      ${need && pl.preAreaM2 ? html`<div class="kv"><span>Fits ${fmtInt(need / 4)} saplings at 2 m</span><b style="color:${pl.preAreaM2 >= need ? 'var(--green-text)' : 'var(--red)'}">${pl.preAreaM2 >= need ? 'Yes' : 'No'}</b></div>` : ''}
    </div>
    <div class="card"><div class="ch"><h2>7/12 of the new land</h2><span class="sub">${(lc.photos || []).length} file(s)</span></div>
      <div class="photos">${(lc.photos || []).map((x) => x.mime === 'application/pdf'
        ? html`<div class="shot none pdf"><a href="${x.url}" target="_blank" rel="noopener">Open 7/12 (PDF)</a></div>`
        : html`<div class="shot"><img src="${x.url}" alt="7/12 of the new land" data-file="${x.id}" style="transform:rotate(${ui.rot[x.id] || 0}deg)" loading="lazy">
          <button class="rot" data-rot="${x.id}" aria-label="Rotate photo" title="Rotate">${icon.rotate}</button><span class="cap">7/12 · ${fmtDateTime(x.uploadedAt)}</span></div>`)}</div></div>
  </div>`;
  const decision = flagged ? html`<div class="card decision">${decisionBlock('land', ui.draft.land, { status: lc.status, reviewer: lc.reviewer, at: lc.reviewedAt, message: lc.message }, sv, ui.tried.land)}</div>
    <div class="savebar" id="savebar-land">${saveBar('land', ui.draft.land, ui.changed('land'), ui.saving, sv, lc.surveyNo)}</div>` : '';
  return { banner, card, decision };
}

const checksCard = (checks) => {
  if (!checks?.length) return '';
  const ok = checks.filter((c) => c.ok).length;
  return html`<div class="card"><div class="ch"><h2>Checks</h2><span class="passed ${ok < checks.length ? 'bad' : ''}">${ok} of ${checks.length} passed</span></div>
    ${checks.map((c) => html`<div class="chk ${c.ok ? '' : 'bad'}">${c.ok ? icon.check : icon.cross}<span>${c.text}</span></div>`)}</div>`;
};

export function decisionBlock(section, d, cur, surveyor, tried) {
  const needMsg = d.decision === 'rejected' || d.decision === 'pending';
  const err = tried && needMsg && !d.message.trim();
  return html`<div>
      <div class="dec-lbl">${section === 'plantation' ? 'Decision on the plantation' : section === 'land' ? 'Decision on the land change' : 'Decision'}</div>
      <div class="decide" role="group" aria-label="Decision on the ${section}">
        ${DECISIONS.map((x) => html`<button data-pd="${section}" data-v="${x.value}" class="${d.decision === x.value ? `on-${x.value}` : ''}" aria-pressed="${d.decision === x.value}">${x.label}</button>`)}
      </div>
      <div class="hint" style="margin-top:10px">${section === 'land' ? html`Approve lets planting continue on the new land. Reject sends ${surveyor} back to walk the land checked at onboarding. Reject and Pending need a message.` : html`Reject and Pending need a message; it goes to ${surveyor}'s phone as a task with a red dot.`}</div>
      ${cur.reviewer && cur.status !== 'waiting' && cur.status ? html`<div class="lastdec"><b>${STATUS_TEXT[cur.status]}</b> by ${cur.reviewer} · ${fmtDateTime(cur.at)}${cur.message ? html`<br>“${cur.message}”` : ''}</div>` : ''}
    </div>
    ${needMsg ? html`<div class="field ${err ? 'err' : ''}">
      <label class="lbl" for="pm-${section}">Message to ${surveyor} <span style="color:var(--red)">(required)</span></label>
      <textarea id="pm-${section}" data-pmsg="${section}" maxlength="${MAX_MSG}" placeholder="What is wrong and what should they do?">${d.message}</textarea>
      <div class="hint ${err ? 'bad' : ''}" data-phint="${section}">${err ? 'Write a message: the surveyor needs to know what to fix.' : `${d.message.length}/${MAX_MSG}`}</div>
    </div>` : html`<div></div>`}`;
}

export function saveBar(section, d, changed, saving, surveyor, gat = '') {
  let text;
  if (!d.decision || !changed) text = html`Choose Approve, Reject or Pending, then save.`;
  else if (section === 'land') {
    text = d.decision === 'approved' ? html`Approving lets planting continue on Gat ${gat}; <b>${surveyor}</b>'s phone moves on to sapling delivery.`
      : d.decision === 'rejected' ? html`Rejecting sends <b>${surveyor}</b> back to walk the land checked at onboarding, with your message.`
      : html`Saving sends one task to <b>${surveyor}</b>'s phone with your message. They add a new 7/12 and it comes back here.`;
  } else if (d.decision === 'approved') {
    text = section === 'payment'
      ? html`Approving opens the next step on <b>${surveyor}</b>'s phone: walk the boundary before planting, then sapling delivery.`
      : html`Approving marks this farmer's plantation complete.`;
  } else {
    text = section === 'payment'
      ? html`Saving sends one task to <b>${surveyor}</b>'s phone with your message. They send a new screenshot and the payment comes back here.`
      : html`Saving sends one task to <b>${surveyor}</b>'s phone with your message. When they fix it, the plantation comes back here for review.`;
  }
  return html`<span class="small" style="flex:1;min-width:240px;color:#3A3A3C">${text}</span>
    <span class="actions">${changed ? html`<button class="btn" data-pundo="${section}">Undo</button>` : ''}<button class="btn primary big" data-psave="${section}" ${!changed || saving ? 'disabled' : ''}>${saving ? 'Saving…' : 'Save decision'}</button></span>`;
}

function eventText(e) {
  switch (e.event) {
    case 'payment_submitted': return `sent the payment: ${e.detail}`;
    case 'pre_walk': return `walked the boundary before planting (${e.detail})`;
    case 'post_walk': return `walked the boundary after planting (${e.detail})`;
    case 'delivery_ok': return `recorded the delivery: ${e.detail}`;
    case 'delivery_short': return `recorded a delivery the farmer did not accept: ${e.detail}`;
    case 'otp_sent': return `sent the OTP ${e.detail}`;
    case 'otp_verified': return 'entered the OTP: the farmer confirmed the saplings';
    case 'planted': return `marked planting done: ${e.detail}`;
    case 'plant_resubmitted': return 'sent a new photo for review';
    case 'land_change_reported': return `reported a land change: ${e.detail}`;
    case 'land_change_resubmitted': return 'sent a new 7/12 of the new land';
    default: return e.detail.replace(/^(Payment|Plantation) /, (m) => m.toLowerCase());
  }
}

export function historyCard(events, intro) {
  return html`<div class="card"><div class="ch"><h2>History</h2></div><div class="pad"><ul class="history">
    ${intro || ''}
    ${events.map((e) => html`<li>${fmtDateTime(e.at)} · <b>${e.by || 'Someone'}</b> ${eventText(e)}</li>`)}
    ${!events.length && !intro ? html`<li class="muted">Nothing yet.</li>` : ''}
  </ul></div></div>`;
}

function planCard(plan, timber, fruit, sub) {
  return html`<div class="card"><div class="ch"><h2>Sapling plan</h2><span class="sub">${sub}</span></div>
    <div class="kv"><span>${fmtInt(timber)} ${sp(plan.timberSpecies, 'timber')} × ${rupees(plan.timberRate)}</span><b>${rupees(timber * plan.timberRate)}</b></div>
    <div class="kv"><span>${fmtInt(fruit)} ${sp(plan.fruitSpecies, 'fruit')} × ${rupees(plan.fruitRate)}</span><b>${rupees(fruit * plan.fruitRate)}</b></div>
    <div class="kv total"><span>Amount due</span><b>${rupees(timber * plan.timberRate + fruit * plan.fruitRate)}</b></div></div>`;
}

// "Mahogany (timber)", or just "Timber" when no species was chosen at onboarding.
const sp = (name, kind) => (name.toLowerCase() === kind ? kind[0].toUpperCase() + kind.slice(1) : `${name} (${kind})`);
const mix = (plan) => `${plan.timberPercent}% timber · ${100 - plan.timberPercent}% fruit, from onboarding`;

export function paymentTab(f, ui) {
  const p = f.plan;
  const plan = p.plan;
  const pay = p.pay;
  const sv = f.surveyor.name;
  const fits = html`<div class="kv"><span>Registered land</span><b>${plan.areaAcres.toFixed(2)} ac</b></div>
    <div class="kv"><span>Fits at 2 m × 2 m</span><b>up to ${fmtInt(plan.maxSaplings)} saplings</b></div>`;
  if (!pay) {
    const note = p.payment === 'locked'
      ? html`Payment opens once all four onboarding documents are approved. Review them on the Onboarding tab.`
      : html`Onboarding is approved. Waiting for <b>${sv}</b> to collect the payment from the farmer and send the screenshot.`;
    return html`<div class="notice">${note}</div>
      <div class="pgrid"><div class="card"><div class="ch"><h2>Land</h2></div>${fits}
        <div class="kv"><span>Rates</span><b>${rupees(plan.timberRate)} per timber · ${rupees(plan.fruitRate)} per fruit sapling</b></div></div>
      ${planCard(plan, plan.timber, plan.fruit, mix(plan))}</div>
      ${historyCard(p.events.filter((e) => e.event.startsWith('payment_')))}`;
  }
  const proofs = pay.proofs || [];
  const idx = Math.min(ui.proofIdx ?? proofs.length - 1, proofs.length - 1);
  const cur = proofs[idx];
  const rot = cur ? ui.rot[cur.id] || 0 : 0;
  const open = pay.status !== 'approved';
  return html`<div class="pgrid">
      <div class="card"><div class="ch"><h2>Payment screenshot</h2><span class="pill p-${STATUS_CLASS[pay.status]}">${STATUS_TEXT[pay.status]}</span></div>
        <div class="proof">
          ${cur ? html`<div class="frame"><img src="${cur.url}" alt="Payment screenshot" data-file="${cur.id}" style="transform:rotate(${rot}deg)">
            <button class="rot" data-rot="${cur.id}" aria-label="Rotate screenshot" title="Rotate">${icon.rotate}</button></div>
            ${proofs.length > 1 ? html`<div class="thumbs" role="group" aria-label="All screenshots">${proofs.map((x, i) => html`<button data-proof="${i}" class="${i === idx ? 'on' : ''}" aria-label="Screenshot ${i + 1}" aria-pressed="${i === idx}"><img src="${x.url}" alt=""></button>`)}</div>` : ''}
            <div class="small"><button class="linkbtn" data-open="${cur.id}">Open full size</button> <span class="muted">· ${proofs.length > 1 ? `screenshot ${idx + 1} of ${proofs.length} · ` : ''}uploaded ${fmtDateTime(cur.uploadedAt)}</span></div>`
          : html`<div class="shot none" style="width:100%">No screenshot uploaded</div>`}
        </div></div>
      <div class="pcol">
        <div class="card"><div class="ch"><h2>What the surveyor entered</h2><span class="sub">sent ${fmtDateTime(pay.submittedAt)}</span></div>
          <div class="kv"><span>Amount paid</span><b>${rupees(pay.amountPaid)}</b></div>
          <div class="kv"><span>Method</span><b>${pay.method === 'upi' ? 'UPI' : 'Bank transfer'}</b></div>
          <div class="kv"><span>Reference no.</span><b class="mono">${pay.reference}</b></div>
          <div class="kv"><span>Paid on</span><b>${fmtDate(pay.paidOn)}</b></div></div>
        ${checksCard(pay.checks)}
        ${planCard(plan, pay.timber, pay.fruit, mix(plan))}
        ${open ? html`<div class="card pad">${decisionBlock('payment', ui.draft.payment, { status: pay.status, reviewer: pay.reviewer, at: pay.reviewedAt, message: pay.message }, sv, ui.tried.payment)}</div>`
          : html`<div class="notice green"><b>Approved</b> by ${pay.reviewer || 'a reviewer'} · ${fmtDateTime(pay.reviewedAt)}. The plantation steps are open on ${sv}'s phone.</div>`}
      </div>
    </div>
    ${open ? html`<div class="savebar" id="savebar-payment">${saveBar('payment', ui.draft.payment, ui.changed('payment'), ui.saving, sv)}</div>` : ''}
    ${historyCard(p.events.filter((e) => e.event.startsWith('payment_')))}`;
}

function stepsCard(steps) {
  return html`<div class="card"><div class="ch"><h2>Steps</h2></div><ol class="steps">
    ${steps.map((s, i) => html`<li class="${s.state}"><span class="dot">${s.state === 'done' ? '✓' : s.state === 'stopped' ? '!' : i + 1}</span>${s.label}</li>`)}</ol></div>`;
}

export function plantationTab(f, ui) {
  const p = f.plan;
  const sv = f.surveyor.name;
  const pl = p.plant;
  const plan = p.plan;
  const pay = p.pay;
  if (p.plantation === 'locked' || !pl) {
    const note = p.plantation === 'locked'
      ? html`Plantation starts after the payment is approved.`
      : STAGE_NOTE.pre_walk(sv);
    return html`<div class="notice">${note}</div><div class="pgrid">${stepsCard(p.steps)}<div></div></div>${historyCard(p.events)}`;
  }
  const onbArea = (f.boundaries || []).reduce((a, b) => a + b.areaM2, 0);
  const planted = pl.timberPlanted != null ? pl.timberPlanted + pl.fruitPlanted : null;
  const photoCheck = (pl.checks || []).find((c) => c.text.includes('planting photos'));
  const reviewable = ['to_review', 'rejected', 'pending'].includes(p.plantation);
  const note = STAGE_NOTE[p.plantation];
  const land = landSection(f, ui);
  const counted = (n, want) => html`<td style="font-weight:600;color:${n === want ? 'var(--green-text)' : 'var(--red)'}">${n == null ? '—' : fmtInt(n)}</td>`;
  return html`
    ${land.banner}
    ${note && !(p.plantation === 'pre_walk' && land.banner) ? html`<div class="notice ${p.plantation === 'short' ? 'red' : ''}">${note(sv)}</div>` : ''}
    ${land.card}${land.decision}
    ${p.plantation === 'done' ? html`<div class="notice green"><b>Approved</b> by ${pl.reviewer || 'a reviewer'} · ${fmtDateTime(pl.reviewedAt)}. This farmer's plantation is complete.</div>` : ''}
    <div class="ptiles">
      <div class="ptile"><small>Saplings planted</small><b>${planted == null ? '—' : fmtInt(planted)}</b><span>${planted == null ? `${fmtInt(pay?.timber + pay?.fruit)} paid for` : `${fmtInt(pl.timberPlanted)} ${plan.timberSpecies.toLowerCase()} · ${fmtInt(pl.fruitPlanted)} ${plan.fruitSpecies.toLowerCase()}`}</span></div>
      <div class="ptile"><small>Area after planting</small><b>${pl.postAreaM2 ? `${acres(pl.postAreaM2).toFixed(2)} ac` : '—'}</b><span>${pl.preAreaM2 ? `${acres(pl.preAreaM2).toFixed(2)} ac before · ` : ''}${acres(onbArea).toFixed(2)} ac at onboarding</span></div>
      <div class="ptile"><small>Farmer's OTP</small>${pl.otpVerifiedAt ? html`<b class="good">Confirmed</b><span>${fmtDateTime(pl.otpVerifiedAt)}</span>` : pl.otpSentAt ? html`<b class="warn">Sent</b><span>${fmtDateTime(pl.otpSentAt)}</span>` : html`<b>Not yet</b><span>after the farmer accepts the saplings</span>`}</div>
      <div class="ptile"><small>Photos</small><b>${fmtInt((pl.photos || []).length)}</b><span>${photoCheck ? photoCheck.text.replace(/\.$/, '') : (pl.photos || []).length ? 'no planting photos yet' : 'none yet'}</span></div>
    </div>
    <div class="pgrid wide">
      <div class="card"><div class="ch"><h2>Boundaries</h2><div class="legend"><span><i class="l-on"></i>Onboarding</span><span><i class="l-pre"></i>Before planting</span><span><i class="l-post"></i>After planting</span></div></div>
        <div class="pad"><div class="map tall" id="plantmap" aria-label="Satellite map with the onboarding, before-planting and after-planting boundaries"></div>
          <div class="actions" style="margin-top:12px"><button class="btn sm" id="kml-all">Download all walks (KML)</button></div></div></div>
      <div class="pcol">
        ${pl.timberCounted != null ? html`<div class="card"><div class="ch"><h2>Delivery and farmer's confirmation</h2></div>
          <div class="tw"><table><tr><th>Sapling</th><th>Paid for</th><th>Counted by farmer</th></tr>
            <tr><td>${sp(plan.timberSpecies, 'timber')}</td><td>${fmtInt(pay.timber)}</td>${counted(pl.timberCounted, pay.timber)}</tr>
            <tr><td>${sp(plan.fruitSpecies, 'fruit')}</td><td>${fmtInt(pay.fruit)}</td>${counted(pl.fruitCounted, pay.fruit)}</tr></table></div>
          <div class="kv"><span>Quality</span><b>${[pl.healthy ? 'Healthy' : 'Not healthy', pl.rightSpecies ? 'right species' : 'wrong species', pl.satisfied ? 'farmer satisfied' : 'farmer not satisfied'].join(', ')}</b></div>
          <div class="kv"><span>OTP sent to</span><b>${pl.otpSentAt ? html`${phone(p.farmerMobile)} · farmer's registered mobile` : '—'}</b></div>
          <div class="kv"><span>OTP entered</span><b>${pl.otpVerifiedAt ? `${fmtDateTime(pl.otpVerifiedAt)}${pl.otpDistanceM == null ? '' : pl.otpDistanceM < 1 ? ' · inside the field' : ` · ${Math.round(pl.otpDistanceM)} m from the field`}` : '—'}</b></div></div>` : stepsCard(p.steps)}
        ${checksCard(pl.checks)}
      </div>
    </div>
    ${(pl.photos || []).length ? html`<div class="card"><div class="ch"><h2>Photos</h2><span class="sub">Click to open full size · rotate if a photo is sideways</span></div>
      <div class="photos">${pl.photos.map((x) => html`<div class="shot"><img src="${x.url}" alt="${PHOTO_LABEL[x.kind]}" data-file="${x.id}" style="transform:rotate(${ui.rot[x.id] || 0}deg)" loading="lazy">
        <button class="rot" data-rot="${x.id}" aria-label="Rotate photo" title="Rotate">${icon.rotate}</button><span class="cap">${PHOTO_LABEL[x.kind]} · ${fmtDateTime(x.capturedAt || x.uploadedAt)}</span></div>`)}</div></div>` : ''}
    ${reviewable ? html`<div class="card decision">${decisionBlock('plantation', ui.draft.plantation, { status: pl.reviewStatus, reviewer: pl.reviewer, at: pl.reviewedAt, message: pl.reviewMessage }, sv, ui.tried.plantation)}</div>
      <div class="savebar" id="savebar-plantation">${saveBar('plantation', ui.draft.plantation, ui.changed('plantation'), ui.saving, sv)}</div>` : ''}
    ${historyCard(p.events)}`;
}

// Map layers: onboarding (amber dotted), before planting (blue dashed), after planting (green).
export function plantLayers(f) {
  const ring = (pts) => (pts || []).map((q) => [q.lat, q.lng]);
  const layers = (f.boundaries || []).map((b) => ({ ring: ring(b.points), style: { color: '#FCD34D', weight: 2.5, dashArray: '3 6', fill: false } }));
  const r = f.plan.plant?.rings || {};
  if (r.prePlant) layers.push({ ring: ring(r.prePlant), style: { color: '#93C5FD', weight: 2.5, dashArray: '9 7', fill: false } });
  if (r.postPlant) layers.push({ ring: ring(r.postPlant), style: { color: '#E8F3EC', weight: 3, fillColor: '#2e7d32', fillOpacity: 0.5 } });
  return layers;
}

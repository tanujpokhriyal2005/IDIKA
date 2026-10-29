import { api, html, raw, render, $, fmtInt, errorBox, qs, esc } from '../lib.js';
import { downloadFile } from './farmers.js';

const C = { green: '#1F7A3A', greenSoft: '#BFDCC8', amber: '#D97706', red: '#B42318', blue: '#1D4ED8', grid: '#ECECF0', muted: '#6C6C70' };
const RANGES = [['30', '30 days'], ['90', '90 days'], ['365', 'This year'], ['all', 'All time']];

function delta(cur, prev, unit = '%', pts = false) {
  if (prev == null || cur == null) return '';
  if (pts) {
    const d = Math.round(cur - prev);
    if (!d) return html`<span class="muted small">same as before</span>`;
    return html`<span class="${d > 0 ? 'up' : 'down'}">${d > 0 ? '↑' : '↓'} ${Math.abs(d)} pts</span> <span class="muted small">vs previous period</span>`;
  }
  if (!prev) return cur ? html`<span class="up">new this period</span>` : '';
  const d = Math.round(((cur - prev) / prev) * 100);
  if (!d) return html`<span class="muted small">same as previous period</span>`;
  return html`<span class="${d > 0 ? 'up' : 'down'}">${d > 0 ? '↑' : '↓'} ${Math.abs(d)}${unit}</span> <span class="muted small">vs previous period</span>`;
}

function seriesChart(series) {
  const W = 640, H = 300, L = 34, R = 8, T = 22, B = 34;
  const pw = W - L - R, ph = H - T - B;
  const max = Math.max(5, ...series.map((s) => Math.max(s.onboarded, s.approved)));
  const step = Math.ceil(max / 4 / 5) * 5 || 1;
  const top = step * 4;
  const y = (v) => T + ph - (v / top) * ph;
  const slot = pw / Math.max(1, series.length);
  const bw = Math.min(40, slot * 0.56);
  const every = series.length > 8 ? 2 : 1;
  let g = '';
  for (let v = 0; v <= top; v += step) {
    g += `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" stroke="${C.grid}"/><text x="${L - 8}" y="${y(v) + 4}" font-size="11" fill="${C.muted}" text-anchor="end">${v}</text>`;
  }
  const pts = [];
  series.forEach((s, i) => {
    const cx = L + slot * i + slot / 2;
    g += `<rect x="${cx - bw / 2}" y="${y(s.onboarded)}" width="${bw}" height="${Math.max(0, y(0) - y(s.onboarded))}" rx="4" fill="${C.greenSoft}"><title>${esc(s.label)}: ${s.onboarded} onboarded, ${s.approved} approved</title></rect>`;
    if (s.onboarded) g += `<text x="${cx}" y="${y(s.onboarded) - 6}" font-size="11" fill="#3A3A3C" text-anchor="middle">${s.onboarded}</text>`;
    pts.push(`${cx},${y(s.approved)}`);
    if (i % every === 0 || i === series.length - 1) g += `<text x="${cx}" y="${H - 12}" font-size="11" fill="${C.muted}" text-anchor="middle">${esc(s.label)}</text>`;
  });
  g += `<polyline points="${pts.join(' ')}" fill="none" stroke="${C.green}" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>`;
  pts.forEach((p) => { const [cx, cy] = p.split(','); g += `<circle cx="${cx}" cy="${cy}" r="3.5" fill="#fff" stroke="${C.green}" stroke-width="2"/>`; });
  return raw(`<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Farmers onboarded and approved over time">${g}</svg>`);
}

const stack = (parts, total) => html`<div class="stack" style="height:${parts.h || 14}px">${parts.items.map(([v, c]) => (v ? html`<span style="width:${(v / Math.max(1, total)) * 100}%;background:${c}"></span>` : ''))}</div>`;

export default async function insightsPage({ el, app, query, navigate }) {
  const me = app.me;
  const range = RANGES.some(([k]) => k === query.get('range')) ? query.get('range') : '90';
  const state = me.role === 'admin' ? query.get('state') || '' : '';
  render(el, html`<div class="loading">Loading insights…</div>`);
  let d, states = [];
  try {
    [d, states] = await Promise.all([
      api('/v1/dash/insights' + qs({ range, state })),
      me.role === 'admin' ? api('/v1/dash/options').then((o) => o.states) : Promise.resolve([]),
    ]);
  } catch (e) { render(el, errorBox(e)); return; }
  const k = d.kpis;
  const scope = me.role === 'admin' ? (state || 'all states') : me.role === 'state_lead' ? me.state : me.fullName;
  const fOnb = d.funnel[0].value || 0;
  const qMax = Math.max(1, ...d.queue.map((x) => x.value));
  const qTotal = d.queue.reduce((a, x) => a + x.value, 0);
  const bMax = Math.max(1, ...d.breakdown.map((b) => b.farmers));
  const sv = d.surveyors;
  const pct = sv.total ? Math.round((sv.active / sv.total) * 100) : 0;
  const by = { state: 'Farmers by state', ngo: 'Farmers by NGO', surveyor: 'Farmers by surveyor' }[d.breakdownBy];
  const unitWord = d.seriesUnit === 'month' ? 'month' : 'week';

  render(el, html`
    <div class="head">
      <div><h1>Insights</h1><div class="sub">How onboarding, review and planting are going · ${scope}</div></div>
      <div class="actions">
        <div class="seg" role="group" aria-label="Time range">${RANGES.map(([key, label]) => html`<button data-range="${key}" class="${key === range ? 'on' : ''}">${label}</button>`)}</div>
        ${me.role === 'admin' ? html`<label class="sr" for="st">State</label><select id="st" class="select"><option value="">All states</option>${states.map((s) => html`<option ${s === state ? 'selected' : ''}>${s}</option>`)}</select>` : ''}
        <button class="btn" id="csv">Download CSV</button>
      </div>
    </div>
    <div class="tiles">
      <div class="tile"><small>Farmers onboarded${range === 'all' ? '' : ` · ${RANGES.find((r) => r[0] === range)[1].toLowerCase()}`}</small><b>${fmtInt(k.onboarded)}</b>${range === 'all' ? html`<span>since the start</span>` : delta(k.onboarded, k.onboardedPrev)}</div>
      <div class="tile"><small>Approved first time</small><b>${k.firstTimeRate == null ? '—' : `${k.firstTimeRate}%`}</b>${k.firstTimeRate == null ? html`<span>no reviews in this period</span>` : delta(k.firstTimeRate, k.firstTimeRatePrev, '', true)}</div>
      <div class="tile"><small>Time to first review</small><b>${k.avgDaysToFirstReview == null ? '—' : `${k.avgDaysToFirstReview} days`}</b><span>target: under 2 days</span></div>
      <div class="tile"><small>Land mapped</small><b>${fmtInt(Math.round(k.landMappedHa))} ha</b><span>${k.mappedFarmers ? `walked boundaries · ${(k.landMappedHa / k.mappedFarmers).toFixed(1)} ha per farmer` : 'no boundaries yet'}</span></div>
    </div>
    <div class="row2">
      <div class="card"><div class="ch"><h2>Onboarding per ${unitWord}</h2><div class="key"><span><i style="background:${C.greenSoft}"></i>Onboarded</span><span><i style="background:${C.green};height:3px;width:14px;vertical-align:3px"></i>Fully approved</span></div></div>
        <div class="pad chart">${seriesChart(d.series)}</div></div>
      <div class="card"><div class="ch"><h2>From onboarding to planting</h2><span class="sub">all time</span></div>
        <div class="pad" style="display:flex;flex-direction:column;gap:14px">
          ${d.funnel.map((s, i) => {
            const p = fOnb ? Math.round((s.value / fOnb) * 100) : 0;
            return html`<div><div style="display:flex;justify-content:space-between;font-size:14px;gap:8px"><b style="font-weight:600">${s.label}</b><span><b>${fmtInt(s.value)}</b> <span class="muted">· ${p}%</span></span></div>
              <div style="margin-top:6px;height:22px;border-radius:6px;background:#F0F0F3"><div style="width:${p}%;height:100%;border-radius:6px;background:${C.green};opacity:${1 - i * 0.18}"></div></div>
              ${i ? html`<span class="muted small">−${fmtInt(d.funnel[i - 1].value - s.value)} from the step before</span>` : ''}</div>`;
          })}
          <span class="muted small">Payments are not tracked in Fieldproof yet.</span>
        </div></div>
    </div>
    <div class="row2">
      <div class="card"><div class="ch"><h2>Review results by document</h2><div class="key"><span><i style="background:${C.green}"></i>Approved</span><span><i style="background:${C.amber}"></i>Pending</span><span><i style="background:${C.red}"></i>Rejected</span><span><i style="background:${C.blue}"></i>New</span></div></div>
        <div class="pad" style="display:flex;flex-direction:column;gap:16px">
          ${d.docs.map((x) => {
            const tot = x.approved + x.pending + x.rejected + x.new;
            return html`<div><div style="display:flex;justify-content:space-between;font-size:14px;gap:8px"><b style="font-weight:600">${x.label}</b><span class="muted">${x.firstTimeRate == null ? 'not reviewed yet' : `${x.firstTimeRate}% approved first time`}</span></div>
              <div style="margin-top:6px">${stack({ items: [[x.approved, C.green], [x.pending, C.amber], [x.rejected, C.red], [x.new, C.blue]] }, tot)}</div>
              <div class="muted small" style="margin-top:5px">${x.topReason ? html`${x.topReasonCount > 1 ? 'Most common reason sent back' : 'Reason sent back'}: “${x.topReason}”` : 'Nothing sent back'}${x.missing ? ` · ${x.missing} not uploaded` : ''}</div></div>`;
          })}
        </div></div>
      <div class="card"><div class="ch"><h2>Waiting for review</h2><span class="sub">${fmtInt(qTotal)} farmers · by age</span></div>
        <div class="pad" style="display:flex;flex-direction:column;gap:12px">
          ${d.queue.map((q, i) => html`<div style="display:grid;grid-template-columns:64px 1fr 34px;align-items:center;gap:10px;font-size:14px"><span>${q.label}</span>
            <div style="height:18px;border-radius:5px;background:#F0F0F3"><div style="width:${(q.value / qMax) * 100}%;height:100%;border-radius:5px;background:${i === 3 ? C.red : i === 2 ? C.amber : C.blue}"></div></div><b style="text-align:right">${q.value}</b></div>`)}
          ${d.queue[3].value ? html`<div class="error-box small" style="margin-top:4px">${d.queue[3].value} farmer${d.queue[3].value === 1 ? ' has' : 's have'} waited 3 days or more. <a href="/dash/farmers?review=waiting&sort=oldest${state ? `&state=${encodeURIComponent(state)}` : ''}" style="color:var(--red);font-weight:600">Open them</a></div>` : ''}
        </div></div>
    </div>
    <div class="row2">
      <div class="card"><div class="ch"><h2>${by}</h2><div class="key"><span><i style="background:${C.green}"></i>Approved</span><span><i style="background:${C.blue}"></i>Waiting</span><span><i style="background:${C.amber}"></i>Sent back</span></div></div>
        <div class="pad" style="display:flex;flex-direction:column;gap:14px">
          ${d.breakdown.length ? d.breakdown.map((b) => html`<div style="display:grid;grid-template-columns:150px 1fr 56px;align-items:center;gap:12px;font-size:14px">
            <b style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${b.name}">${b.name}</b>
            <div style="width:${(b.farmers / bMax) * 100}%;min-width:4px">${stack({ h: 20, items: [[b.approved, C.green], [b.waiting, C.blue], [b.sentBack, C.amber], [b.farmers - b.approved - b.waiting - b.sentBack, '#D1D1D6']] }, b.farmers)}</div>
            <b style="text-align:right">${fmtInt(b.farmers)}</b></div>`) : html`<span class="muted">Nothing to show yet.</span>`}
        </div></div>
      <div class="card"><div class="ch"><h2>Surveyors active</h2><span class="sub">last 7 days</span></div>
        <div class="pad" style="display:flex;align-items:center;gap:18px">
          ${raw(`<svg width="112" height="112" viewBox="0 0 36 36" role="img" aria-label="${sv.active} of ${sv.total} surveyors active"><circle cx="18" cy="18" r="15.9" fill="none" stroke="#F0F0F3" stroke-width="4"/><circle cx="18" cy="18" r="15.9" fill="none" stroke="${C.green}" stroke-width="4" stroke-dasharray="${pct} 100" stroke-linecap="round" transform="rotate(-90 18 18)" pathLength="100"/><text x="18" y="20.5" text-anchor="middle" font-size="8" font-weight="800" fill="#1C1C1E" font-family="Inter,sans-serif">${pct}%</text></svg>`)}
          <div style="font-size:14px;line-height:22px"><b style="font-size:22px">${sv.active}</b> <span class="muted">of ${sv.total}</span><br>
            <span class="muted">${sv.notSynced} haven't synced in 7 days${sv.never ? `, ${sv.never} never signed in` : ''}.</span><br><a href="/dash/surveyors?quick=stale" style="font-weight:600">See who</a></div>
        </div></div>
    </div>
    <div class="card"><div class="ch"><h2>${d.teamBy === 'ngo' ? 'NGOs' : 'Surveyors'}</h2><span class="sub">sorted by farmers onboarded</span></div>
      ${d.team?.length ? html`<div class="tw"><table><tr><th>${d.teamBy === 'ngo' ? 'NGO' : 'Surveyor'}</th>${d.teamBy === 'ngo' ? html`<th>Surveyors</th>` : ''}<th>Farmers</th><th>Approved first time</th></tr>
        ${d.team.map((t) => html`<tr><td><b>${t.name}</b>${t.firstTimeRate != null && t.firstTimeRate < 75 ? html` <span class="pill p-pending" style="margin-left:6px">Needs support</span>` : ''}${t.state ? html`<div class="code">${t.state}</div>` : ''}</td>
          ${d.teamBy === 'ngo' ? html`<td>${t.surveyors}</td>` : ''}<td>${fmtInt(t.farmers)}</td>
          <td>${t.firstTimeRate == null ? html`<span class="muted">No reviews yet</span>` : html`<span class="minibar ${t.firstTimeRate < 75 ? 'low' : ''}"><span class="track" style="width:110px"><i style="width:${t.firstTimeRate}%"></i></span><b>${t.firstTimeRate}%</b></span>`}</td></tr>`)}
      </table></div>` : html`<div class="empty">Nothing to show yet.</div>`}
    </div>`);

  const go = (r, st) => navigate('/dash/insights' + qs({ range: r === '90' ? '' : r, state: st }), { replace: true });
  el.addEventListener('click', (e) => { const b = e.target.closest('[data-range]'); if (b) go(b.dataset.range, state); });
  $('#st', el)?.addEventListener('change', (e) => go(range, e.target.value));
  $('#csv', el).addEventListener('click', () => downloadFile('/v1/dash/farmers.csv' + qs({ state }), 'fieldproof-farmers.csv'));
}

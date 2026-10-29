import { api, html, render, $, fmtInt, ago, pill, DOCS, errorBox, qs } from '../lib.js';

export default async function overviewPage({ el, app, query, navigate }) {
  const me = app.me;
  const state = query.get('state') || '';
  render(el, html`<div class="loading">Loading…</div>`);
  let data, states = [];
  try {
    [data, states] = await Promise.all([
      api('/v1/dash/overview' + qs({ state })),
      me.role === 'admin' ? api('/v1/dash/options').then((o) => o.states) : Promise.resolve([]),
    ]);
  } catch (e) {
    render(el, errorBox(e, true));
    $('[data-retry]', el)?.addEventListener('click', () => overviewPage({ el, app, query, navigate }));
    return;
  }
  const t = data.tiles;
  const scope = me.role === 'admin' ? (state || 'All states') : me.role === 'state_lead' ? me.state : me.fullName;
  const fq = (extra) => '/dash/farmers' + qs({ state, ...extra });
  const by = { state: 'By state', ngo: 'By NGO', surveyor: 'By surveyor' }[data.breakdownBy];

  render(el, html`
    <div class="head">
      <div><h1>Overview</h1><div class="sub">${scope} · everyone below you in the hierarchy</div></div>
      ${me.role === 'admin' ? html`<div class="actions"><label class="sr" for="st">State</label>
        <select id="st" class="select"><option value="">All states</option>${states.map((s) => html`<option ${s === state ? 'selected' : ''}>${s}</option>`)}</select></div>` : ''}
    </div>
    <div class="tiles">
      ${me.role === 'admin' ? html`<a class="tile" href="/dash/state-leads"><small>State leads</small><b>${fmtInt(t.stateLeads)}</b><span>${t.statesMapped} states mapped</span></a>` : ''}
      ${me.role !== 'ngo' ? html`<a class="tile" href="/dash/ngos"><small>NGOs</small><b>${fmtInt(t.ngos)}</b></a>` : ''}
      <a class="tile" href="/dash/surveyors"><small>Surveyors</small><b>${fmtInt(t.surveyors)}</b><span>${fmtInt(t.activeThisWeek)} active this week</span></a>
      <a class="tile" href="${fq({})}"><small>Farmers onboarded</small><b>${fmtInt(t.farmers)}</b><span>${fmtInt(t.approved)} fully approved</span></a>
      <a class="tile ${t.waiting ? 'alert' : ''}" href="${fq({ review: 'waiting', sort: 'oldest' })}"><small>Waiting for review</small><b>${fmtInt(t.waiting)}</b><span>${t.waiting ? (t.oldestWaitingDays ? `oldest ${t.oldestWaitingDays} day${t.oldestWaitingDays === 1 ? '' : 's'}` : 'oldest from today') : 'nothing waiting'}</span></a>
      <a class="tile" href="${fq({ review: 'sent_back' })}"><small>Sent back to surveyor</small><b>${fmtInt(t.sentBack)}</b><span>waiting for a fix</span></a>
    </div>
    <div class="card">
      <div class="ch"><h2>Waiting for review</h2><a href="${fq({ review: 'waiting', sort: 'oldest' })}" style="font-size:14px;font-weight:600">See all ${fmtInt(t.waiting)}</a></div>
      ${data.waiting.length ? html`<div class="tw"><table>
        <tr><th>Farmer</th><th>Village · ${me.role === 'admin' ? 'State' : 'Block'}</th><th>Surveyor</th>${me.role !== 'ngo' ? html`<th>NGO</th>` : ''}${DOCS.map((d) => html`<th>${d.short}</th>`)}<th>Onboarded</th></tr>
        ${data.waiting.map((f) => html`<tr class="click" data-href="/dash/farmers/${f.id}">
          <td><a href="/dash/farmers/${f.id}"><b>${f.fullName}</b></a><div class="code">${f.code}</div></td>
          <td>${f.village} · ${me.role === 'admin' ? f.state : f.block}</td><td>${f.surveyor}</td>${me.role !== 'ngo' ? html`<td>${f.ngo || '—'}</td>` : ''}
          ${DOCS.map((d) => html`<td>${pill(f.docs[d.key])}</td>`)}<td class="muted">${ago(f.submittedAt)}</td></tr>`)}
      </table></div>` : html`<div class="empty"><b>Nothing waiting for review</b>New farmers appear here when a surveyor finishes onboarding and the phone syncs.</div>`}
    </div>
    <div class="card">
      <div class="ch"><h2>${by}</h2></div>
      ${data.breakdown.length ? html`<div class="tw"><table>
        <tr><th>${{ state: 'State', ngo: 'NGO', surveyor: 'Surveyor' }[data.breakdownBy]}</th>${data.breakdownBy === 'state' ? html`<th>State lead</th><th>NGOs</th>` : ''}${data.breakdownBy !== 'surveyor' ? html`<th>Surveyors</th>` : ''}<th>Farmers</th><th>Waiting for review</th><th>Sent back</th><th>Approved</th></tr>
        ${data.breakdown.map((b) => {
          const filt = data.breakdownBy === 'state' ? { state: b.id } : data.breakdownBy === 'ngo' ? { ngo: b.id } : { surveyor: b.id };
          return html`<tr><td><a href="${'/dash/farmers' + qs(filt)}"><b>${b.name}</b></a></td>
            ${data.breakdownBy === 'state' ? html`<td>${b.lead || html`<span class="muted">No state lead yet</span>`}</td><td>${b.ngos}</td>` : ''}
            ${data.breakdownBy !== 'surveyor' ? html`<td>${b.surveyors}</td>` : ''}
            <td>${fmtInt(b.farmers)}</td><td>${b.waiting ? html`<a href="${'/dash/farmers' + qs({ ...filt, review: 'waiting' })}"><span class="pill p-new">${b.waiting}</span></a>` : '0'}</td>
            <td>${b.sentBack ? html`<a href="${'/dash/farmers' + qs({ ...filt, review: 'sent_back' })}"><span class="pill p-pending">${b.sentBack}</span></a>` : '0'}</td><td>${fmtInt(b.approved)}</td></tr>`;
        })}
      </table></div>` : html`<div class="empty">No ${data.breakdownBy === 'state' ? 'states' : data.breakdownBy === 'ngo' ? 'NGOs' : 'surveyors'} yet. <a href="/dash/issue">Issue a login</a> to add one.</div>`}
    </div>`);

  $('#st', el)?.addEventListener('change', (e) => navigate('/dash/' + qs({ state: e.target.value })));
  el.addEventListener('click', (e) => {
    const tr = e.target.closest('tr[data-href]');
    if (tr && !e.target.closest('a')) navigate(tr.dataset.href);
  });
}

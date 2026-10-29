import { api, html, render, $, $$, fmtInt, ago, daysSince, phone, chip, icon, errorBox, qs, debounce, toast, confirmBox, infoBox, copyText, esc } from '../lib.js';

// ---------- shared: Manage menu ----------

function manageMenu(row, kind) {
  if (!row.userId) return html`<a href="/dash/issue?mobile=${encodeURIComponent(row.phone || '')}&name=${encodeURIComponent(row.name)}" class="small">Give a login</a>`;
  const off = row.active === false || row.login === 'off';
  return html`<span class="menu"><button class="btn sm" data-menu="${row.userId}" aria-haspopup="true">Manage</button>
    <span class="pop" hidden data-pop="${row.userId}">
      ${kind !== 'surveyor' ? html`<button data-act="reset" data-id="${row.userId}" data-name="${row.name}">Reset password</button>` : ''}
      <button data-act="${off ? 'on' : 'off'}" data-id="${row.userId}" data-name="${row.name}" class="${off ? '' : 'danger'}">${off ? 'Turn login back on' : 'Turn login off'}</button>
    </span></span>`;
}

function wireManage(el, reload, kind) {
  el.addEventListener('click', async (e) => {
    const m = e.target.closest('[data-menu]');
    $$('[data-pop]', el).forEach((p) => { if (!m || p.dataset.pop !== m.dataset.menu) p.hidden = true; });
    if (m) { const p = $(`[data-pop="${m.dataset.menu}"]`, el); p.hidden = !p.hidden; return; }
    const a = e.target.closest('[data-act]');
    if (!a) return;
    const { act, id, name } = a.dataset;
    if (act === 'reset') {
      if (!(await confirmBox({ title: `Reset ${name}'s password?`, text: 'They will be signed out and need the new one-time password to sign in, then set their own.', ok: 'Reset password' }))) return;
      try {
        const r = await api(`/v1/dash/users/${id}/reset-password`, { method: 'POST' });
        const box = infoBox('New one-time password', html`<p>Share this with ${name}. It is shown only once.</p><div class="cred"><span class="muted">One-time password</span><b class="mono">${r.tempPassword}</b></div>
          <button class="btn" style="margin-top:14px" data-copy="${r.tempPassword}">Copy</button>`);
        box.addEventListener('click', (ev) => { const c = ev.target.closest('[data-copy]'); if (c) copyText(c.dataset.copy); });
      } catch (ex) { toast(ex.message, true); }
      return;
    }
    const turningOff = act === 'off';
    const text = turningOff
      ? (kind === 'surveyor' ? `${name} will be signed out of the phone app and can't sign in again until you turn the login back on. Their farmers stay as they are.` : `${name} will be signed out and can't sign in until you turn the login back on. People under them keep working.`)
      : `${name} will be able to sign in again.`;
    if (!(await confirmBox({ title: turningOff ? `Turn off ${name}'s login?` : `Turn ${name}'s login back on?`, text, ok: turningOff ? 'Turn off' : 'Turn on', danger: turningOff }))) return;
    try {
      await api(`/v1/dash/users/${id}/active`, { method: 'POST', body: { active: !turningOff } });
      toast(turningOff ? 'Login turned off' : 'Login turned on');
      reload();
    } catch (ex) { toast(ex.message, true); }
  });
}

const loginCell = (r) => (r.active === false || r.login === 'off'
  ? html`<span class="pill p-off">Turned off</span>`
  : r.neverSignedIn || r.login === 'never' ? html`<span style="color:var(--amber-text)">Not signed in yet</span>` : html`Active`);

function urlSync(base, f, push) {
  const url = base + qs(f);
  if (push) history.pushState({}, '', url); else history.replaceState({}, '', url);
}

// ---------- NGOs ----------

export async function ngosPage({ el, app, query }) {
  const me = app.me;
  const f = { state: query.get('state') || '', district: query.get('district') || '', block: query.get('block') || '', village: query.get('village') || '', q: query.get('q') || '' };
  const CH = { state: ['district', 'block', 'village'], district: ['block', 'village'], block: ['village'] };
  let seq = 0;
  render(el, html`<div class="head"><div><h1>NGOs</h1><div class="sub">${me.role === 'admin' ? 'NGOs in every state' : `NGOs in ${me.state}`}</div></div>
    <div class="actions"><label class="sr" for="q">Search NGOs</label><input id="q" class="input search" type="search" placeholder="Search NGO name or username" value="${f.q}"></div></div>
    <div class="card fp" id="filters"></div><div class="card" id="list"><div class="loading">Loading…</div></div>`);

  async function load() {
    const my = ++seq;
    try {
      const [o, data] = await Promise.all([
        api('/v1/dash/options' + qs({ state: f.state, district: f.district, block: f.block })),
        api('/v1/dash/ngos' + qs(f)),
      ]);
      if (my !== seq) return;
      const any = Object.values(f).some(Boolean);
      render($('#filters', el), html`<div class="fr"><span class="gl">Where they work</span><div class="chips">
          ${me.role === 'admin' ? chip({ id: 'state', label: 'State', icon: icon.flag, value: f.state, options: o.states.map((s) => ({ value: s, label: s })), placeholder: 'All' }) : ''}
          ${chip({ id: 'district', label: 'District', icon: icon.pin, value: f.district, options: o.districts.map((d) => ({ value: d, label: d })), placeholder: 'All' })}
          ${chip({ id: 'block', label: 'Block', icon: icon.pin, value: f.block, options: o.blocks.map((b) => ({ value: b, label: b })), placeholder: f.district ? 'All' : 'Pick a district', disabled: !f.district })}
          ${chip({ id: 'village', label: 'Village', icon: icon.home, value: f.village, options: o.villages.map((v) => ({ value: v.id, label: v.name })), placeholder: f.block ? 'All' : 'Pick a block', disabled: !f.block })}
        </div></div>
        <div class="ff"><span><b>${fmtInt(data.total)}</b> NGO${data.total === 1 ? '' : 's'}</span><span class="right"><button class="linkbtn" id="clear" ${any ? '' : 'disabled'}>Clear all</button></span></div>`);
      render($('#list', el), data.items.length ? html`<div class="tw"><table>
        <tr><th>NGO</th>${me.role === 'admin' ? html`<th>State</th>` : ''}<th>Districts</th><th>Blocks</th><th>Surveyors</th><th>Farmers</th><th>Approved first time</th><th>Login</th><th></th></tr>
        ${data.items.map((n) => html`<tr>
          <td><b>${n.name}</b><div class="code">${n.username}${n.reportsTo ? ` · added by ${n.reportsTo}` : ''}</div></td>
          ${me.role === 'admin' ? html`<td>${n.state}</td>` : ''}
          <td class="wrap">${n.districts.join(', ') || '—'}</td><td class="wrap">${n.blocks.join(', ') || '—'}</td>
          <td><a href="/dash/surveyors?ngo=${n.id}">${n.surveyors}</a></td><td><a href="/dash/farmers?ngo=${n.id}">${fmtInt(n.farmers)}</a></td>
          <td>${n.firstTimeRate == null ? html`<span class="muted">No reviews yet</span>` : html`<span class="minibar ${n.firstTimeRate < 75 ? 'low' : ''}"><span class="track"><i style="width:${n.firstTimeRate}%"></i></span>${n.firstTimeRate}%</span>`}</td>
          <td>${loginCell(n)}</td><td>${manageMenu({ ...n, userId: n.id }, 'ngo')}</td></tr>`)}
      </table></div>` : html`<div class="empty"><b>${any ? 'No NGOs match these filters' : 'No NGOs yet'}</b>${any ? html`<button class="linkbtn" id="clear2">Clear all filters</button>` : html`<a href="/dash/issue">Issue an NGO login</a>`}</div>`);
    } catch (e) {
      if (my === seq) render($('#list', el), errorBox(e));
    }
  }
  const set = (k, v, push = true) => { f[k] = v; (CH[k] || []).forEach((c) => { f[c] = ''; }); urlSync('/dash/ngos', f, push); load(); };
  el.addEventListener('change', (e) => { if (e.target.dataset.f) set(e.target.dataset.f, e.target.value); });
  el.addEventListener('click', (e) => {
    if (e.target.closest('#clear') || e.target.closest('#clear2')) { Object.keys(f).forEach((k) => { f[k] = ''; }); $('#q', el).value = ''; urlSync('/dash/ngos', f, true); load(); }
  });
  $('#q', el).addEventListener('input', debounce((e) => set('q', e.target.value.trim(), false), 350));
  wireManage(el, load, 'ngo');
  await load();
}

// ---------- surveyors ----------

export async function surveyorsPage({ el, app, query }) {
  const me = app.me;
  const f = { ngo: query.get('ngo') || '', district: query.get('district') || '', block: query.get('block') || '', quick: query.get('quick') || '', q: query.get('q') || '' };
  const CH = { ngo: [], district: ['block'] };
  let seq = 0;
  render(el, html`<div class="head"><div><h1>Surveyors</h1><div class="sub">${me.role === 'ngo' ? `Surveyors of ${me.fullName}` : me.role === 'state_lead' ? `Surveyors in ${me.state}` : 'Surveyors in every state'} · they sign in on the phone app with OTP</div></div>
    <div class="actions"><label class="sr" for="q">Search surveyors</label><input id="q" class="input search" type="search" placeholder="Search name, username or mobile" value="${f.q}"></div></div>
    <div class="tabs" id="tabs"></div>
    <div class="card fp" id="filters"></div><div class="card" id="list"><div class="loading">Loading…</div></div>`);

  async function load() {
    const my = ++seq;
    try {
      const [o, data] = await Promise.all([api('/v1/dash/options' + qs({ district: f.district, ngo: f.ngo })), api('/v1/dash/surveyors' + qs(f))]);
      if (my !== seq) return;
      const c = data.counts;
      render($('#tabs', el), html`${[['', `All · ${c.all}`], ['fix', `Have items to fix · ${c.fix}`], ['stale', `Not synced in 7 days · ${c.stale}`], ['never', `Not signed in yet · ${c.never}`]]
        .map(([k, l]) => html`<button class="tab ${f.quick === k ? 'on' : ''}" data-quick="${k}">${l}</button>`)}`);
      const any = f.ngo || f.district || f.block || f.q || f.quick;
      render($('#filters', el), html`<div class="fr"><span class="gl">Filter</span><div class="chips">
          ${me.role !== 'ngo' ? chip({ id: 'ngo', label: 'NGO', icon: icon.users, value: f.ngo, options: o.ngos.map((n) => ({ value: n.id, label: n.name })), placeholder: 'All' }) : ''}
          ${chip({ id: 'district', label: 'District', icon: icon.pin, value: f.district, options: o.districts.map((d) => ({ value: d, label: d })), placeholder: 'All' })}
          ${chip({ id: 'block', label: 'Block', icon: icon.pin, value: f.block, options: o.blocks.map((b) => ({ value: b, label: b })), placeholder: f.district ? 'All' : 'Pick a district', disabled: !f.district })}
        </div></div>
        <div class="ff"><span><b>${fmtInt(data.total)}</b> surveyor${data.total === 1 ? '' : 's'}</span><span class="right"><button class="linkbtn" id="clear" ${any ? '' : 'disabled'}>Clear all</button></span></div>`);
      render($('#list', el), data.items.length ? html`<div class="tw"><table>
        <tr><th>Surveyor</th>${me.role !== 'ngo' ? html`<th>NGO</th>` : ''}${me.role === 'admin' ? html`<th>State</th>` : ''}<th>District · blocks</th><th>Farmers</th><th>Sent back</th><th>Last synced</th><th>Login</th><th></th></tr>
        ${data.items.map((s) => html`<tr>
          <td><b>${s.name}</b><div class="code">${s.username ? `${s.username} · ` : ''}${phone(s.phone)}</div></td>
          ${me.role !== 'ngo' ? html`<td>${s.ngo || html`<span class="muted">No NGO yet</span>`}</td>` : ''}${me.role === 'admin' ? html`<td>${s.state || '—'}</td>` : ''}
          <td class="wrap">${s.district}${s.blocks.length ? ` · ${s.blocks.join(', ')}` : ''}</td>
          <td><a href="/dash/farmers?surveyor=${s.id}">${fmtInt(s.farmers)}</a></td>
          <td>${s.sentBack ? html`<a href="/dash/farmers?surveyor=${s.id}&review=sent_back"><span class="pill p-rejected">${s.sentBack} to fix</span></a>` : html`<span class="muted">—</span>`}</td>
          <td>${s.lastSeenAt ? html`<span style="${daysSince(s.lastSeenAt) > 7 ? 'color:var(--red)' : ''}">${ago(s.lastSeenAt)}</span>` : html`<span class="muted">never</span>`}</td>
          <td>${loginCell(s)}</td><td>${manageMenu(s, 'surveyor')}</td></tr>`)}
      </table></div>` : html`<div class="empty"><b>${any ? 'No surveyors match' : 'No surveyors yet'}</b>${any ? html`<button class="linkbtn" id="clear2">Clear all filters</button>` : html`<a href="/dash/issue">Issue a surveyor login</a>`}</div>`);
    } catch (e) {
      if (my === seq) render($('#list', el), errorBox(e));
    }
  }
  const set = (k, v, push = true) => { f[k] = v; (CH[k] || []).forEach((c) => { f[c] = ''; }); urlSync('/dash/surveyors', f, push); load(); };
  el.addEventListener('change', (e) => { if (e.target.dataset.f) set(e.target.dataset.f, e.target.value); });
  el.addEventListener('click', (e) => {
    const q = e.target.closest('[data-quick]');
    if (q) { set('quick', q.dataset.quick); return; }
    if (e.target.closest('#clear') || e.target.closest('#clear2')) { Object.keys(f).forEach((k) => { f[k] = ''; }); $('#q', el).value = ''; urlSync('/dash/surveyors', f, true); load(); }
  });
  $('#q', el).addEventListener('input', debounce((e) => set('q', e.target.value.trim(), false), 350));
  wireManage(el, load, 'surveyor');
  await load();
}

// ---------- state leads (admin) ----------

export async function stateLeadsPage({ el }) {
  render(el, html`<div class="head"><div><h1>State leads</h1><div class="sub">One state each, set when the login is issued</div></div>
    <div class="actions"><a class="btn primary" href="/dash/issue?role=state_lead">${icon.plus} Add a state lead</a></div></div>
    <div class="card" id="list"><div class="loading">Loading…</div></div>`);
  async function load() {
    try {
      const data = await api('/v1/dash/state-leads');
      render($('#list', el), data.items.length ? html`<div class="tw"><table>
        <tr><th>State lead</th><th>State</th><th>NGOs</th><th>Surveyors</th><th>Farmers</th><th>Last sign-in</th><th>Login</th><th></th></tr>
        ${data.items.map((s) => html`<tr><td><b>${s.name}</b><div class="code">${s.username}</div></td><td>${s.state}</td>
          <td><a href="/dash/ngos?state=${encodeURIComponent(s.state)}">${s.ngos}</a></td><td>${s.surveyors}</td><td><a href="/dash/farmers?state=${encodeURIComponent(s.state)}">${fmtInt(s.farmers)}</a></td>
          <td class="muted">${s.lastLoginAt ? ago(s.lastLoginAt) : 'never'}</td><td>${loginCell(s)}</td><td>${manageMenu({ ...s, userId: s.id }, 'state_lead')}</td></tr>`)}
      </table></div>` : html`<div class="empty"><b>No state leads yet</b><a href="/dash/issue?role=state_lead">Add the first one</a></div>`);
    } catch (e) { render($('#list', el), errorBox(e)); }
  }
  wireManage(el, load, 'state_lead');
  await load();
}

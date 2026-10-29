import { api, html, render, $, $$, ROLE_LABEL, phone, errorBox, qs, debounce, copyText, toast } from '../lib.js';

const ISSUABLE = { admin: ['state_lead', 'ngo', 'surveyor'], state_lead: ['ngo', 'surveyor'], ngo: ['surveyor'] };

// Suggest a username from a full name: "Sachin More" -> "sachin.more".
const suggest = (name) => name.toLowerCase().normalize('NFKD').replace(/[^a-z0-9 ]/g, '').trim().split(/\s+/).slice(0, 2).join('.').slice(0, 32);

export default async function issuePage({ el, app, query }) {
  const me = app.me;
  const roles = ISSUABLE[me.role] || [];
  let places, opts;
  render(el, html`<div class="loading">Loading…</div>`);
  try {
    [places, opts] = await Promise.all([api('/v1/dash/places'), api('/v1/dash/options')]);
  } catch (e) { render(el, errorBox(e)); return; }

  const s = {
    role: roles.includes(query.get('role')) ? query.get('role') : roles.includes('surveyor') ? 'surveyor' : roles[0],
    state: me.role === 'admin' ? '' : me.state,
    ngoId: me.role === 'ngo' ? me.id : '',
    fullName: query.get('name') || '',
    username: '',
    usernameTouched: false,
    mobile: query.get('mobile') || '',
    district: '',
    blocks: [],
    otherBlocks: '',
    knownBlocks: [],
    errors: {},
    check: null,
    busy: false,
  };
  if (s.fullName) s.username = suggest(s.fullName);

  const ngoName = () => opts.ngos.find((n) => n.id === s.ngoId)?.name || '';
  const ngoState = () => opts.ngos.find((n) => n.id === s.ngoId)?.extra || '';
  const fieldCls = (k) => `field ${s.errors[k] ? 'err' : ''}`;
  const errHint = (k) => (s.errors[k] ? html`<div class="hint bad">${s.errors[k]}</div>` : '');

  function form() {
    const sv = s.role === 'surveyor';
    const checkHint = s.check ? html`<div class="hint ${s.check.available ? 'ok' : 'bad'}">${s.check.message}</div>` : '';
    return html`
      <div class="head"><div><h1>Issue a login</h1><div class="sub">${me.role === 'admin' ? 'You can add state leads, NGOs and surveyors anywhere in India.' : me.role === 'state_lead' ? `You can add NGOs and surveyors in ${me.state}.` : 'You can add surveyors who work under your NGO.'}</div></div></div>
      <div style="display:flex;flex-wrap:wrap;gap:22px;align-items:flex-start">
      <form class="card pad" id="f" novalidate style="flex:1 1 380px;max-width:560px;display:flex;flex-direction:column;gap:16px;padding:22px">
        <div class="field"><label class="lbl" for="role">Role</label>
          <select id="role" class="select" data-k="role">${roles.map((r) => html`<option value="${r}" ${r === s.role ? 'selected' : ''}>${ROLE_LABEL[r]}</option>`)}</select>
          <div class="hint">${me.role === 'admin' ? 'Admins are not created here.' : me.role === 'state_lead' ? "State lead and Admin aren't offered: only an admin can create those." : 'Only surveyors can be added by an NGO.'}</div></div>
        ${s.role === 'state_lead' || (s.role === 'ngo' && me.role === 'admin') ? html`<div class="${fieldCls('state')}"><label class="lbl" for="state">State</label>
            <select id="state" class="select" data-k="state"><option value="">Choose a state</option>${places.states.map((x) => html`<option ${x === s.state ? 'selected' : ''}>${x}</option>`)}</select>${errHint('state')}</div>` : ''}
        ${s.role === 'ngo' && me.role !== 'admin' ? html`<div class="field"><label class="lbl">State</label><input class="input" value="${me.state}" disabled style="background:#F2F2F7;color:var(--text2)"><div class="hint">Fixed to your state.</div></div>` : ''}
        ${sv && me.role !== 'ngo' ? html`<div class="${fieldCls('ngoId')}"><label class="lbl" for="ngo">Works under NGO</label>
            <select id="ngo" class="select" data-k="ngoId"><option value="">Choose the NGO</option>${opts.ngos.map((n) => html`<option value="${n.id}" ${n.id === s.ngoId ? 'selected' : ''}>${n.name}${me.role === 'admin' && n.extra ? ` · ${n.extra}` : ''}</option>`)}</select>
            ${opts.ngos.length ? (s.ngoId ? html`<div class="hint">State: ${ngoState()}</div>` : '') : html`<div class="hint bad">There is no NGO yet. Issue an NGO login first.</div>`}${errHint('ngoId')}</div>` : ''}
        <div class="${fieldCls('fullName')}"><label class="lbl" for="nm">${s.role === 'ngo' ? 'NGO name' : 'Full name'}</label><input id="nm" class="input" data-k="fullName" value="${s.fullName}" maxlength="80" autocomplete="off">${errHint('fullName')}</div>
        <div class="${fieldCls('username')}"><label class="lbl" for="un">Username</label><input id="un" class="input" data-k="username" value="${s.username}" maxlength="32" autocapitalize="none" spellcheck="false" autocomplete="off">
          <span id="uncheck">${checkHint}</span><div class="hint">Unique across Fieldproof. Small letters, numbers and dots.${sv ? ' Surveyors use it only to be found here; they sign in with OTP.' : ' Used to sign in to the dashboard.'}</div>${errHint('username')}</div>
        <div class="${fieldCls('mobile')}"><label class="lbl" for="mob">Mobile number${sv ? '' : ' (optional)'}</label><input id="mob" class="input" data-k="mobile" value="${s.mobile}" inputmode="numeric" maxlength="16" placeholder="10-digit mobile">
          ${sv ? html`<div class="hint">The surveyor signs in on the app with an OTP sent to this number.</div>` : ''}${errHint('mobile')}</div>
        ${sv || s.role === 'ngo' ? html`<div class="${fieldCls('district')}"><label class="lbl" for="dist">District${sv ? '' : ' (optional)'}</label>
            <input id="dist" class="input" data-k="district" value="${s.district}" list="districts" autocomplete="off" placeholder="Type or pick a district">
            <datalist id="districts">${places.districts.map((d) => html`<option value="${d}">`)}</datalist>${errHint('district')}</div>` : ''}
        ${sv ? html`<div class="${fieldCls('blocks')}"><span class="lbl">Blocks they work in</span>
            ${s.knownBlocks.length ? html`<div style="display:flex;flex-wrap:wrap;gap:8px 16px;margin-bottom:8px">${s.knownBlocks.map((b) => html`<label class="check"><input type="checkbox" data-block="${b}" ${s.blocks.includes(b) ? 'checked' : ''}>${b}</label>`)}</div>` : s.district ? html`<div class="hint" style="margin:0 0 8px">No blocks known for this district yet. Type them below.</div>` : html`<div class="hint" style="margin:0 0 8px">Choose the district first.</div>`}
            <input class="input" data-k="otherBlocks" value="${s.otherBlocks}" placeholder="${s.knownBlocks.length ? 'Other blocks, separated by commas' : 'Blocks, separated by commas'}">${errHint('blocks')}</div>` : ''}
        <div id="formerr" class="error-box" ${s.errors._ ? '' : 'hidden'}>${s.errors._ || ''}</div>
        <button class="btn primary big" type="submit" ${s.busy ? 'disabled' : ''}>${s.busy ? 'Issuing…' : 'Issue login'}</button>
        <div class="small muted" style="background:#F2F2F7;border-radius:10px;padding:10px 12px;line-height:19px">${sv ? 'The surveyor can sign in on the Fieldproof app straight away with their mobile number.' : 'A one-time password is shown once after you issue the login. Share it with the person; they set their own on first sign-in.'}</div>
      </form>
      <aside id="result" style="flex:1 1 300px;max-width:400px"></aside></div>`;
  }

  const draw = () => {
    const focus = document.activeElement?.id;
    render(el, form());
    if (focus && $(`#${focus}`, el)) { const x = $(`#${focus}`, el); x.focus(); if (x.setSelectionRange && x.value) x.setSelectionRange(x.value.length, x.value.length); }
  };

  const checkUsername = debounce(async () => {
    const u = s.username.trim();
    if (!u) { s.check = null; render($('#uncheck', el), html``); return; }
    try {
      const r = await api('/v1/dash/username' + qs({ u }));
      if (r.username !== s.username.trim().toLowerCase()) return;
      s.check = r;
      render($('#uncheck', el), html`<div class="hint ${r.available ? 'ok' : 'bad'}">${r.message}</div>`);
    } catch { /* checked again on submit */ }
  }, 350);

  async function loadBlocks() {
    const d = s.district.trim();
    if (!d) { s.knownBlocks = []; return; }
    try { s.knownBlocks = (await api('/v1/dash/places' + qs({ district: d }))).blocks || []; } catch { s.knownBlocks = []; }
    s.blocks = s.blocks.filter((b) => s.knownBlocks.includes(b));
  }

  el.addEventListener('input', (e) => {
    const k = e.target.dataset.k;
    if (!k || e.target.tagName === 'SELECT') return;
    s[k] = e.target.value;
    delete s.errors[k];
    if (k === 'fullName' && !s.usernameTouched) {
      s.username = suggest(s.fullName);
      $('#un', el).value = s.username;
      checkUsername();
    }
    if (k === 'username') { s.usernameTouched = true; checkUsername(); }
  });
  el.addEventListener('change', async (e) => {
    const k = e.target.dataset.k;
    if (e.target.dataset.block !== undefined) {
      const b = e.target.dataset.block;
      s.blocks = e.target.checked ? [...s.blocks, b] : s.blocks.filter((x) => x !== b);
      delete s.errors.blocks;
      return;
    }
    if (!k) return;
    s[k] = e.target.value;
    delete s.errors[k];
    if (k === 'role') { s.errors = {}; }
    if (k === 'district') { await loadBlocks(); }
    if (['role', 'ngoId', 'district', 'state'].includes(k)) draw();
  });

  el.addEventListener('submit', async (e) => {
    e.preventDefault();
    const sv = s.role === 'surveyor';
    const blocks = [...s.blocks, ...s.otherBlocks.split(',').map((x) => x.trim()).filter(Boolean)];
    s.errors = {};
    if (s.fullName.trim().length < 2) s.errors.fullName = 'Write the full name.';
    if (!/^[a-z0-9][a-z0-9.]{1,30}[a-z0-9]$/.test(s.username.trim().toLowerCase()) || s.username.includes('..')) s.errors.username = '3 to 32 characters: small letters, numbers and dots. No dot at the start or end.';
    else if (s.check && s.check.username === s.username.trim().toLowerCase() && !s.check.available) s.errors.username = 'That username is already taken.';
    const digits = s.mobile.replace(/\D/g, '').replace(/^91(?=\d{10}$)/, '').replace(/^0(?=\d{10}$)/, '');
    if (sv && !s.mobile.trim()) s.errors.mobile = 'Surveyors need a mobile number.';
    else if (s.mobile.trim() && !/^[6-9]\d{9}$/.test(digits)) s.errors.mobile = 'Mobile number must be 10 digits, starting with 6, 7, 8 or 9.';
    if ((s.role === 'state_lead' || (s.role === 'ngo' && me.role === 'admin')) && !s.state) s.errors.state = 'Choose the state.';
    if (sv && me.role !== 'ngo' && !s.ngoId) s.errors.ngoId = 'Choose the NGO.';
    if (sv && !s.district.trim()) s.errors.district = 'Choose the district.';
    if (sv && !blocks.length) s.errors.blocks = 'Add at least one block.';
    if (Object.keys(s.errors).length) { draw(); $('.field.err .input, .field.err .select', el)?.focus(); return; }
    s.busy = true;
    draw();
    try {
      const r = await api('/v1/dash/users', { method: 'POST', body: {
        role: s.role, username: s.username.trim().toLowerCase(), fullName: s.fullName.trim(), mobile: digits,
        state: s.state, ngoId: s.ngoId, district: s.district.trim(), blocks,
      } });
      s.busy = false;
      const issued = { ...r, ngo: ngoName() };
      Object.assign(s, { fullName: '', username: '', usernameTouched: false, mobile: '', blocks: [], otherBlocks: '', check: null, errors: {} });
      draw();
      showResult(issued);
      toast('Login issued');
    } catch (ex) {
      s.busy = false;
      if (ex.body?.field) s.errors[ex.body.field] = ex.message; else s.errors._ = ex.message;
      draw();
    }
  });

  function showResult(r) {
    render($('#result', el), html`<div class="card pad" style="padding:18px 20px">
      <div style="font-size:12px;font-weight:600;letter-spacing:.04em;text-transform:uppercase;color:var(--text2)">Login issued</div>
      <div style="margin-top:10px;font-size:16px;font-weight:600">${r.fullName} · ${ROLE_LABEL[r.role]}</div>
      <div class="cred"><span class="muted">Username</span><b>${r.username}</b>
        ${r.state ? html`<span class="muted">State</span><span>${r.state}</span>` : ''}
        ${r.role === 'surveyor' ? html`<span class="muted">Signs in with</span><span>OTP to ${phone(r.mobile)}</span>` : html`<span class="muted">One-time password</span><b class="mono">${r.tempPassword}</b>`}</div>
      ${r.tempPassword ? html`<button class="btn" style="margin-top:14px" data-copy="Username: ${r.username}\nOne-time password: ${r.tempPassword}\nSign in at ${location.origin}/dash/">Copy login details</button>
        <div class="hint" style="margin-top:10px">Shown only once. They will be asked to set their own password when they first sign in.</div>`
      : html`<div class="hint" style="margin-top:10px">They can open the Fieldproof app now and sign in with their mobile number.</div>`}
    </div>`);
  }
  el.addEventListener('click', (e) => { const c = e.target.closest('[data-copy]'); if (c) copyText(c.dataset.copy); });

  draw();
}

import { api, html, render, $ } from '../lib.js';

export default async function loginPage({ el, app, navigate, query }) {
  // Already signed in? Go straight in.
  try {
    app.me = await api('/v1/dash/me', { quiet: true });
    navigate(app.me.mustChangePassword ? '/dash/password' : safeNext(query.get('next')), { replace: true, force: true });
    return;
  } catch { app.me = null; }

  render(el, html`<div class="login">
    <div class="art" role="img" aria-label="Idika agritech"><div><b>Fieldproof</b> · review every farmer the field team onboards, before planting.</div></div>
    <div class="side-form">
      <form id="f" novalidate>
        <div>
          <div style="font-size:18px;font-weight:700;margin-bottom:20px">Fieldproof</div>
          <h1>Sign in</h1>
          <div class="muted" style="margin-top:6px">Use the username and password your admin, state lead or NGO issued to you.</div>
        </div>
        <div class="field"><label class="lbl" for="u">Username</label><input id="u" class="input" autocomplete="username" autocapitalize="none" spellcheck="false" required></div>
        <div class="field"><label class="lbl" for="p">Password</label><input id="p" class="input" type="password" autocomplete="current-password" required></div>
        <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;font-size:14px">
          <label class="check"><input type="checkbox" id="r" checked>Keep me signed in</label>
          <span class="muted">Forgot password? Ask whoever issued your login</span>
        </div>
        <div id="err" class="error-box" hidden></div>
        <button class="btn primary big" id="go" type="submit">Sign in</button>
      </form>
    </div>
  </div>`);
  $('#u').focus();
  $('#f').addEventListener('submit', async (e) => {
    e.preventDefault();
    const u = $('#u').value.trim();
    const p = $('#p').value;
    const err = $('#err');
    if (!u || !p) { err.hidden = false; err.textContent = 'Enter your username and password.'; return; }
    $('#go').disabled = true;
    $('#go').textContent = 'Signing in…';
    try {
      app.me = await api('/v1/dash/login', { method: 'POST', body: { username: u, password: p, remember: $('#r').checked } });
      navigate(app.me.mustChangePassword ? '/dash/password' : safeNext(query.get('next')), { replace: true, force: true });
    } catch (ex) {
      err.hidden = false;
      err.textContent = ex.message;
      $('#p').value = '';
      $('#p').focus();
      $('#go').disabled = false;
      $('#go').textContent = 'Sign in';
    }
  });
}

// Only go back to a dashboard page after sign-in.
function safeNext(n) {
  return n && n.startsWith('/dash/') && !n.startsWith('/dash/login') && !n.includes('//') ? n : '/dash/';
}

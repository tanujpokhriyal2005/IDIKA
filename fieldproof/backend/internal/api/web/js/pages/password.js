import { api, html, render, $, toast } from '../lib.js';

export default async function passwordPage({ el, app, navigate }) {
  if (!app.me) {
    try { app.me = await api('/v1/dash/me', { quiet: true }); } catch { navigate('/dash/login', { replace: true, force: true }); return; }
  }
  const forced = app.me.mustChangePassword;
  render(el, html`<div class="login">
    <div class="art" role="img" aria-label="Idika agritech"><div><b>Fieldproof</b> · signed in as ${app.me.username}</div></div>
    <div class="side-form">
      <form id="f" novalidate>
        <div>
          <h1>${forced ? 'Set your own password' : 'Change password'}</h1>
          <div class="muted" style="margin-top:6px">${forced ? 'You signed in with a one-time password. Choose your own to continue.' : 'Other browsers where you are signed in will be signed out.'}</div>
        </div>
        <div class="field"><label class="lbl" for="c">${forced ? 'One-time password you were given' : 'Current password'}</label><input id="c" class="input" type="password" autocomplete="current-password"></div>
        <div class="field"><label class="lbl" for="n">New password</label><input id="n" class="input" type="password" autocomplete="new-password"><div class="hint">At least 8 characters, not the same as your username.</div></div>
        <div class="field"><label class="lbl" for="n2">New password again</label><input id="n2" class="input" type="password" autocomplete="new-password"></div>
        <div id="err" class="error-box" hidden></div>
        <button class="btn primary big" id="go" type="submit">Save password</button>
        ${forced ? html`<button type="button" class="linkbtn" id="out">Sign out</button>` : html`<a href="/dash/">Back to the dashboard</a>`}
      </form>
    </div>
  </div>`);
  $('#c').focus();
  $('#out')?.addEventListener('click', async () => {
    try { await api('/v1/dash/logout', { method: 'POST' }); } catch { /* ignore */ }
    app.me = null;
    navigate('/dash/login', { replace: true, force: true });
  });
  $('#f').addEventListener('submit', async (e) => {
    e.preventDefault();
    const err = $('#err');
    const show = (m) => { err.hidden = false; err.textContent = m; };
    const c = $('#c').value, n = $('#n').value, n2 = $('#n2').value;
    if (!c || !n) return show('Fill in all the fields.');
    if (n.length < 8) return show('Use at least 8 characters.');
    if (n !== n2) return show('The two new passwords are not the same.');
    $('#go').disabled = true;
    try {
      app.me = await api('/v1/dash/password', { method: 'POST', body: { current: c, new: n } });
      toast('Password saved');
      navigate('/dash/', { replace: true, force: true });
    } catch (ex) {
      show(ex.message);
      $('#go').disabled = false;
    }
  });
}

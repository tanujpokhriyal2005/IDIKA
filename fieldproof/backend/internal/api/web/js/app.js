import { api, html, raw, render, $, icon, setAuthLost, ROLE_LABEL, confirmBox, initDropdowns } from './lib.js';
import loginPage from './pages/login.js';
import passwordPage from './pages/password.js';
import overviewPage from './pages/overview.js';
import farmersPage from './pages/farmers.js';
import reviewPage from './pages/review.js';
import { ngosPage, surveyorsPage, stateLeadsPage } from './pages/people.js';
import issuePage from './pages/issue.js';
import accessPage from './pages/access.js';
import insightsPage from './pages/insights.js';
import landCheckPage from './pages/landcheck.js?v=4';

const ROUTES = [
  { path: '/dash/login', page: loginPage, public: true },
  { path: '/dash/password', page: passwordPage, pending: true },
  { path: '/dash/', page: overviewPage, nav: 'overview' },
  { path: '/dash/farmers', page: farmersPage, nav: 'farmers' },
  { path: '/dash/farmers/:id', page: reviewPage, nav: 'farmers' },
  { path: '/dash/land-check', page: landCheckPage, nav: 'landcheck' },
  { path: '/dash/state-leads', page: stateLeadsPage, nav: 'stateleads', roles: ['admin'] },
  { path: '/dash/ngos', page: ngosPage, nav: 'ngos', roles: ['admin', 'state_lead'] },
  { path: '/dash/surveyors', page: surveyorsPage, nav: 'surveyors' },
  { path: '/dash/issue', page: issuePage, nav: 'issue' },
  { path: '/dash/access', page: accessPage, nav: 'access' },
  { path: '/dash/insights', page: insightsPage, nav: 'insights' },
];

const NAV = [
  { key: 'overview', href: '/dash/', label: 'Overview' },
  { key: 'farmers', href: '/dash/farmers', label: 'Farmers', badge: true },
  { key: 'stateleads', href: '/dash/state-leads', label: 'State leads', roles: ['admin'] },
  { key: 'ngos', href: '/dash/ngos', label: 'NGOs', roles: ['admin', 'state_lead'] },
  { key: 'surveyors', href: '/dash/surveyors', label: 'Surveyors' },
  { key: 'access', href: '/dash/access', label: 'Roles and access' },
  { key: 'insights', href: '/dash/insights', label: 'Insights' },
];

export const app = { me: null, waiting: null, guard: null, cleanup: null };

function match(pathname) {
  const p = pathname.replace(/\/+$/, '') || '/dash';
  for (const r of ROUTES) {
    const rp = r.path.replace(/\/+$/, '') || '/dash';
    const keys = [];
    const re = new RegExp('^' + rp.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '$');
    const m = p.match(re);
    if (m) return { route: r, params: Object.fromEntries(keys.map((k, i) => [k, decodeURIComponent(m[i + 1])])) };
  }
  return null;
}

export async function navigate(url, { replace = false, force = false } = {}) {
  if (!force && app.guard) {
    const msg = app.guard();
    if (msg && !(await confirmBox({ title: 'Leave without saving?', text: msg, ok: 'Leave', cancel: 'Stay', danger: true }))) return;
  }
  app.guard = null;
  if (replace) history.replaceState({}, '', url); else history.pushState({}, '', url);
  route();
}

export async function refreshCounts() {
  if (!app.me || app.me.mustChangePassword) return;
  try {
    const c = await api('/v1/dash/counts');
    app.waiting = c.waiting + (c.payments || 0) + (c.plantations || 0); // everything waiting for a decision
    const b = $('#nav-badge');
    if (b) { b.textContent = c.waiting; b.hidden = !c.waiting; }
  } catch { /* badge is optional */ }
}

// refreshAgent shows on the Land check button whether the agent is running, and the
// progress of a check while one is being made.
export async function refreshAgent() {
  if (!app.me || app.me.mustChangePassword) return;
  try {
    const d = await api('/v1/dash/land-checks', { quiet: true });
    const dot = $('#agent-dot'), ring = $('#agent-ring'), sub = $('#agent-sub');
    if (!dot) return;
    const online = d.agentSeenAt && Date.now() - new Date(d.agentSeenAt).getTime() < 2 * 60 * 1000;
    dot.classList.toggle('off', !online);
    dot.title = online ? 'The land check agent is running' : 'The land check agent is not running';
    const run = d.items.find((c) => c.status === 'running');
    if (run) {
      const p = Math.max(1, Math.min(99, run.progress || 1));
      sub.textContent = `Checking ${run.parcels} parcel${run.parcels === 1 ? '' : 's'} · ${p}%`;
      ring.style.setProperty('--p', p);
    } else {
      sub.textContent = 'AI agent · satellite check';
    }
    ring.hidden = !run;
    dot.hidden = !!run;
  } catch { /* the button works without its status */ }
}

function shell(nav) {
  const me = app.me;
  const items = NAV.filter((n) => !n.roles || n.roles.includes(me.role));
  const where = me.role === 'admin' ? 'All India' : me.role === 'ngo' ? `${me.state || ''}` : me.state || '';
  render($('#app'), html`<div class="shell">
    <nav class="side" id="side" aria-label="Main">
      <a class="brand" href="/dash/"><span class="logo">${icon.leaf}</span><b>Fieldproof</b></a>
      ${items.map((n) => html`<a class="nav ${nav === n.key ? 'on' : ''}" href="${n.href}" ${nav === n.key ? raw('aria-current="page"') : ''}>${n.label}${n.badge ? html`<span class="badge" id="nav-badge" ${app.waiting ? '' : raw('hidden')}>${app.waiting ?? ''}</span>` : ''}</a>`)}
      <a class="issue-btn ${nav === 'issue' ? 'on' : ''}" href="/dash/issue">${icon.plus}Issue a login</a>
      <a class="agent-btn ${nav === 'landcheck' ? 'on' : ''}" id="agent-btn" href="/dash/land-check" ${nav === 'landcheck' ? raw('aria-current="page"') : ''}>
        <span class="agent-face" id="agent-face"><span class="agent-txt"><b>Land check</b><span id="agent-sub">AI agent · satellite check</span></span>
          <span class="agent-dot" id="agent-dot" title="Agent status"></span><span class="agent-ring" id="agent-ring" hidden></span></span></a>
      <div class="me">
        <div class="nm">${me.fullName}</div>
        <div class="rl">${ROLE_LABEL[me.role]}${where ? ` · ${where}` : ''}</div>
        <div class="rl">${me.username}</div>
        <div class="links"><a href="/dash/password">Change password</a><button id="signout">Sign out</button></div>
      </div>
    </nav>
    <button class="btn menu-toggle" id="menu" style="position:fixed;top:12px;right:12px;z-index:31" aria-controls="side">${icon.menu} Menu</button>
    <main class="main" id="main"></main>
  </div>`);
  $('#menu').addEventListener('click', () => $('#side').classList.toggle('open'));
  // The soft glow on the Land check button follows the mouse.
  const face = $('#agent-face');
  face.addEventListener('mousemove', (e) => {
    const r = face.getBoundingClientRect();
    face.style.setProperty('--mx', `${e.clientX - r.left}px`);
    face.style.setProperty('--my', `${e.clientY - r.top}px`);
  });
  $('#signout').addEventListener('click', async () => {
    try { await api('/v1/dash/logout', { method: 'POST' }); } catch { /* signed out anyway */ }
    app.me = null;
    navigate('/dash/login', { replace: true, force: true });
  });
  return $('#main');
}

async function route() {
  if (app.cleanup) { try { app.cleanup(); } catch { /* ignore */ } app.cleanup = null; }
  const m = match(location.pathname);
  if (!m) { navigate('/dash/', { replace: true, force: true }); return; }
  const { route: r, params } = m;
  if (!r.public && !app.me) {
    try { app.me = await api('/v1/dash/me', { quiet: true }); } catch { app.me = null; }
    if (!app.me) { navigate(`/dash/login${location.pathname !== '/dash/' ? `?next=${encodeURIComponent(location.pathname + location.search)}` : ''}`, { replace: true, force: true }); return; }
  }
  if (app.me?.mustChangePassword && !r.pending && !r.public) { navigate('/dash/password', { replace: true, force: true }); return; }
  if (r.roles && !r.roles.includes(app.me.role)) { navigate('/dash/', { replace: true, force: true }); return; }
  const query = new URLSearchParams(location.search);
  let el;
  if (r.public || r.pending) {
    el = $('#app');
  } else {
    el = shell(r.nav);
    refreshCounts();
    refreshAgent();
  }
  document.title = 'Fieldproof dashboard';
  window.scrollTo(0, 0);
  const ctx = { el, params, query, app, navigate, setGuard: (g) => { app.guard = g; } };
  try {
    app.cleanup = (await r.page(ctx)) || null;
  } catch (e) {
    console.error(e);
    render(el, html`<div class="error-box">This page could not load: ${e.message}</div>`);
  }
}

setAuthLost((err) => {
  if (err.code === 'password_change_required') { if (app.me) app.me.mustChangePassword = true; navigate('/dash/password', { replace: true, force: true }); return; }
  if (location.pathname !== '/dash/login') { app.me = null; navigate('/dash/login', { replace: true, force: true }); }
});

document.addEventListener('click', (e) => {
  const a = e.target.closest('a');
  if (!a || a.target || a.hasAttribute('download') || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  const url = new URL(a.href, location.href);
  if (url.origin !== location.origin || !url.pathname.startsWith('/dash')) return;
  e.preventDefault();
  $('#side')?.classList.remove('open');
  if (url.pathname + url.search !== location.pathname + location.search) navigate(url.pathname + url.search);
});
window.addEventListener('popstate', () => { app.guard = null; route(); });
window.addEventListener('beforeunload', (e) => { if (app.guard && app.guard()) { e.preventDefault(); e.returnValue = ''; } });

initDropdowns();
route();

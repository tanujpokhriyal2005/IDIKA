import { api, html, raw, render, $, fmtDateTime, ago, toast, errorBox, confirmBox } from '../lib.js';
import { refreshAgent } from '../app.js';

// Land check: upload a KML, the land check agent checks every parcel's satellite
// history and leaves a report here.

const VERDICTS = [['Flag', 'p-rejected'], ['A person checks', 'p-pending'], ['Approve', 'p-approved']];

function agentLine(seenAt) {
  const online = seenAt && Date.now() - new Date(seenAt).getTime() < 2 * 60 * 1000;
  return online
    ? html`<span class="lc-dot on"></span>The land check agent is running.`
    : html`<span class="lc-dot"></span>The land check agent is not running${seenAt ? html` (last seen ${ago(seenAt)})` : ''}. Uploaded checks wait until it starts.`;
}

// While the agent works: a bar from 0 to 100 %, parcels done, and a rough time left
// worked out from how fast it has gone so far.
function progressBar(c) {
  const p = Math.max(1, Math.min(99, c.progress || 1));
  const secs = (Date.now() - new Date(c.startedAt).getTime()) / 1000;
  let left = '';
  if (p >= 5 && secs > 30) {
    const min = Math.ceil((secs * (100 - p)) / p / 60);
    left = min <= 1 ? ' · about a minute left' : ` · about ${min} min left`;
  }
  return html`<div class="lc-prog">
    <div class="lc-prog-row"><span class="pill p-new">Checking…</span>
      <div class="lc-bar" role="progressbar" aria-valuenow="${p}" aria-valuemin="0" aria-valuemax="100" aria-label="Checking"><span style="width:${p}%"></span></div>
      <b class="lc-pct">${p}%</b></div>
    <div class="muted small">${p}% completed · ${c.parcelsDone} of ${c.parcels} parcels done${left}</div></div>`;
}

function result(c) {
  if (c.status === 'done') {
    const pills = VERDICTS.filter(([v]) => c.summary?.[v]).map(([v, cls]) => html`<span class="pill ${cls}">${c.summary[v]} ${v}</span>`);
    return html`<div class="lc-result">${pills}
      <a class="btn sm primary" href="/v1/dash/land-checks/${c.id}/report" target="_blank" rel="noopener">Open report</a>
      <a class="btn sm" href="/v1/dash/land-checks/${c.id}/report?download=1" download>Download</a></div>`;
  }
  if (c.status === 'running') return progressBar(c);
  if (c.status === 'stopped') return html`<span class="pill p-off">Stopped</span> <span class="muted small">This check was stopped before it finished.</span>`;
  if (c.status === 'paused') return html`<span class="pill p-pending">Paused</span> <span class="muted small">Resume starts the check again from the beginning.</span>`;
  if (c.status === 'failed') return html`<span class="pill p-rejected">Failed</span> <span class="muted small">${c.error || 'Something went wrong. Try uploading again.'}</span>`;
  return html`<span class="pill p-off">Waiting</span> <span class="muted small">${c.inLine > 1 ? `${c.inLine - 1} ahead in line` : 'next in line'}</span>`;
}

// Pause / resume / delete, for the uploader or an admin.
function actions(c) {
  if (!c.canManage) return '';
  const b = (act, label, title, cls = '') => html`<button class="lc-act ${cls}" data-act="${act}" data-id="${c.id}" data-name="${c.name}" title="${title}" aria-label="${title}">${raw(label)}</button>`;
  const pause = '<svg viewBox="0 0 16 16" width="14" height="14"><rect x="3" y="2" width="3.5" height="12" rx="1"/><rect x="9.5" y="2" width="3.5" height="12" rx="1"/></svg>';
  const play = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M4 2.5v11a1 1 0 0 0 1.5.85l9-5.5a1 1 0 0 0 0-1.7l-9-5.5A1 1 0 0 0 4 2.5z"/></svg>';
  const bin = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M6 1.5h4a1 1 0 0 1 1 1V3h3v1.5H2V3h3v-.5a1 1 0 0 1 1-1zM3.5 5.5h9l-.7 8.1a1.5 1.5 0 0 1-1.5 1.4H5.7a1.5 1.5 0 0 1-1.5-1.4z"/></svg>';
  return html`<div class="lc-acts">
    ${c.status === 'queued' || c.status === 'running' ? b('pause', pause, 'Pause this check') : ''}
    ${['paused', 'stopped', 'failed'].includes(c.status) ? b('resume', play, 'Resume (starts again from the beginning)', 'play') : ''}
    ${b('delete', bin, 'Delete this check', 'del')}</div>`;
}

function list(data) {
  if (!data.items.length) {
    return html`<div class="empty"><b>No checks yet</b>Upload a KML above and the report will appear here.</div>`;
  }
  return html`<div class="tw"><table>
    <tr><th>Check</th><th>Uploaded by</th><th>Uploaded</th><th>Parcels</th><th>Report</th></tr>
    ${data.items.map((c) => html`<tr>
      <td><b>${c.name}</b><div class="muted small">${c.fileName}</div></td>
      <td>${c.createdBy}</td>
      <td>${fmtDateTime(c.createdAt)}</td>
      <td>${c.parcels}</td>
      <td><div class="lc-cell">${result(c)}${actions(c)}</div></td></tr>`)}
  </table></div>`;
}

// countParcels reads a .kml in the browser just to show how many parcels it holds.
function countParcels(text) {
  try {
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.getElementsByTagName('parsererror').length) return null;
    return doc.getElementsByTagNameNS('*', 'Polygon').length;
  } catch { return null; }
}

const toBase64 = (buf) => {
  let s = '';
  const bytes = new Uint8Array(buf);
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
};

export default async function landCheckPage({ el }) {
  render(el, html`
    <div class="head"><div><h1>Land check</h1><div class="sub">Upload a KML and the land check agent reads each parcel's satellite history from 2017 to today: tree clearing, buildings, water, and what the land is now.</div></div></div>
    <div class="card"><div class="ch"><h2>Upload parcels</h2></div>
      <div class="pad lc-upload">
        <label class="lc-drop" id="drop">
          <input type="file" id="file" accept=".kml,.kmz" hidden>
          <span class="lc-kml">KML</span>
          <b>Drop a KML or KMZ file here</b>
          <span>or <u>choose a file</u></span>
          <span class="hint">Drawn in Google Earth, exported from Fieldproof, or any KML with polygons · up to 50 parcels · about 2 minutes per parcel</span>
        </label>
        <div id="chosen"></div>
        <div class="field"><label class="lbl" for="lc-name">Name this check</label><input class="input" id="lc-name" maxlength="80" placeholder="e.g. Aligarh test batch"></div>
        <div class="lc-go"><button class="btn primary" id="start" disabled>Start check</button><span class="hint" style="margin:0">Uses free satellite data. Nothing is sent to farmers or surveyors.</span></div>
      </div></div>
    <div class="card"><div class="ch"><div><h2>Checks</h2><div class="sub" id="agent"></div></div></div><div id="list"><div class="loading">Loading…</div></div></div>`);

  let chosen = null; // { fileName, kml?, kmz?, parcels }
  let timer = null;
  let alive = true;

  const showChosen = (msg, bad = false) => {
    render($('#chosen'), msg ? html`<div class="lc-chip ${bad ? 'bad' : ''}">${msg}${chosen ? html` <button class="linkbtn" id="clear">Remove</button>` : ''}</div>` : html``);
    $('#clear')?.addEventListener('click', (e) => { e.preventDefault(); chosen = null; $('#file').value = ''; showChosen(''); $('#start').disabled = true; });
  };

  const pick = async (file) => {
    chosen = null;
    $('#start').disabled = true;
    if (!file) return;
    const ext = file.name.toLowerCase().split('.').pop();
    if (ext !== 'kml' && ext !== 'kmz') { showChosen(`${file.name} is not a KML or KMZ file.`, true); return; }
    if (file.size > 5 * 1024 * 1024) { showChosen(`${file.name} is too big. Keep it under 5 MB.`, true); return; }
    if (ext === 'kml') {
      const text = await file.text();
      const n = countParcels(text);
      if (n === null) { showChosen(`${file.name} doesn't look like a KML file.`, true); return; }
      if (!n) { showChosen(`No parcels in ${file.name}: it needs at least one polygon (a closed boundary).`, true); return; }
      if (n > 50) { showChosen(`${file.name} has ${n} parcels. Check at most 50 at a time.`, true); return; }
      chosen = { fileName: file.name, kml: text };
      showChosen(html`<b>${file.name}</b> · ${n} parcel${n === 1 ? '' : 's'} found`);
    } else {
      chosen = { fileName: file.name, kmz: toBase64(await file.arrayBuffer()) };
      showChosen(html`<b>${file.name}</b> · KMZ file, parcels are counted on upload`);
    }
    if (!$('#lc-name').value.trim()) $('#lc-name').value = file.name.replace(/\.(kml|kmz)$/i, '');
    $('#start').disabled = false;
  };

  const drop = $('#drop');
  $('#file').addEventListener('change', (e) => pick(e.target.files[0]));
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); pick(e.dataTransfer.files[0]); });

  const load = async () => {
    clearTimeout(timer);
    try {
      const data = await api('/v1/dash/land-checks');
      if (!alive) return;
      render($('#agent'), agentLine(data.agentSeenAt));
      render($('#list'), list(data));
      refreshAgent(); // keep the sidebar button's progress in step
      // Keep the list fresh while something is waiting or being checked; faster while a bar is moving.
      if (data.items.some((c) => c.status === 'running')) timer = setTimeout(load, 4000);
      else if (data.items.some((c) => c.status === 'queued')) timer = setTimeout(load, 10000);
    } catch (e) {
      if (!alive) return;
      render($('#list'), errorBox(e, true));
      $('[data-retry]')?.addEventListener('click', load);
    }
  };

  $('#list').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const { act, id, name } = btn.dataset;
    if (act === 'delete' && !(await confirmBox({ title: 'Delete this check?', text: `"${name}" and its report will be removed. If the agent is checking it, it stops.`, ok: 'Delete', danger: true }))) return;
    btn.disabled = true;
    try {
      if (act === 'delete') await api(`/v1/dash/land-checks/${id}`, { method: 'DELETE' });
      else await api(`/v1/dash/land-checks/${id}/${act}`, { method: 'POST' });
      toast({ pause: 'Paused.', resume: 'Back in line: the check starts again from the beginning.', delete: 'Deleted.' }[act]);
    } catch (err) {
      toast(err.message, true);
    }
    load();
  });

  $('#start').addEventListener('click', async () => {
    if (!chosen) return;
    const b = $('#start');
    b.disabled = true;
    b.textContent = 'Uploading…';
    try {
      const c = await api('/v1/dash/land-checks', { method: 'POST', body: { name: $('#lc-name').value, ...chosen } });
      toast(`Check started: ${c.parcels} parcel${c.parcels === 1 ? '' : 's'}. The report will appear below.`);
      chosen = null;
      $('#file').value = '';
      $('#lc-name').value = '';
      showChosen('');
      load();
    } catch (e) {
      toast(e.message, true);
      b.disabled = false;
    } finally {
      b.textContent = 'Start check';
    }
  });

  load();
  return () => { alive = false; clearTimeout(timer); };
}

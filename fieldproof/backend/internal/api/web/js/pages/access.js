import { html, render } from '../lib.js';

export default function accessPage({ el, app }) {
  const r = app.me.role;
  const node = (role, title, sub) => html`<div class="node ${r === role ? 'me-node' : ''}"><b>${title}${r === role ? ' · you' : ''}</b><span>${sub}</span></div>`;
  render(el, html`
    <div class="head"><div><h1>Roles and access</h1><div class="sub">Each role can do everything the roles below it can, and nothing above it.</div></div></div>
    <div class="chain">
      ${node('admin', 'Admin', 'All of India')}<span aria-hidden="true" class="muted">→</span>
      ${node('state_lead', 'State lead', 'One state, set by the admin')}<span aria-hidden="true" class="muted">→</span>
      ${node('ngo', 'NGO', 'Inside one state')}<span aria-hidden="true" class="muted">→</span>
      ${node('surveyor', 'Surveyor', 'Under one NGO · phone app')}
    </div>
    <div class="card"><div class="ch"><div><h2>Who can issue logins</h2><div class="sub">Issue = create the account and hand over the first password (or the mobile number for OTP).</div></div></div>
      <div class="tw"><table>
        <tr><th>Signed in as</th><th>State lead</th><th>NGO</th><th>Surveyor</th><th>Where</th></tr>
        <tr><td><b>Admin</b></td><td class="y">Yes</td><td class="y">Yes</td><td class="y">Yes</td><td>Any state (picks the state when creating a state lead)</td></tr>
        <tr><td><b>State lead</b></td><td class="n">No</td><td class="y">Yes</td><td class="y">Yes</td><td>Own state only</td></tr>
        <tr><td><b>NGO</b></td><td class="n">No</td><td class="n">No</td><td class="y">Yes</td><td>Under itself only</td></tr>
        <tr><td><b>Surveyor</b></td><td class="n">No</td><td class="n">No</td><td class="n">No</td><td>—</td></tr>
      </table></div></div>
    <div class="card"><div class="ch"><div><h2>Who sees what</h2><div class="sub">A farmer becomes visible on the dashboard once onboarding is finished and synced.</div></div></div>
      <div class="tw"><table>
        <tr><th>Signed in as</th><th>NGOs and surveyors</th><th>Farmers</th><th>Review (approve, reject, pending)</th></tr>
        <tr><td><b>Admin</b></td><td>Every state lead, NGO and surveyor</td><td>Every farmer in India</td><td class="y">Yes, any farmer</td></tr>
        <tr><td><b>State lead</b></td><td>NGOs and surveyors of their state</td><td>Farmers of their state</td><td class="y">Yes, their state</td></tr>
        <tr><td><b>NGO</b></td><td>Their own surveyors</td><td>Farmers onboarded by their surveyors</td><td class="y">Yes, their surveyors' farmers</td></tr>
        <tr><td><b>Surveyor</b></td><td>—</td><td>Only farmers they onboarded (phone app)</td><td class="n">No · receives the tasks</td></tr>
      </table></div></div>
    <div class="card"><div class="ch"><h2>Review rules</h2></div>
      <div class="pad" style="font-size:14px;line-height:23px">
        Reviewed separately: <b>Aadhaar</b>, <b>land record</b>, <b>water source photo</b>, <b>boundary KML</b>.<br>
        <b>Approve</b> needs no message. <b>Reject</b> and <b>Pending</b> cannot be saved without a message.<br>
        On save, each Reject or Pending item becomes one action item, with a red dot, on the phone of <b>the surveyor who onboarded that farmer</b>, and nobody else.<br>
        When the surveyor re-uploads, the item goes back to <b>New</b> for review. The latest decision wins; every decision stays in the history.
      </div></div>`);
}

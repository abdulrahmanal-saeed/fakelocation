import { initializeApp } from 'https://www.gstatic.com/firebasejs/12.6.0/firebase-app.js';
import { getAuth, GoogleAuthProvider, inMemoryPersistence, setPersistence, signInWithPopup }
  from 'https://www.gstatic.com/firebasejs/12.6.0/firebase-auth.js';

const status = document.querySelector('#status');
const reports = document.querySelector('#reports');
const signIn = document.querySelector('#signin');
const refresh = document.querySelector('#refresh');
const app = initializeApp(await (await fetch('/__/firebase/init.json')).json());
const auth = getAuth(app);
await setPersistence(auth, inMemoryPersistence);
const services = await (await fetch('./services.json')).json();

async function request(path, payload) {
  const user = auth.currentUser;
  if (!user || !services.api) throw new Error();
  const response = await fetch(new URL(path, services.api), {
    method: payload ? 'POST' : 'GET',
    headers: { Authorization: 'Bearer ' + await user.getIdToken(), 'Content-Type': 'application/json' },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  if (!response.ok) throw new Error();
  return response.json();
}

async function loadReports() {
  try {
    const { incidents } = await request('/v1/incidents/moderation');
    reports.replaceChildren();
    for (const report of incidents) {
      const row = document.createElement('li');
      row.textContent = [report.kind, report.road, report.lat.toFixed(5) + ', ' + report.lon.toFixed(5),
        report.confirmed ? 'Confirmed' : 'Pending', report.flags + ' flags'].join(' · ') + ' ';
      const remove = document.createElement('button');
      remove.textContent = 'Remove report';
      remove.onclick = async () => {
        if (!window.confirm('Remove this road report?')) return;
        try {
          await request('/v1/incidents/remove', { id: report.id });
          await loadReports();
        } catch { status.textContent = 'The report could not be removed.'; }
      };
      row.append(remove);
      reports.append(row);
    }
    status.textContent = incidents.length ? '' : 'No active reports.';
  } catch { status.textContent = 'Sign in with the WayShift moderator account and try again.'; }
}

refresh.onclick = loadReports;
signIn.onclick = async () => {
  try {
    await signInWithPopup(auth, new GoogleAuthProvider());
    refresh.hidden = false;
    await loadReports();
  } catch { status.textContent = 'Google sign-in could not be completed.'; }
};

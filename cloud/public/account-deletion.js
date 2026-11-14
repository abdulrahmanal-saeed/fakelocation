import { initializeApp } from 'https://www.gstatic.com/firebasejs/12.6.0/firebase-app.js';
import {
  deleteUser, getAuth, GoogleAuthProvider, inMemoryPersistence, setPersistence,
  signInWithEmailAndPassword, signInWithPopup,
} from 'https://www.gstatic.com/firebasejs/12.6.0/firebase-auth.js';
import { doc, getFirestore, writeBatch } from 'https://www.gstatic.com/firebasejs/12.6.0/firebase-firestore.js';

const status = document.querySelector('#status');
const form = document.querySelector('#signin');
const google = document.querySelector('#google');
const remove = document.querySelector('#remove');
const response = await fetch('/__/firebase/init.json');
if (!response.ok) throw new Error('Firebase Hosting configuration unavailable');
const app = initializeApp(await response.json());
const auth = getAuth(app);
await setPersistence(auth, inMemoryPersistence);
const db = getFirestore(app);
const configuration = await fetch('./services.json').then(r => r.ok ? r.json() : {});

let busy = false;
async function perform(action) {
  if (busy) return;
  busy = true;
  try {
    await action();
  } catch (error) {
    status.textContent = error.code === 'auth/requires-recent-login'
      ? 'Sign in again before deleting your account.'
      : 'The request could not be completed. Check your sign-in and try again.';
  } finally {
    busy = false;
  }
}

function signedIn(user) {
  document.querySelector('#password').value = '';
  status.textContent = 'Signed in as ' + (user.email ?? 'WayShift user');
  remove.hidden = false;
}
form.addEventListener('submit', event => {
  event.preventDefault();
  perform(async () => signedIn((await signInWithEmailAndPassword(
    auth, document.querySelector('#email').value, document.querySelector('#password').value
  )).user));
});
google.addEventListener('click', () => perform(async () => signedIn((await signInWithPopup(auth, new GoogleAuthProvider())).user)));
remove.addEventListener('click', () => perform(async () => {
  const user = auth.currentUser;
  if (!user || !window.confirm('Permanently delete your WayShift account?')) return;
  const idToken = await user.getIdToken(true);
  if (configuration.api) {
    const result = await fetch(new URL('/v1/account/delete', configuration.api), {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + idToken, 'Content-Type': 'application/json' },
      body: '{}',
    });
    if (!result.ok) throw new Error('Deletion service unavailable');
  }
  const profile = doc(db, 'users', user.uid);
  const batch = writeBatch(db);
  batch.set(profile, { deleting: true });
  for (const group of ['core', 'history', 'places', 'parking']) batch.delete(doc(profile, 'backups', group));
  await batch.commit();
  await deleteUser(user);
  form.hidden = google.hidden = remove.hidden = true;
  status.textContent = 'Your WayShift account and cloud backups were deleted.';
}));

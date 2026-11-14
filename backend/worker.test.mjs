import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { after, before, test } from 'node:test';
import worker from './worker.mjs';

const sql = new DatabaseSync(':memory:');
sql.exec(readFileSync(new URL('./migrations/0001_wayshift.sql', import.meta.url), 'utf8'));
const DB = {
  prepare(query) {
    const statement = sql.prepare(query);
    const bind = (...values) => ({
      async first() { return statement.get(...values) ?? null; },
      async all() { return { results: statement.all(...values) }; },
      async run() { return { meta: statement.run(...values) }; },
    });
    return { bind, ...bind() };
  },
  async batch(statements) {
    sql.exec('BEGIN');
    try {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      sql.exec('COMMIT');
      return results;
    } catch (error) {
      sql.exec('ROLLBACK');
      throw error;
    }
  },
};
const env = {
  DB, FIREBASE_PROJECT_ID: 'wayshift-tests', RATE_SALT: 'test',
  RESEND_API_KEY: 'test', FEEDBACK_FROM: 'testing@example.com', FEEDBACK_TO: 'owner@example.com',
  MODERATOR_EMAIL: 'moderator@example.com',
};
const originalFetch = globalThis.fetch;
const sent = new Map();
let failFirstEmail = false;
const key = await crypto.subtle.generateKey({
  name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
  publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256',
}, true, ['sign', 'verify']);
const jwk = { ...await crypto.subtle.exportKey('jwk', key.publicKey), kid: 'wayshift-test' };

before(() => {
  globalThis.fetch = async (url, options) => {
    if (url.startsWith('https://www.googleapis.com/service_accounts/')) return Response.json({ keys: [jwk] });
    if (url === 'https://api.resend.com/emails') {
      if (failFirstEmail) { failFirstEmail = false; return new Response('', { status: 503 }); }
      sent.set(options.headers['Idempotency-Key'], JSON.parse(options.body));
      return Response.json({ id: 'test-delivery' });
    }
    throw new Error('Unexpected network request');
  };
});
after(() => { globalThis.fetch = originalFetch; });

async function token(uid, verified = true) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const time = Math.floor(Date.now() / 1000);
  const content = encode({ alg: 'RS256', kid: jwk.kid }) + '.' + encode({
    sub: uid, aud: env.FIREBASE_PROJECT_ID, iss: 'https://securetoken.google.com/' + env.FIREBASE_PROJECT_ID,
    exp: time + 1200, iat: time, auth_time: time, email_verified: verified, email: uid + '@example.com',
  });
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key.privateKey, Buffer.from(content));
  return content + '.' + Buffer.from(signature).toString('base64url');
}

async function request(path, body, uid, verified) {
  const jobs = [];
  const headers = { 'CF-Connecting-IP': '203.0.113.45' };
  if (uid) headers.Authorization = 'Bearer ' + await token(uid, verified);
  const result = await worker.fetch(new Request('https://test.example' + path, {
    method: body ? 'POST' : 'GET', headers, body: body && JSON.stringify(body),
  }), env, { waitUntil: promise => jobs.push(promise) });
  await Promise.all(jobs);
  return { status: result.status, data: await result.json() };
}

test('feedback is saved before sending, retried, and repeated submissions keep the same ticket', async () => {
  const id = crypto.randomUUID();
  const body = { id, category: 'map', message: 'Test road label' };
  failFirstEmail = true;
  assert.deepEqual(await request('/v1/feedback', body), { status: 202, data: { id } });
  assert.equal(sent.size, 0);
  assert.equal(sql.prepare('SELECT attempts FROM feedback WHERE id=?').get(id).attempts, 1);
  sql.prepare('UPDATE feedback SET next_attempt=0 WHERE id=?').run(id);
  await worker.scheduled({}, env);
  assert.equal(sent.get('wayshift-feedback-' + id).text, body.message);
  assert.equal((await request('/v1/feedback', body)).status, 202);
  assert.equal(sent.size, 1);
  assert.equal(sql.prepare('SELECT COUNT(*) count FROM feedback WHERE id=?').get(id).count, 1);
});

test('reports require a verified account, matching direction and an independent driver', async () => {
  const id = crypto.randomUUID();
  const position = { lat: 25.2, lon: 55.27, heading: 90, road: 'test road' };
  const body = { ...position, id, kind: 'traffic' };
  assert.equal((await request('/v1/incidents', body, 'driver-1', false)).status, 401);
  assert.equal((await request('/v1/incidents', body, 'driver-1')).status, 201);
  const dupe = await request('/v1/incidents', { ...body, id: crypto.randomUUID() }, 'driver-2');
  assert.deepEqual(dupe.data, { id, duplicate: true });
  const query = '/v1/incidents?lat=25.2&lon=55.27';
  assert.deepEqual((await request(query)).data.incidents, []);
  assert.equal((await request(query, undefined, 'driver-2')).data.incidents[0].confirmed, false);
  const vote = { ...position, id, vote: 'present' };
  assert.equal((await request('/v1/incidents/vote', vote, 'driver-1')).status, 409);
  assert.equal((await request('/v1/incidents/vote', { ...vote, heading: 270 }, 'driver-2')).status, 409);
  assert.equal((await request('/v1/incidents/vote', vote, 'driver-2')).status, 200);
  assert.equal((await request(query)).data.incidents[0].confirmed, true);
  const event = sql.prepare('SELECT expires,confirmed FROM incidents WHERE id=?').get(id);
  assert.equal(event.expires - event.confirmed, 30 * 60_000);
  assert.equal((await request('/v1/incidents/abuse', { id }, 'driver-3')).status, 200);
  assert.equal((await request('/v1/incidents/moderation', undefined, 'driver-3')).status, 403);
  assert.equal((await request('/v1/incidents/moderation', undefined, 'moderator')).data.incidents[0].flags, 1);
  assert.equal((await request('/v1/incidents/remove', { id }, 'moderator')).status, 200);
  assert.deepEqual((await request(query)).data.incidents, []);
});

test('expired reports are hidden and cannot be confirmed', async () => {
  const id = crypto.randomUUID();
  const position = { lat: 30.04, lon: 31.23, heading: 45, road: 'test cairo road' };
  assert.equal((await request('/v1/incidents', { ...position, id, kind: 'closure' }, 'driver-4')).status, 201);
  sql.prepare('UPDATE incidents SET expires=? WHERE id=?').run(Date.now() - 1, id);
  assert.deepEqual((await request('/v1/incidents?lat=30.04&lon=31.23', undefined, 'driver-5')).data.incidents, []);
  assert.equal((await request('/v1/incidents/vote', { ...position, id, vote: 'present' }, 'driver-5')).status, 409);
});

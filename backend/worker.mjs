const encoder = new TextEncoder();
const lifetimes = { traffic: 30, crash: 60, hazard: 60, closure: 120 };
const feedbackCategories = new Set(["speed", "map", "idea", "other"]);
const json = (body, status = 200) => Response.json(body, {
  status, headers: { "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" },
});
const now = () => Date.now();
const ms = minutes => minutes * 60_000;
const hash = async value => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))),
  b => b.toString(16).padStart(2, "0")).join("");
const decode = value => Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0));
const encode = value => btoa(String.fromCharCode(...new Uint8Array(value))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
let firebaseKeys;
let firebaseKeysUntil = 0;
let playAccess;
let playAccessUntil = 0;

async function actor(request, env) {
  const token = request.headers.get("Authorization")?.match(/^Bearer ([\w.-]+)$/)?.[1];
  if (!token || !env.FIREBASE_PROJECT_ID) return null;
  try {
    const [header, payload, signature] = token.split(".");
    const meta = JSON.parse(new TextDecoder().decode(decode(header)));
    const claims = JSON.parse(new TextDecoder().decode(decode(payload)));
    const time = Math.floor(now() / 1000);
    if (meta.alg !== "RS256" || claims.aud !== env.FIREBASE_PROJECT_ID ||
        claims.iss !== "https://securetoken.google.com/" + env.FIREBASE_PROJECT_ID ||
        !claims.sub || claims.sub.length > 128 || !(claims.exp > time) || !(claims.iat <= time) ||
        !(claims.auth_time <= time)) return null;
    if (!firebaseKeys || firebaseKeysUntil < now() || !firebaseKeys.some(key => key.kid === meta.kid)) {
      const response = await fetch("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com");
      if (!response.ok) return null;
      firebaseKeys = (await response.json()).keys;
      firebaseKeysUntil = now() + ms(30);
    }
    const jwk = firebaseKeys.find(key => key.kid === meta.kid);
    if (!jwk) return null;
    const algorithm = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
    const key = await crypto.subtle.importKey("jwk", jwk, algorithm, false, ["verify"]);
    if (!await crypto.subtle.verify(algorithm.name, key, decode(signature), encoder.encode(header + "." + payload))) return null;
    return { id: claims.sub, email: claims.email_verified ? claims.email : null, recent: time - claims.auth_time < 300 };
  } catch {
    return null;
  }
}

async function body(request) {
  if (Number(request.headers.get("Content-Length")) > 8192) throw new RangeError("too_large");
  const value = await request.text();
  if (value.length > 8192) throw new RangeError("too_large");
  return JSON.parse(value);
}

function validPoint(lat, lon) {
  return Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
}

function near(row, point) {
  const rad = Math.PI / 180;
  const dy = (row.lat - point.lat) * 111195;
  const dx = (row.lon - point.lon) * Math.cos(point.lat * rad) * 111195;
  const turn = Math.abs((row.heading - point.heading + 540) % 360 - 180);
  return Math.hypot(dx, dy) <= 45 && turn <= 30;
}

function publicIncident(row) {
  return { id: row.id, kind: row.kind, road: row.road, lat: row.lat, lon: row.lon, heading: row.heading, confirmed: row.confirmed !== null };
}

async function emailFeedback(env, ticket) {
  if (!env.RESEND_API_KEY || !env.FEEDBACK_FROM || !env.FEEDBACK_TO) return;
  const item = await env.DB.prepare("SELECT * FROM feedback WHERE id=? AND mailed IS NULL").bind(ticket).first();
  if (!item) return;
  try {
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { Authorization: "Bearer " + env.RESEND_API_KEY, "Content-Type": "application/json",
        "Idempotency-Key": "wayshift-feedback-" + item.id },
      body: JSON.stringify({
        from: env.FEEDBACK_FROM, to: [env.FEEDBACK_TO],
        subject: "WayShift feedback " + item.id.slice(0, 8) + " (" + item.category + ")",
        text: [item.message, item.reply_email && "Reply email: " + item.reply_email,
          item.lat !== null && "Location: " + item.lat + ", " + item.lon].filter(Boolean).join("\n\n"),
      }),
    });
    if (response.ok) {
      await env.DB.prepare("UPDATE feedback SET mailed=? WHERE id=?").bind(now(), ticket).run();
      return;
    }
  } catch {}
  const delay = Math.min(360, 5 * 2 ** Math.min(item.attempts, 7));
  await env.DB.prepare("UPDATE feedback SET attempts=attempts+1,next_attempt=? WHERE id=? AND mailed IS NULL")
    .bind(now() + ms(delay), ticket).run();
}

async function saveFeedback(request, env, ctx, user) {
  const input = await body(request);
  const { id, category, lat, lon } = input;
  const message = typeof input.message === "string" ? input.message.trim() : "";
  const email = typeof input.email === "string" && input.email.trim() ? input.email.trim() : null;
  if (!/^[0-9a-f-]{36}$/.test(id ?? "") || !feedbackCategories.has(category) || message.length < 5 || message.length > 2000 ||
      (email && (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) ||
      ((lat != null || lon != null) && !validPoint(lat, lon))) return json({ error: "invalid_feedback" }, 400);
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const sender = user?.id ?? "guest:" + await hash((env.RATE_SALT ?? "") + ip);
  const result = await env.DB.prepare("INSERT OR IGNORE INTO feedback (id,category,message,reply_email,lat,lon,actor,created) SELECT ?,?,?,?,?,?,?,? WHERE (SELECT count(*) FROM feedback WHERE actor=? AND created>?)<5")
    .bind(id, category, message, email, lat ?? null, lon ?? null, sender, now(), sender, now() - ms(60)).run();
  if (!result.meta.changes) {
    const existing = await env.DB.prepare("SELECT id FROM feedback WHERE id=? AND actor=?").bind(id, sender).first();
    if (!existing) return json({ error: "rate_limit" }, 429);
  }
  ctx.waitUntil(emailFeedback(env, id));
  return json({ id }, 202);
}

async function road(request, env, url, user) {
  const path = url.pathname;
  const isModerator = user && (user.id === env.MODERATOR_UID || user.email && user.email === env.MODERATOR_EMAIL);
  if (request.method === "GET") {
    if (path === "/v1/incidents/moderation") {
      if (!isModerator) return json({ error: "forbidden" }, 403);
      const { results } = await env.DB.prepare("SELECT incidents.*, (SELECT COUNT(*) FROM abuse WHERE abuse.incident=incidents.id) AS flags FROM incidents WHERE removed IS NULL AND expires>? ORDER BY flags DESC,created DESC LIMIT 100").bind(now()).all();
      return json({ incidents: results.map(row => ({ ...publicIncident(row), flags: row.flags })) });
    }
    if (path !== "/v1/incidents") return json({ error: "not_found" }, 404);
    const lat = Number(url.searchParams.get("lat") ?? NaN);
    const lon = Number(url.searchParams.get("lon") ?? NaN);
    if (!validPoint(lat, lon)) return json({ error: "invalid_location" }, 400);
    const lonDelta = Math.min(5, .035 / Math.max(.008, Math.cos(lat * Math.PI / 180)));
    const { results } = await env.DB.prepare("SELECT * FROM incidents WHERE expires>? AND removed IS NULL AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND (confirmed IS NOT NULL OR ? IS NOT NULL AND author != ?) ORDER BY confirmed DESC,created DESC LIMIT 60")
      .bind(now(), lat-.035, lat+.035, lon-lonDelta, lon+lonDelta, user?.id ?? null, user?.id ?? null).all();
    return json({ incidents: results.map(publicIncident) });
  }
  if (!user?.email) return json({ error: "verified_account_required" }, 401);
  const input = await body(request);
  if (path === "/v1/incidents/abuse") {
    await env.DB.prepare("INSERT OR IGNORE INTO abuse SELECT id,?,? FROM incidents WHERE id=?")
      .bind(user.id, now(), input.id ?? "").run();
    return json({ ok: true });
  }
  if (path === "/v1/incidents/remove") {
    if (!isModerator) return json({ error: "forbidden" }, 403);
    await env.DB.prepare("UPDATE incidents SET removed=? WHERE id=?").bind(now(), input.id ?? "").run();
    return json({ ok: true });
  }
  const blocked = await env.DB.prepare("SELECT actor FROM blocked_reporters WHERE actor=?").bind(user.id).first();
  if (blocked) return json({ error: "reporting_blocked" }, 403);
  if (path === "/v1/incidents/vote") {
    const event = await env.DB.prepare("SELECT * FROM incidents WHERE id=? AND removed IS NULL AND expires>?")
      .bind(input.id ?? "", now()).first();
    if (!event || (input.vote === "gone" && event.confirmed == null) || !["present","gone"].includes(input.vote) || !validPoint(input.lat,input.lon) ||
        !Number.isFinite(input.heading) || input.heading < 0 || input.heading >= 360 ||
        input.road !== event.road || !near(event,input) || event.author === user.id)
      return json({ error: "not_eligible" }, 409);
    const time = now();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO incident_votes VALUES (?,?,?,?) ON CONFLICT(incident,actor) DO UPDATE SET vote=excluded.vote,created=excluded.created WHERE incident_votes.vote<>excluded.vote AND incident_votes.created<?")
        .bind(event.id,user.id,input.vote,time,time-ms(1)),
      input.vote === "present"
        ? env.DB.prepare("UPDATE incidents SET confirmed=coalesce(confirmed,?),expires=? WHERE id=? AND removed IS NULL AND expires>? AND EXISTS(SELECT 1 FROM incident_votes WHERE incident=? AND actor=? AND created=?)")
          .bind(time,time+ms(lifetimes[event.kind]),event.id,time,event.id,user.id,time)
        : env.DB.prepare("UPDATE incidents SET removed=? WHERE id=? AND removed IS NULL AND expires>? AND EXISTS(SELECT 1 FROM incident_votes WHERE incident=? AND actor=? AND created=?)")
          .bind(time,event.id,time,event.id,user.id,time),
    ]);
    return json({ ok: true });
  }
  if (path !== "/v1/incidents") return json({ error: "not_found" }, 404);
  const { id, kind, road: roadName, lat, lon, heading } = input;
  if (!/^[0-9a-f-]{36}$/.test(id ?? "") || !Object.hasOwn(lifetimes,kind) || !validPoint(lat,lon) ||
      !Number.isFinite(heading) || heading < 0 || heading >= 360 || typeof roadName !== "string" || !roadName.trim() || roadName.length > 100)
    return json({ error: "invalid_report" }, 400);
  const { results } = await env.DB.prepare("SELECT * FROM incidents WHERE road=? AND kind=? AND expires>? AND removed IS NULL AND lat BETWEEN ? AND ?")
    .bind(roadName,kind,now(),lat-.001,lat+.001).all();
  const duplicate = results.find(event => near(event,input));
  if (duplicate) return json({ id: duplicate.id, duplicate: true });
  const time = now();
  const result = await env.DB.prepare("INSERT OR IGNORE INTO incidents (id,kind,road,lat,lon,heading,author,created,expires) SELECT ?,?,?,?,?,?,?,?,? WHERE (SELECT count(*) FROM incidents WHERE author=? AND created>?)<5")
    .bind(id,kind,roadName,lat,lon,heading,user.id,time,time+ms(lifetimes[kind]),user.id,time-ms(10)).run();
  if (!result.meta.changes) return json({ error: "rate_limit" }, 429);
  return json({ id }, 201);
}

async function googleToken(env) {
  if (playAccess && playAccessUntil > now()) return playAccess;
  const service = JSON.parse(env.PLAY_SERVICE_ACCOUNT);
  const time = Math.floor(now() / 1000);
  const header = encode(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = encode(encoder.encode(JSON.stringify({
    iss: service.client_email, scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat: time, exp: time + 3600,
  })));
  const claims = header + "." + payload;
  const algorithm = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
  const key = await crypto.subtle.importKey("pkcs8", decode(service.private_key.replace(/-----[^-]+-----|\s/g,"")), algorithm, false, ["sign"]);
  const signed = encode(await crypto.subtle.sign(algorithm.name,key,encoder.encode(claims)));
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", body: new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer", assertion:claims+"."+signed}),
  });
  if (!response.ok) throw new Error("Google token unavailable");
  playAccess = (await response.json()).access_token;
  playAccessUntil = now() + ms(50);
  return playAccess;
}

async function play(env, path, input) {
  const base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + encodeURIComponent(env.PLAY_PACKAGE_ID);
  return fetch(base + path, {
    method: input ? "POST" : "GET", headers: { Authorization: "Bearer " + await googleToken(env), "Content-Type": "application/json" },
    body: input ? JSON.stringify(input) : undefined,
  });
}

async function verifyPurchase(env, token, user) {
  const uri = "/purchases/subscriptionsv2/tokens/" + encodeURIComponent(token);
  const response = await play(env,uri);
  if (response.status === 404 || response.status === 410) {
    await env.DB.prepare("UPDATE subscriptions SET status='EXPIRED',expires=0,checked=? WHERE token=? AND actor=?").bind(now(),token,user).run();
    return false;
  }
  if (!response.ok) throw new Error("Play verification unavailable");
  const value = await response.json();
  if (value.externalAccountIdentifiers?.obfuscatedExternalAccountId !== await hash(user)) return false;
  const status = value.subscriptionState?.replace("SUBSCRIPTION_STATE_", "");
  const items = (value.lineItems ?? []).filter(item => item.productId === "wayshift-plus" && ["weekly","monthly"].includes(item.offerDetails?.basePlanId));
  if (!items.length || !status) return false;
  const expiry = Math.max(0,...items.map(item => Date.parse(item.expiryTime) || 0));
  await env.DB.prepare("INSERT INTO subscriptions VALUES(?,?,?,?,?) ON CONFLICT(token) DO UPDATE SET status=excluded.status,expires=excluded.expires,checked=excluded.checked WHERE actor=excluded.actor")
    .bind(token,user,status,expiry,now()).run();
  if (value.linkedPurchaseToken) await env.DB.prepare("DELETE FROM subscriptions WHERE token=? AND actor=?").bind(value.linkedPurchaseToken,user).run();
  if (["ACTIVE","IN_GRACE_PERIOD","CANCELED"].includes(status) && value.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING") {
    const ack = await play(env,"/purchases/subscriptions/wayshift-plus/tokens/"+encodeURIComponent(token)+":acknowledge",{});
    if (!ack.ok) throw new Error("Purchase acknowledgment unavailable");
  }
  return true;
}

async function entitlement(env, user) {
  const paid = await env.DB.prepare("SELECT max(expires) AS expiry FROM subscriptions WHERE actor=? AND status IN ('ACTIVE','IN_GRACE_PERIOD','CANCELED')").bind(user).first();
  const credits = await env.DB.prepare("SELECT (SELECT count(*) FROM ad_events WHERE actor=?) - 5*(SELECT count(*) FROM reward_sessions WHERE actor=?) AS balance").bind(user,user).first();
  const active = await env.DB.prepare("SELECT id,device FROM reward_sessions WHERE actor=? AND finished IS NULL").bind(user).first();
  return { plusUntil: paid?.expiry ?? 0, credits: Math.max(0,credits?.balance ?? 0), active };
}

let adKeys;
let adKeysUntil = 0;
function derToRaw(der) {
  if (der[0] !== 48 || der[1] !== der.length-2) throw new Error("Invalid ECDSA signature");
  let offset = 2;
  const output = new Uint8Array(64);
  for (let i=0; i<2; i++) {
    if (der[offset++] !== 2) throw new Error("Invalid ECDSA integer");
    const length = der[offset++];
    let value = der.slice(offset,offset+length);
    offset += length;
    if (value.length === 33 && value[0] === 0) value = value.slice(1);
    if (!value.length || value.length > 32) throw new Error("Invalid ECDSA integer size");
    output.set(value,i*32+32-value.length);
  }
  if (offset !== der.length) throw new Error("Invalid ECDSA signature length");
  return output;
}

async function rewardCallback(env,url) {
  const raw = url.search.slice(1);
  const offset = raw.indexOf("&signature=");
  const params = url.searchParams;
  const keyId = Number(params.get("key_id"));
  const nonce = params.get("custom_data");
  const transaction = params.get("transaction_id");
  if (offset < 0 || !nonce || !transaction || !env.ADMOB_REWARDED_ID ||
      ![env.ADMOB_REWARDED_ID,env.ADMOB_REWARDED_ID.split("/").at(-1)].includes(params.get("ad_unit")) ||
      params.get("reward_amount") !== "1") return json({ error: "invalid_reward" },400);
  if (!adKeys || adKeysUntil < now() || !adKeys.some(k => k.keyId === keyId)) {
    const response = await fetch("https://www.gstatic.com/admob/reward/verifier-keys.json");
    if (!response.ok) return json({ error: "key_unavailable" },503);
    adKeys = (await response.json()).keys;
    adKeysUntil = now() + ms(60);
  }
  const record = adKeys.find(k => k.keyId === keyId);
  if (!record) return json({ error: "unknown_key" },403);
  const algorithm = { name: "ECDSA", namedCurve: "P-256", hash: "SHA-256" };
  const key = await crypto.subtle.importKey("spki",decode(record.base64),algorithm,false,["verify"]);
  if (!await crypto.subtle.verify(algorithm,key,derToRaw(decode(params.get("signature"))),encoder.encode(raw.slice(0,offset))))
    return json({ error: "invalid_signature" },403);
  const eventTime = Number(params.get("timestamp"));
  if (!Number.isFinite(eventTime) || eventTime > now()+ms(2)) return json({ error: "invalid_timestamp" },400);
  await env.DB.prepare("INSERT OR IGNORE INTO ad_events SELECT ?,nonce,actor,? FROM ad_challenges WHERE nonce=? AND expires>=? AND expires-900000<=?")
    .bind(transaction,now(),nonce,eventTime,eventTime).run();
  return json({ ok: true });
}

async function payments(request,env,url,user) {
  if (!user?.email) return json({ error: "sign_in_required" },401);
  const path = url.pathname;
  if (path === "/v1/plus" && request.method === "GET") return json(await entitlement(env,user.id));
  if (request.method !== "POST") return json({ error: "not_found" },404);
  const input = await body(request);
  if (path === "/v1/plus/purchase") {
    if (typeof input.token !== "string" || input.token.length < 10 || input.token.length > 4096) return json({ error: "invalid_purchase" },400);
    if (!await verifyPurchase(env,input.token,user.id)) return json({ error: "unverified_purchase" },403);
  } else if (path === "/v1/plus/video") {
    const nonce = crypto.randomUUID();
    const result = await env.DB.prepare("INSERT INTO ad_challenges SELECT ?,?,? WHERE (SELECT count(*) FROM ad_challenges WHERE actor=? AND expires>?)<12")
      .bind(nonce,user.id,now()+ms(15),user.id,now()).run();
    if (!result.meta.changes) return json({ error: "rate_limit" },429);
    return json({ nonce });
  } else if (path === "/v1/plus/session") {
    if (!/^[0-9a-f-]{36}$/.test(input.id ?? "") || !/^[0-9a-f-]{36}$/.test(input.device ?? "")) return json({ error: "invalid_session" },400);
    await env.DB.prepare("INSERT OR IGNORE INTO reward_sessions (id,actor,device,started) SELECT ?,?,?,? WHERE (SELECT count(*) FROM ad_events WHERE actor=?)-5*(SELECT count(*) FROM reward_sessions WHERE actor=?)>=5")
      .bind(input.id,user.id,input.device,now(),user.id,user.id).run();
    const state = await entitlement(env,user.id);
    if (state.active?.device !== input.device) return json({ error: "session_unavailable" },409);
    return json(state);
  } else if (path === "/v1/plus/session/end") {
    await env.DB.prepare("UPDATE reward_sessions SET finished=? WHERE actor=? AND id=? AND device=? AND finished IS NULL")
      .bind(now(),user.id,input.id ?? "",input.device ?? "").run();
  } else if (path === "/v1/plus/session/release") {
    await env.DB.prepare("DELETE FROM reward_sessions WHERE actor=? AND id=? AND device=? AND finished IS NULL AND started>?")
      .bind(user.id,input.id ?? "",input.device ?? "",now()-ms(2)).run();
  } else return json({ error: "not_found" },404);
  return json(await entitlement(env,user.id));
}

async function deleteAccount(request,env,user) {
  if (!user?.recent) return json({ error: "reauthenticate" },403);
  const { results } = await env.DB.prepare("SELECT token FROM subscriptions WHERE actor=? AND status IN ('ACTIVE','IN_GRACE_PERIOD','ON_HOLD','PAUSED')").bind(user.id).all();
  for (const item of results) {
    const result = await play(env,"/purchases/subscriptionsv2/tokens/"+encodeURIComponent(item.token)+":cancel",{
      cancellationContext:{cancellationType:"USER_REQUESTED_STOP_RENEWALS"},
    });
    if (!result.ok) {
      await verifyPurchase(env,item.token,user.id);
      const current = await env.DB.prepare("SELECT status FROM subscriptions WHERE token=?").bind(item.token).first();
      if (current && !["CANCELED","EXPIRED"].includes(current.status)) return json({ error:"cancel_subscription_in_play" },409);
    }
  }
  await env.DB.batch([
    ...["subscriptions","ad_challenges","ad_events","reward_sessions","incident_votes","abuse","feedback"].map(table =>
      env.DB.prepare("DELETE FROM "+table+" WHERE actor=?").bind(user.id)),
    env.DB.prepare("UPDATE incidents SET author=?,removed=? WHERE author=?").bind("deleted",now(),user.id),
  ]);
  return json({ ok:true });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === "OPTIONS") return new Response(null, { headers: {
        "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Authorization, Content-Type",
      } });
      if (request.method === "GET" && url.pathname === "/v1/admob/reward") return await rewardCallback(env,url);
      const user = await actor(request,env);
      if (url.pathname.startsWith("/v1/plus")) return await payments(request,env,url,user);
      if (request.method === "POST" && url.pathname === "/v1/account/delete") return await deleteAccount(request,env,user);
      if (request.method === "POST" && url.pathname === "/v1/feedback") return await saveFeedback(request,env,ctx,user);
      if (url.pathname.startsWith("/v1/incidents") && ["GET","POST"].includes(request.method)) return await road(request,env,url,user);
      return json({ error: "not_found" },404);
    } catch (error) {
      if (error instanceof SyntaxError || error instanceof RangeError) return json({ error: "invalid_body" },400);
      console.error("Request failed", url.pathname, error.name);
      return json({ error: "temporarily_unavailable" },503);
    }
  },
  async scheduled(_event, env) {
    const { results } = await env.DB.prepare("SELECT id FROM feedback WHERE mailed IS NULL AND next_attempt<=? ORDER BY created LIMIT 15").bind(now()).all();
    for (const ticket of results) await emailFeedback(env,ticket.id);
    await env.DB.prepare("DELETE FROM incidents WHERE expires<?").bind(now()-ms(7*24*60)).run();
    await env.DB.prepare("DELETE FROM ad_challenges WHERE expires<?").bind(now()-ms(60)).run();
    if (env.PLAY_SERVICE_ACCOUNT) {
      const { results: checks } = await env.DB.prepare("SELECT token,actor FROM subscriptions WHERE checked<? AND (expires>? OR status IN ('ACTIVE','IN_GRACE_PERIOD','ON_HOLD','PAUSED','PENDING')) ORDER BY checked LIMIT 20")
        .bind(now()-ms(15),now()-ms(24*60)).all();
      for (const item of checks) {
        try { await verifyPurchase(env,item.token,item.actor); }
        catch { console.error("Subscription verification failed"); }
      }
    }
  },
};

import { DurableObject } from "cloudflare:workers";
const ttl = 600_000;
const validRandom = value => typeof value === "string" && /^[A-Za-z0-9_-]{43}$/.test(value);
const encoder = new TextEncoder();
const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const unbase64 = text => Uint8Array.from(atob(text), c => c.charCodeAt(0));
const json = (status, value, extra = {}) => new Response(JSON.stringify(value), {status, headers: {
  "Content-Type": "application/json", "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'", ...extra,
}});
const failure = (status, code) => json(status, {error: code});
async function body(request) {
  if (request.headers.get("Content-Type") !== "application/json") throw Error();
  const reader = request.body?.getReader(); if (!reader) throw Error();
  let size = 0; const chunks = [];
  try {while (true) {const {done, value} = await reader.read(); if (done) break;
    size += value.length; if (size > 2048) throw Error(); chunks.push(value);
  }} finally {await reader.cancel();}
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) {bytes.set(chunk, offset); offset += chunk.length;}
  return JSON.parse(new TextDecoder("utf-8", {fatal: true}).decode(bytes));
}
async function publicKey(pem) {
  if (typeof pem !== "string" || pem.length > 1024 || !/^-----BEGIN PUBLIC KEY-----\n[A-Za-z0-9+/=\n]+-----END PUBLIC KEY-----\n?$/.test(pem)) throw Error();
  const key = await crypto.subtle.importKey("spki", unbase64(pem.replace(/-----[^-]+-----|\s/g, "")), {name: "RSA-OAEP", hash: "SHA-1"}, true, ["encrypt"]);
  if (key.algorithm.modulusLength < 2048 || key.algorithm.modulusLength > 4096) throw Error();
  return key;
}
async function proofHash(proof) {return b64(await crypto.subtle.digest("SHA-256", encoder.encode(proof)));}
function equal(a, b) {if (typeof a !== "string" || a.length !== b.length) return false; let diff = 0; for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i); return diff === 0;}
function callbackValues(url) {
  if (url.search.length > 8192) throw Error(); const values = {};
  for (const [key, value] of url.searchParams) {
    if (!['state','code','error','error_description','response_type','scope'].includes(key) || Object.hasOwn(values, key)) throw Error();
    values[key] = value;
  }
  if (!validRandom(values.state) || Object.hasOwn(values, 'code') === Object.hasOwn(values, 'error')) throw Error();
  if (Object.hasOwn(values, 'code') && !/^[\x21-\x7e]{2,2048}$/.test(values.code)) throw Error();
  return values;
}
export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url), origin = new URL(env.PUBLIC_ORIGIN);
      if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash || origin.port || origin.hostname === 'configure-your-account.workers.dev') return failure(503, 'CONFIGURATION_REQUIRED');
      if (url.protocol !== 'https:' || url.origin !== origin.origin) return failure(403, 'HTTPS_OR_HOST_REQUIRED');
      const match = /^\/(sandbox|production)\/(reader\/(?:sessions|poll|done)|square\/callback)$/.exec(url.pathname);
      if (!match) return failure(404, 'NOT_FOUND');
      const [, environment, route] = match;
      if (request.method === 'GET' && route === 'reader/done' && !url.search) {
        const response = json(200, {});
        response.headers.set('Content-Type', 'text/html; charset=utf-8');
        return new Response('<!doctype html><meta name="viewport" content="width=device-width"><title>Kollel authorization</title><h1>Return to Kollel Giving Kiosk</h1><p>The kiosk will check Square. No payment was started.</p><a href="intent://square/return#Intent;scheme=kollelgiving;package=org.portlandkollel.kiosk;end">Open Kollel Giving Kiosk</a>', {headers: response.headers});
      }
      if ((request.method !== 'POST' || !['reader/sessions','reader/poll'].includes(route)) && (request.method !== 'GET' || route !== 'square/callback')) return failure(405, 'METHOD_NOT_ALLOWED');
      // Two bounded objects total, one per environment; never an unbounded object per random state.
      const instance = env.FLOWS.get(env.FLOWS.idFromName(environment));
      return await instance.fetch(request);
    } catch {return failure(400, 'INVALID_REQUEST');} // Never log errors, URLs, query strings or bodies.
  },
};
export class AuthorizationSessions extends DurableObject {
  async fetch(request) {
    return this.ctx.blockConcurrencyWhile(async () => {
      try {
        const url = new URL(request.url), environment = url.pathname.split('/')[1], route = url.pathname.split('/').slice(2).join('/');
        const now = Date.now(); await this.expire(now);
        if (route === 'square/callback') {
          const values = callbackValues(url), key = `flow:${values.state}`, session = await this.ctx.storage.get(key);
          if (!session || session.consumed || session.sealed || session.environment !== environment) return failure(400, 'NEW_PKCE_REQUIRED');
          const clear = new URLSearchParams({state: values.state, ...(Object.hasOwn(values,'code') ? {code: values.code} : {error:'access_denied'})}).toString();
          const aes = await crypto.subtle.generateKey({name:'AES-GCM', length:256}, true, ['encrypt']);
          const iv = crypto.getRandomValues(new Uint8Array(12));
          const encrypted = new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM', iv, additionalData:encoder.encode(`kollel-reader-relay-v1:${values.state}`), tagLength:128}, aes, encoder.encode(clear)));
          const wrapped = await crypto.subtle.encrypt({name:'RSA-OAEP'}, await publicKey(session.public_key), await crypto.subtle.exportKey('raw', aes));
          session.sealed = {key:b64(wrapped), iv:b64(iv), ciphertext:b64(encrypted.slice(0,-16)), tag:b64(encrypted.slice(-16))};
          delete session.public_key; await this.ctx.storage.put(key, session);
          return json(303, {}, {Location:`/${environment}/reader/done`});
        }
        if (url.search) throw Error();
        const data = await body(request), names = Object.keys(data).sort().join(',');
        if (names !== (route === 'reader/sessions' ? 'public_key,state' : 'state') || !validRandom(data.state)) throw Error();
        const proof = request.headers.get('Authorization') || '';
        if (!/^Bearer [A-Za-z0-9_-]{43}$/.test(proof)) return failure(403, 'AUTHORIZATION_REQUIRED');
        const key = `flow:${data.state}`, session = await this.ctx.storage.get(key), hash = await proofHash(proof);
        if (route === 'reader/sessions') {
          if (session) return failure(400, 'NEW_PKCE_REQUIRED');
          if ((await this.ctx.storage.list({prefix:'flow:'})).size >= 32) return failure(429, 'TRY_LATER');
          await publicKey(data.public_key);
          await this.ctx.storage.put(key, {created:now, expires:now+ttl, environment, proof:hash, public_key:data.public_key, sealed:null});
          const alarm = await this.ctx.storage.getAlarm(); if (alarm === null || alarm > now+ttl) await this.ctx.storage.setAlarm(now+ttl);
          return json(200, {ready:true});
        }
        if (!session || session.consumed || session.environment !== environment || !equal(session.proof, hash)) return failure(404, 'SESSION_UNAVAILABLE');
        const sealed = session.sealed;
        if (sealed !== null) await this.ctx.storage.put(key, {created:session.created, expires:session.expires, environment, consumed:true});
        return json(200, {sealed}); // Durable tombstone before response: a lost response cannot be consumed twice.
      } catch {return failure(400, 'INVALID_REQUEST');}
    });
  }
  async expire(now) {
    const sessions = await this.ctx.storage.list({prefix:'flow:'});
    for (const [key, session] of sessions) if (session.expires <= now || session.created > now) await this.ctx.storage.delete(key);
  }
  async alarm() {
    await this.ctx.blockConcurrencyWhile(async () => {
      await this.expire(Date.now()); const remaining = await this.ctx.storage.list({prefix:'flow:'});
      if (remaining.size) await this.ctx.storage.setAlarm(Math.min(...[...remaining.values()].map(s => s.expires)));
    });
  }
}

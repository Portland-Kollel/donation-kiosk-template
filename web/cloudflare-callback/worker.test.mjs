import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto, randomBytes } from 'node:crypto';
import { Miniflare } from 'miniflare';
import { readFile } from 'node:fs/promises';
let mf, pem, keys;
const options={modules:true, scriptPath:new URL('./worker.mjs',import.meta.url).pathname, compatibilityDate:'2026-07-30', bindings:{PUBLIC_ORIGIN:'https://callback.example.invalid'}, durableObjects:{FLOWS:{className:'AuthorizationSessions',useSQLite:true}}};
const origin = 'https://callback.example.invalid';
const random = () => randomBytes(32).toString('base64url');
const invoke = (environment, path, data, proof, method='POST') => mf.dispatchFetch(`${origin}/${environment}/${path}`, {method, redirect:'manual', headers: {'Content-Type':'application/json', ...(proof ? {Authorization:`Bearer ${proof}`} : {})}, ...(method==='POST'?{body:JSON.stringify(data)}:{})});
before(async () => {
  mf = new Miniflare(options);
  keys = await webcrypto.subtle.generateKey({name:'RSA-OAEP',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-1'},true,['encrypt','decrypt']);
  pem = '-----BEGIN PUBLIC KEY-----\n'+Buffer.from(await webcrypto.subtle.exportKey('spki',keys.publicKey)).toString('base64').match(/.{1,64}/g).join('\n')+'\n-----END PUBLIC KEY-----\n';
});
after(async()=>{await mf?.dispose();});
async function flow(environment='sandbox') {const state=random(),proof=random();assert.equal((await invoke(environment,'reader/sessions',{state,public_key:pem},proof)).status,200);return {state,proof,environment};}
const callback = f => invoke(f.environment,`square/callback?state=${f.state}&code=synthetic-code`,null,null,'GET');
test('proof-bound envelope interoperates with tablet RSA-OAEP/AES-GCM and consumes once',async()=>{
  const f=await flow(); const response=await callback(f);assert.equal(response.status,303);assert.equal(response.headers.get('location'),'/sandbox/reader/done');assert.equal(response.headers.get('cache-control'),'no-store');
  assert.equal((await invoke('sandbox','reader/poll',{state:f.state},random())).status,404);
  const envelope=(await (await invoke('sandbox','reader/poll',{state:f.state},f.proof)).json()).sealed;
  assert.ok(!JSON.stringify(envelope).includes('synthetic-code'));assert.ok(!JSON.stringify(envelope).includes(f.proof));
  const raw=await webcrypto.subtle.decrypt({name:'RSA-OAEP'},keys.privateKey,Buffer.from(envelope.key,'base64'));
  const aes=await webcrypto.subtle.importKey('raw',raw,'AES-GCM',false,['decrypt']);
  const clear=await webcrypto.subtle.decrypt({name:'AES-GCM',iv:Buffer.from(envelope.iv,'base64'),additionalData:new TextEncoder().encode(`kollel-reader-relay-v1:${f.state}`)},aes,Buffer.concat([Buffer.from(envelope.ciphertext,'base64'),Buffer.from(envelope.tag,'base64')]));
  assert.equal(new URLSearchParams(new TextDecoder().decode(clear)).get('code'),'synthetic-code');
  assert.equal((await invoke('sandbox','reader/poll',{state:f.state},f.proof)).status,404);
  assert.equal((await callback(f)).status,400);
  assert.equal((await invoke('sandbox','reader/sessions',{state:f.state,public_key:pem},f.proof)).status,400);
});
test('concurrent retrieval has one durable winner',async()=>{
  const f=await flow();await callback(f);
  const replies=await Promise.all([invoke('sandbox','reader/poll',{state:f.state},f.proof),invoke('sandbox','reader/poll',{state:f.state},f.proof)]);
  assert.deepEqual(replies.map(r=>r.status).sort(),[200,404]);
});
test('environment, host, HTTPS, request fields and return destination are fixed',async()=>{
  const f=await flow();assert.equal((await invoke('production','reader/poll',{state:f.state},f.proof)).status,404);
  assert.equal((await invoke('production',`square/callback?state=${f.state}&code=synthetic-code`,null,null,'GET')).status,400);
  assert.equal((await mf.dispatchFetch('http://callback.example.invalid/sandbox/reader/done')).status,403);
  assert.equal((await mf.dispatchFetch('https://wrong.example.invalid/sandbox/reader/done')).status,403);
  assert.equal((await invoke('sandbox',`square/callback?state=${f.state}&code=a&code=b`,null,null,'GET')).status,400);
  assert.equal((await invoke('sandbox','reader/sessions',{state:random(),public_key:pem,return_url:'https://wrong.example.invalid'},random())).status,400);
  const done=await mf.dispatchFetch(`${origin}/sandbox/reader/done`);assert.match(await done.text(),/package=org.portlandkollel.kiosk/);
});
test('expired state cannot complete or poll after runtime restart',async()=>{
  const f=await flow(); await callback(f);
  // Restart the actual Worker runtime with its clock advanced, retaining durable storage.
  // Only the test source changes its clock; production exposes no time/state override.
  const source=await readFile(new URL('./worker.mjs',import.meta.url),'utf8');
  await mf.setOptions({...options,scriptPath:undefined,script:source.replaceAll('Date.now()', '(Date.now()+601000)')});
  assert.equal((await invoke('sandbox','reader/poll',{state:f.state},f.proof)).status,404);
  assert.equal((await callback(f)).status,400);
  await mf.setOptions(options);
});
test('only 32 transient records allowed per environment and missing bearer rejected',async()=>{
  assert.equal((await invoke('production','reader/poll',{state:random()})).status,403);
  for(let i=0;i<32;i++)await flow('production');
  assert.equal((await invoke('production','reader/sessions',{state:random(),public_key:pem},random())).status,429);
});

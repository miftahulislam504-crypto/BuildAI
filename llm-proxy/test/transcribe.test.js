'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createHandler, looksLikeWav } = require('../api/transcribe');

const ENV = { TRANSCRIBE_API_KEY: 'gk-test', APP_TOKEN: 'secret-token' };

function wav(samples = 4000) {
  const b = Buffer.alloc(44 + samples * 2);
  b.write('RIFF', 0, 'ascii'); b.writeUInt32LE(36 + samples * 2, 4); b.write('WAVE', 8, 'ascii');
  b.write('fmt ', 12, 'ascii'); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(16000, 24); b.writeUInt32LE(32000, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36, 'ascii'); b.writeUInt32LE(samples * 2, 40);
  return b;
}
function res() {
  const r = { code: null, body: null };
  r.status = (c) => { r.code = c; return r; };
  r.json = (j) => { r.body = j; return r; };
  return r;
}
const call = async (handler, { method = 'POST', token = 'secret-token', body = wav(), query = {}, headers = {} } = {}) => {
  const r = res();
  await handler({ method, query, body, headers: { ...(token === null ? {} : { authorization: `Bearer ${token}` }), ...headers } }, r);
  return r;
};
const whisper = (text, ok = true) => async () => ({ ok, status: ok ? 200 : 500, json: async () => ({ text }) });

test('audio becomes text', async () => {
  const r = await call(createHandler({ env: ENV, fetchImpl: whisper(' রহিমকে কল করো ') }));
  assert.equal(r.code, 200);
  assert.deepEqual(r.body, { text: 'রহিমকে কল করো' });
});

test('only POST', async () => {
  const r = await call(createHandler({ env: ENV, fetchImpl: whisper('x') }), { method: 'GET' });
  assert.equal(r.code, 405);
});

test('wrong or missing token is refused before anything is sent upstream', async () => {
  let called = false;
  const h = createHandler({ env: ENV, fetchImpl: async () => { called = true; return { ok: true, json: async () => ({ text: 'x' }) }; } });
  assert.equal((await call(h, { token: 'nope' })).code, 401);
  assert.equal((await call(h, { token: null })).code, 401);
  assert.equal(called, false);
});

test('not configured', async () => {
  const r = await call(createHandler({ env: { APP_TOKEN: 'secret-token' }, fetchImpl: whisper('x') }));
  assert.equal(r.code, 500);
});

test('not a WAV, or too short, is refused', async () => {
  const h = createHandler({ env: ENV, fetchImpl: whisper('x') });
  assert.equal((await call(h, { body: Buffer.from('hello') })).code, 400);
  assert.equal((await call(h, { body: wav(10) })).code, 400);
  assert.equal(looksLikeWav(wav()), true);
  assert.equal(looksLikeWav('text'), false);
});

test('too big is refused', async () => {
  const h = createHandler({ env: ENV, fetchImpl: whisper('x') });
  assert.equal((await call(h, { headers: { 'content-length': String(10 * 1024 * 1024) } })).code, 413);
});

test('language hint is two letters or falls back to bn, and the key goes only upstream', async () => {
  let seen;
  const h = createHandler({ env: ENV, fetchImpl: async (url, init) => { seen = { url, init }; return { ok: true, json: async () => ({ text: 'ok' }) }; } });
  await call(h, { query: { lang: 'en' } });
  assert.equal(seen.init.body.get('language'), 'en');
  assert.equal(seen.init.headers.Authorization, 'Bearer gk-test');
  assert.equal(seen.init.body.get('model'), 'whisper-large-v3-turbo');
  await call(h, { query: { lang: 'bn-BD; drop' } });
  assert.equal(seen.init.body.get('language'), 'bn');
});

test('upstream failure and timeout are reported, not leaked', async () => {
  assert.equal((await call(createHandler({ env: ENV, fetchImpl: whisper('x', false) }))).code, 502);
  const abort = async () => { const e = new Error('aborted'); e.name = 'AbortError'; throw e; };
  const r = await call(createHandler({ env: ENV, fetchImpl: abort }));
  assert.equal(r.code, 504);
  assert.deepEqual(r.body, { error: 'speech service unreachable' });
});

test('very long text is cut', async () => {
  const r = await call(createHandler({ env: ENV, fetchImpl: whisper('ক'.repeat(2000)) }));
  assert.equal(r.body.text.length, 500);
});

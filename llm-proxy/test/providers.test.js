'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createHandler, resolveChain, toOpenAI, fromOpenAI } = require('../api/assist');

const TOOLS = [
  { name: 'flashlight', description: 'Torch.', input_schema: { type: 'object', properties: { state: { type: 'string', enum: ['on', 'off'] } } } },
  { name: 'time', description: 'Say the time.', input_schema: { type: 'object', properties: {} } },
];
const BODY = { text: 'আলো জ্বালাও', lang: 'bn-BD', now: '2026-10-26T20:03+06:00', tools: TOOLS };
const AGENT = {
  mode: 'agent', goal: 'send hi', lang: 'bn-BD', now: '2026-10-26T20:03+06:00', package: 'com.whatsapp', screen: '[1] button "Send"', history: [],
  tools: [{ name: 'tap', description: 'Tap.', input_schema: { type: 'object', properties: { id: { type: 'integer' } } } }, { name: 'done', description: 'Done.', input_schema: { type: 'object', properties: {} } }],
};
const ENV = { APP_TOKEN: 't', LLM_PROVIDERS: 'gemini,groq,anthropic', GEMINI_API_KEY: 'g-key', GROQ_API_KEY: 'q-key', ANTHROPIC_API_KEY: 'a-key' };

const GEMINI = 'generativelanguage.googleapis.com';
const GROQ = 'api.groq.com';
const ANTHROPIC = 'api.anthropic.com';

const res = () => { const r = { headers: {} }; r.status = (c) => { r.code = c; return r; }; r.json = (j) => { r.body = j; return r; }; r.setHeader = (k, v) => { r.headers[k] = v; }; return r; };
const call = async (h, body = BODY) => { const r = res(); await h({ method: 'POST', headers: { authorization: 'Bearer t' }, body }, r); return r; };

const openai = (name, args) => ({ ok: true, status: 200, json: async () => ({ choices: [{ message: { tool_calls: [{ function: { name, arguments: typeof args === 'string' ? args : JSON.stringify(args) } }] } }] }) });
const claude = (name, input) => ({ ok: true, status: 200, json: async () => ({ content: [{ type: 'tool_use', name, input }] }) });
const fail = (status, headers) => ({ ok: false, status, headers: { get: (k) => (headers || {})[k] ?? null }, json: async () => ({}) });

/** routes by host; records every call */
function net(routes) {
  const seen = [];
  const f = async (url, init) => {
    const host = Object.keys(routes).find((h) => url.includes(h));
    seen.push({ host, url, init, body: JSON.parse(init.body) });
    const r = routes[host];
    if (typeof r === 'function') return r();
    return r;
  };
  f.seen = seen;
  f.hosts = () => seen.map((s) => s.host);
  return f;
}

test('only providers with a key (and a model, and https) are in the chain, in the order given', () => {
  assert.deepEqual(resolveChain(ENV, false).map((p) => p.name), ['gemini', 'groq', 'anthropic']);
  assert.deepEqual(resolveChain({ ...ENV, GEMINI_API_KEY: '' }, false).map((p) => p.name), ['groq', 'anthropic']);
  assert.deepEqual(resolveChain({ ...ENV, LLM_PROVIDERS: 'nonsense, groq ,GROQ' }, false).map((p) => p.name), ['groq']);
  assert.deepEqual(resolveChain({ APP_TOKEN: 't', ANTHROPIC_API_KEY: 'a' }, false).map((p) => p.name), ['anthropic'], 'default is Anthropic alone');
  // openrouter, cerebras and custom have no default model: without one they are skipped
  const e = { ...ENV, LLM_PROVIDERS: 'openrouter,cerebras,custom', OPENROUTER_API_KEY: 'o', CEREBRAS_API_KEY: 'c', CUSTOM_API_KEY: 'x', CUSTOM_URL: 'https://llm.example/v1/chat/completions' };
  assert.deepEqual(resolveChain(e, false), []);
  const withModels = resolveChain({ ...e, OPENROUTER_MODEL: 'm:free', CUSTOM_MODEL: 'cm' }, false);
  assert.deepEqual(withModels.map((p) => [p.name, p.model]), [['openrouter', 'm:free'], ['custom', 'cm']]);
  assert.deepEqual(resolveChain({ ...e, CUSTOM_MODEL: 'cm', CUSTOM_URL: 'http://plain.example/x' }, false), [], 'a custom address must be https');
});

test('the agent can use its own provider list and its own models', () => {
  const e = { ...ENV, AGENT_PROVIDERS: 'anthropic', GEMINI_AGENT_MODEL: 'g-agent' };
  assert.deepEqual(resolveChain(e, true).map((p) => p.name), ['anthropic']);
  assert.deepEqual(resolveChain(e, false).map((p) => p.name), ['gemini', 'groq', 'anthropic']);
  assert.equal(resolveChain({ ...ENV, GEMINI_AGENT_MODEL: 'g-agent' }, true)[0].model, 'g-agent');
  assert.equal(resolveChain({ ...ENV, OPENROUTER_API_KEY: 'o', OPENROUTER_MODEL: 'm', LLM_PROVIDERS: 'openrouter' }, true)[0].model, 'm', 'no agent model set: the normal one');
});

test('the first provider answers: nobody else is called, and the header says who', async () => {
  const f = net({ [GEMINI]: openai('flashlight', { state: 'on' }), [GROQ]: openai('time', {}), [ANTHROPIC]: claude('time', {}) });
  const r = await call(createHandler({ env: ENV, fetchImpl: f }));
  assert.equal(r.code, 200);
  assert.deepEqual(r.body, { steps: [{ intent: 'flashlight', slots: { state: 'on' } }] });
  assert.deepEqual(f.hosts(), [GEMINI]);
  assert.equal(r.headers['x-llm-provider'], 'gemini');
});

test('a rate limit moves on to the next provider', async () => {
  const f = net({ [GEMINI]: fail(429), [GROQ]: openai('flashlight', { state: 'on' }), [ANTHROPIC]: claude('time', {}) });
  const r = await call(createHandler({ env: ENV, fetchImpl: f }));
  assert.equal(r.code, 200);
  assert.deepEqual(f.hosts(), [GEMINI, GROQ]);
  assert.equal(r.headers['x-llm-provider'], 'groq');
});

test('server errors, network errors and timeouts also move on', async () => {
  const timeout = () => { const e = new Error('t'); e.name = 'TimeoutError'; throw e; };
  for (const first of [fail(503), () => { throw new Error('dns'); }, timeout]) {
    const f = net({ [GEMINI]: first, [GROQ]: openai('time', {}), [ANTHROPIC]: claude('time', {}) });
    const r = await call(createHandler({ env: ENV, fetchImpl: f }));
    assert.equal(r.code, 200);
    assert.equal(r.headers['x-llm-provider'], 'groq');
  }
});

test('a provider that is rate limited is skipped for a while, then tried again first', async () => {
  let t = 1_000_000;
  const f = net({ [GEMINI]: fail(429), [GROQ]: openai('time', {}), [ANTHROPIC]: claude('time', {}) });
  const h = createHandler({ env: ENV, fetchImpl: f, clock: () => t });
  await call(h);
  assert.deepEqual(f.hosts(), [GEMINI, GROQ]);
  f.seen.length = 0;
  t += 30_000;
  await call(h);
  assert.deepEqual(f.hosts(), [GROQ], 'still resting: no wasted call');
  f.seen.length = 0;
  t += 31_000; // 61 s after the limit
  await call(h);
  assert.deepEqual(f.hosts(), [GEMINI, GROQ], 'rested: tried first again');
});

test('Retry-After sets how long a provider rests', async () => {
  let t = 1_000_000;
  const f = net({ [GEMINI]: fail(429, { 'retry-after': '120' }), [GROQ]: openai('time', {}) });
  const h = createHandler({ env: { ...ENV, LLM_PROVIDERS: 'gemini,groq' }, fetchImpl: f, clock: () => t });
  await call(h);
  f.seen.length = 0;
  t += 90_000;
  await call(h);
  assert.deepEqual(f.hosts(), [GROQ]);
});

test('when every provider is resting they are all tried anyway', async () => {
  let t = 1_000_000;
  const f = net({ [GEMINI]: fail(429), [GROQ]: fail(429) });
  const h = createHandler({ env: { ...ENV, LLM_PROVIDERS: 'gemini,groq' }, fetchImpl: f, clock: () => t });
  assert.equal((await call(h)).code, 429);
  f.seen.length = 0;
  assert.equal((await call(h)).code, 429);
  assert.deepEqual(f.hosts(), [GEMINI, GROQ]);
});

test('an unusable answer moves on: broken arguments, a tool that was not offered, or no tool at all', async () => {
  const noTool = { ok: true, status: 200, json: async () => ({ choices: [{ message: { content: 'Done, the light is on!' } }] }) };
  for (const first of [openai('flashlight', '{not json'), openai('launch_rocket', {}), noTool, { ok: true, status: 200, json: async () => ({}) }]) {
    const f = net({ [GEMINI]: first, [GROQ]: openai('time', {}) });
    const r = await call(createHandler({ env: { ...ENV, LLM_PROVIDERS: 'gemini,groq' }, fetchImpl: f }));
    assert.equal(r.code, 200);
    assert.equal(r.headers['x-llm-provider'], 'groq');
  }
});

test('how it fails when nothing works: 429 if all were rate limited, 504 if it ended in a timeout, else 502', async () => {
  const env = { ...ENV, LLM_PROVIDERS: 'gemini,groq' };
  const timeout = () => { const e = new Error('t'); e.name = 'TimeoutError'; throw e; };
  assert.equal((await call(createHandler({ env, fetchImpl: net({ [GEMINI]: fail(429), [GROQ]: fail(429) }) }))).code, 429);
  assert.equal((await call(createHandler({ env, fetchImpl: net({ [GEMINI]: fail(500), [GROQ]: timeout }) }))).code, 504);
  assert.equal((await call(createHandler({ env, fetchImpl: net({ [GEMINI]: fail(429), [GROQ]: fail(500) }) }))).code, 502);
});

test('no token or no usable provider means the server is not configured', async () => {
  assert.equal((await call(createHandler({ env: { ...ENV, APP_TOKEN: '' }, fetchImpl: net({}) }))).code, 500);
  assert.equal((await call(createHandler({ env: { APP_TOKEN: 't', LLM_PROVIDERS: 'groq' }, fetchImpl: net({}) }))).code, 500);
});

test('OpenAI-style providers get tools as functions, the key as a Bearer token, and must answer with a tool', async () => {
  const f = net({ [GROQ]: openai('flashlight', { state: 'on' }) });
  await call(createHandler({ env: { ...ENV, LLM_PROVIDERS: 'groq', GROQ_MODEL: 'my-model' }, fetchImpl: f }));
  const sent = f.seen[0];
  assert.equal(sent.init.headers.authorization, 'Bearer q-key');
  assert.ok(!sent.init.body.includes('q-key'));
  assert.equal(sent.body.model, 'my-model');
  assert.equal(sent.body.tool_choice, 'required');
  assert.deepEqual(sent.body.tools.map((t) => [t.type, t.function.name]), [['function', 'flashlight'], ['function', 'time'], ['function', 'say'], ['function', 'ask']]);
  assert.deepEqual(sent.body.tools[0].function.parameters, TOOLS[0].input_schema);
  assert.equal(sent.body.messages[0].role, 'system');
  assert.ok(sent.body.messages[1].content.includes('<utterance>আলো জ্বালাও</utterance>'));
});

test('say and ask work through an OpenAI-style provider too', async () => {
  const say = await call(createHandler({ env: { ...ENV, LLM_PROVIDERS: 'groq' }, fetchImpl: net({ [GROQ]: openai('say', { text: 'প্যারিস' }) }) }));
  assert.deepEqual(say.body, { steps: [], say: 'প্যারিস' });
  const ask = await call(createHandler({ env: { ...ENV, LLM_PROVIDERS: 'groq' }, fetchImpl: net({ [GROQ]: openai('ask', { question: 'কোনটা?' }) }) }));
  assert.deepEqual(ask.body, { steps: [], ask: 'কোনটা?' });
});

test('agent mode: one step, from a fallback provider if the first is limited', async () => {
  const f = net({ [GEMINI]: fail(429), [GROQ]: openai('tap', { id: 3 }) });
  const r = await call(createHandler({ env: ENV, fetchImpl: f }), AGENT);
  assert.deepEqual(r.body, { steps: [{ intent: 'tap', slots: { id: 3 } }] });
  const bad = await call(createHandler({ env: { ...ENV, LLM_PROVIDERS: 'groq' }, fetchImpl: net({ [GROQ]: openai('say', { text: 'hi' }) }) }), AGENT);
  assert.equal(bad.code, 502, 'the agent needs a real step');
});

test('the OpenAI conversion keeps the prompt and never invents a plain-text answer', () => {
  const req = { max_tokens: 400, system: 'SYS', tools: [{ name: 'a', description: 'd', input_schema: { type: 'object' } }], messages: [{ role: 'user', content: 'u' }] };
  const o = toOpenAI(req, 'm');
  assert.equal(o.messages[0].content, 'SYS');
  assert.ok(o.max_tokens >= 1024);
  assert.deepEqual(fromOpenAI({ choices: [{ message: { content: 'hello' } }] }), { content: [] });
  assert.equal(fromOpenAI({ choices: [] }), null);
});

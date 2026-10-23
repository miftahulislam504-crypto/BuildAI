'use strict';
/**
 * POST /api/transcribe
 *
 * The BuildX AI app sends what the person just said as a 16 kHz mono WAV (Content-Type: audio/wav) and gets back
 *   { text: string }
 * This function forwards the audio to a Whisper-compatible speech-to-text API. It is the same kind of boundary as
 * /api/assist: your key and your bill stay here, the phone only holds APP_TOKEN.
 *
 * Query: ?lang=bn  (optional language hint, two letters)
 * Environment: TRANSCRIBE_API_KEY (required), APP_TOKEN (required, same one as /api/assist),
 *              TRANSCRIBE_URL (optional, default Groq's OpenAI-compatible endpoint; OpenAI's works too),
 *              TRANSCRIBE_MODEL (optional, default whisper-large-v3-turbo; use whisper-1 for OpenAI).
 * Nothing is logged: not the audio, not the text.
 */
const crypto = require('node:crypto');

const DEFAULT_URL = 'https://api.groq.com/openai/v1/audio/transcriptions';
const DEFAULT_MODEL = 'whisper-large-v3-turbo';
const MAX_BYTES = 3 * 1024 * 1024; // about 90 s of 16 kHz audio; a spoken command is a few seconds
const UPSTREAM_TIMEOUT_MS = 12000; // the phone gives up at 15 s
const LANG = /^[a-z]{2}$/;

// words the app understands: they steer Whisper towards the commands people actually give
const HINT = 'ফোন করো, মেসেজ পাঠাও, ওয়াইফাই, ব্লুটুথ, ভলিউম, অ্যালার্ম, টর্চ, রিমাইন্ডার, হটস্পট, এয়ারপ্লেন মোড, Hey Build';

function bad(res, code, msg) {
  res.status(code).json({ error: msg });
}

function tokenOk(header, expected) {
  const given = typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : '';
  const a = crypto.createHash('sha256').update(given).digest();
  const b = crypto.createHash('sha256').update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

/** a real WAV: RIFF....WAVE, 16-bit PCM, not absurdly short */
function looksLikeWav(buf) {
  return Buffer.isBuffer(buf) && buf.length > 44 + 3200 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WAVE';
}

async function readBody(req) {
  if (Buffer.isBuffer(req.body)) return req.body;
  if (typeof req.on !== 'function' && typeof req[Symbol.asyncIterator] !== 'function') return Buffer.alloc(0);
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > MAX_BYTES) return null; // too big
    chunks.push(c);
  }
  return Buffer.concat(chunks);
}

function langOf(req) {
  const q = (req.query && req.query.lang) || '';
  return LANG.test(q) ? q : 'bn';
}

function createHandler({ fetchImpl, env } = {}) {
  const doFetch = fetchImpl || globalThis.fetch;
  const getEnv = () => env || process.env;

  return async function handler(req, res) {
    if (req.method !== 'POST') return bad(res, 405, 'POST only');

    const e = getEnv();
    if (!e.TRANSCRIBE_API_KEY || !e.APP_TOKEN) return bad(res, 500, 'server is not configured');
    if (!tokenOk(req.headers && req.headers.authorization, e.APP_TOKEN)) return bad(res, 401, 'bad token');

    const len = Number((req.headers && req.headers['content-length']) || 0);
    if (len > MAX_BYTES) return bad(res, 413, 'audio too long');

    const audio = await readBody(req);
    if (audio === null) return bad(res, 413, 'audio too long');
    if (!looksLikeWav(audio)) return bad(res, 400, 'send a 16-bit WAV');

    const form = new FormData();
    form.append('file', new Blob([audio], { type: 'audio/wav' }), 'speech.wav');
    form.append('model', e.TRANSCRIBE_MODEL || DEFAULT_MODEL);
    form.append('language', langOf(req));
    form.append('prompt', HINT);
    form.append('temperature', '0');
    form.append('response_format', 'json');

    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), UPSTREAM_TIMEOUT_MS);
    try {
      const r = await doFetch(e.TRANSCRIBE_URL || DEFAULT_URL, {
        method: 'POST',
        headers: { Authorization: `Bearer ${e.TRANSCRIBE_API_KEY}` },
        body: form,
        signal: ctl.signal,
      });
      if (!r.ok) return bad(res, 502, 'speech service refused');
      const j = await r.json();
      const text = typeof j.text === 'string' ? j.text.trim().slice(0, 500) : '';
      return res.status(200).json({ text });
    } catch (err) {
      return bad(res, err && err.name === 'AbortError' ? 504 : 502, 'speech service unreachable');
    } finally {
      clearTimeout(timer);
    }
  };
}

module.exports = createHandler();
module.exports.createHandler = createHandler;
module.exports.looksLikeWav = looksLikeWav;
// Vercel: read the raw audio ourselves, do not let the platform guess a body type
module.exports.config = { api: { bodyParser: false } };

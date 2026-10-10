// Recap backend (Cloudflare Worker + Workers AI + Durable Object SQLite)
//
// Auth: every request carries "Authorization: Bearer <apiKey>". The app generates the
// key and hands the same key to the Recapper device during setup. The key is hashed
// to pick your private Durable Object, so there are no accounts or sign-ups.
//
// Routes
//   GET    /api/ping
//   GET    /api/config          -> { mode: "push" | "daily" }
//   PUT    /api/config          { mode }
//   POST   /api/transcribe      body = WAV, ?source=phone|device-ptt|device-daily, X-Recorded-At = epoch ms
//   GET    /api/notes?limit=&before=
//   DELETE /api/notes/:id
//   POST   /api/search          { q, ask, tzMin }

import { DurableObject } from "cloudflare:workers";

const STT = "@cf/openai/whisper-large-v3-turbo";
const EMBED = "@cf/baai/bge-base-en-v1.5";
const LLM = "@cf/meta/llama-3.1-8b-instruct";
const MAX_AUDIO_BYTES = 12 * 1024 * 1024;
const MERGE_GAP_MS = 3 * 60 * 1000; // daily-mode clips closer than this join one note
const JUNK = /^(thank you\.?|thanks for watching[.!]?|you|bye\.?|[.\s]*)$/i; // typical Whisper hallucinations on silence

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });

async function sha256Hex(s) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function toBase64(buf) {
  const bytes = new Uint8Array(buf);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

function splitText(text) {
  const sentences = text.match(/[^.!?]+[.!?]*\s*/g) || [text];
  const out = [];
  let cur = "";
  for (const s of sentences) {
    if (cur && (cur + s).length > 350) { out.push(cur.trim()); cur = ""; }
    cur += s;
  }
  if (cur.trim()) out.push(cur.trim());
  return out.slice(0, 60);
}

async function summarize(env, text) {
  try {
    const r = await env.AI.run(LLM, {
      messages: [
        { role: "system", content: 'You label voice notes. Reply with ONLY JSON like {"title":"3-6 word title","summary":"one short sentence"}.' },
        { role: "user", content: text.slice(0, 6000) },
      ],
      max_tokens: 120,
    });
    const j = JSON.parse(String(r.response).match(/\{[\s\S]*\}/)[0]);
    return { title: String(j.title).slice(0, 80), summary: String(j.summary).slice(0, 300) };
  } catch {
    return { title: text.split(/\s+/).slice(0, 6).join(" "), summary: text.slice(0, 140) };
  }
}

async function transcribe(req, env, store, url) {
  const audio = await req.arrayBuffer();
  console.log(`transcribe: ${audio.byteLength} bytes from ${url.searchParams.get("source")}`);
  if (audio.byteLength < 2000) return json({ error: "audio too short" }, 400);
  if (audio.byteLength > MAX_AUDIO_BYTES) return json({ error: "audio too large" }, 413);

  const source = url.searchParams.get("source") || "phone";
  const hdr = Number(req.headers.get("X-Recorded-At") || 0);
  const startedAt = hdr > 1.6e12 ? hdr : Date.now(); // devices without a synced clock send 0
  const endedAt = startedAt + Math.round(Math.max(1, (audio.byteLength - 44) / 32000) * 1000);

  const stt = await env.AI.run(STT, { audio: toBase64(audio) });
  const text = (stt.text || "").trim();
  console.log(`stt heard (${text.length} chars): "${text.slice(0, 120)}"`);
  if (text.length < 3 || JUNK.test(text)) {
    console.log("skipped: no usable speech");
    return json({ skipped: true, reason: "no speech", heard: text });
  }

  const chunks = splitText(text);
  const emb = await env.AI.run(EMBED, { text: chunks });
  const meta = source === "device-daily"
    ? { title: "Daily recording", summary: "" }
    : await summarize(env, text);

  const note = await store.addNote({
    source, startedAt, endedAt, text, title: meta.title, summary: meta.summary,
    chunks: chunks.map((t, i) => ({ text: t, vec: emb.data[i] })),
  });
  return json({ ok: true, note });
}

async function search(req, env, store) {
  const { q, ask = true, tzMin = 0 } = await req.json();
  if (!q || !q.trim()) return json({ error: "empty query" }, 400);
  const e = await env.AI.run(EMBED, { text: [q] });
  const hits = await store.searchChunks(e.data[0], 8);
  let answer = null;
  if (ask && hits.length) {
    const local = (ts) => new Date(ts + tzMin * 60000).toISOString().replace("T", " ").slice(0, 16);
    const excerpts = hits.map((h, i) => `[${i + 1}] (${local(h.ts)}) ${h.text}`).join("\n");
    const r = await env.AI.run(LLM, {
      messages: [
        { role: "system", content: `You answer questions about the user's own recorded voice notes. The current local time is ${local(Date.now())}. Use ONLY the excerpts. Cite excerpts like [1]. If they don't contain the answer, say you couldn't find it. Be brief.` },
        { role: "user", content: `Excerpts:\n${excerpts}\n\nQuestion: ${q}` },
      ],
      max_tokens: 300,
    });
    answer = String(r.response || "").trim();
  }
  return json({ answer, hits });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    if (p === "/") return new Response("Recap backend is running.", { headers: { "Content-Type": "text/plain" } });
    if (!p.startsWith("/api/")) return json({ error: "not found" }, 404);

    const key = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
    if (key.length < 32) return json({ error: "missing or too-short API key" }, 401);
    const store = env.USER_STORE.get(env.USER_STORE.idFromName(await sha256Hex(key)));

    try {
      if (p === "/api/ping") return json({ ok: true });
      if (p === "/api/config") {
        if (req.method === "PUT") return json(await store.setMode((await req.json()).mode));
        return json(await store.getConfig());
      }
      if (p === "/api/transcribe" && req.method === "POST") return await transcribe(req, env, store, url);
      if (p === "/api/search" && req.method === "POST") return await search(req, env, store);
      if (p === "/api/notes" && req.method === "GET") {
        const limit = Math.min(Number(url.searchParams.get("limit") || 100), 200);
        const before = Number(url.searchParams.get("before") || 0);
        return json({ notes: await store.listNotes(limit, before) });
      }
      const m = p.match(/^\/api\/notes\/([\w-]+)$/);
      if (m && req.method === "DELETE") { await store.deleteNote(m[1]); return json({ ok: true }); }
      return json({ error: "not found" }, 404);
    } catch (err) {
      console.error(err);
      return json({ error: String((err && err.message) || err) }, 500);
    }
  },
};

export class UserStore extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    for (const stmt of [
      "CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER, source TEXT, title TEXT, summary TEXT, text TEXT)",
      "CREATE TABLE IF NOT EXISTS chunks(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id TEXT, ts INTEGER, text TEXT, emb BLOB)",
      "CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)",
      "CREATE INDEX IF NOT EXISTS idx_notes_started ON notes(started_at)",
      "CREATE INDEX IF NOT EXISTS idx_chunks_note ON chunks(note_id)",
    ]) this.sql.exec(stmt);
  }

  getConfig() {
    const row = this.sql.exec("SELECT v FROM kv WHERE k = 'mode'").toArray()[0];
    return { mode: row ? row.v : "push" };
  }

  setMode(mode) {
    if (mode !== "push" && mode !== "daily") throw new Error("mode must be push or daily");
    this.sql.exec("INSERT OR REPLACE INTO kv(k, v) VALUES('mode', ?)", mode);
    return { mode };
  }

  addNote(n) {
    let id = null;
    let text = n.text;
    let startedAt = n.startedAt;
    let endedAt = n.endedAt;
    let summary = n.summary;

    if (n.source === "device-daily") {
      const prev = this.sql.exec("SELECT * FROM notes WHERE source = 'device-daily' ORDER BY ended_at DESC LIMIT 1").toArray()[0];
      if (prev && n.startedAt >= prev.started_at && n.startedAt - prev.ended_at < MERGE_GAP_MS) {
        id = prev.id;
        text = prev.text + " " + n.text;
        startedAt = prev.started_at;
        endedAt = Math.max(prev.ended_at, n.endedAt);
        summary = text.slice(0, 160);
        this.sql.exec("UPDATE notes SET ended_at = ?, text = ?, summary = ? WHERE id = ?", endedAt, text, summary, id);
      }
    }
    if (!id) {
      id = crypto.randomUUID();
      if (n.source === "device-daily") summary = text.slice(0, 160);
      this.sql.exec("INSERT INTO notes(id, started_at, ended_at, source, title, summary, text) VALUES(?,?,?,?,?,?,?)",
        id, startedAt, endedAt, n.source, n.title, summary, text);
    }
    for (const c of n.chunks) {
      this.sql.exec("INSERT INTO chunks(note_id, ts, text, emb) VALUES(?,?,?,?)",
        id, n.startedAt, c.text, new Float32Array(c.vec).buffer);
    }
    return { id, startedAt, endedAt, source: n.source, title: n.title, summary, text };
  }

  listNotes(limit, before) {
    return this.sql
      .exec("SELECT * FROM notes WHERE (? = 0 OR started_at < ?) ORDER BY started_at DESC LIMIT ?", before, before, limit)
      .toArray()
      .map((r) => ({ id: r.id, startedAt: r.started_at, endedAt: r.ended_at, source: r.source, title: r.title, summary: r.summary, text: r.text }));
  }

  deleteNote(id) {
    this.sql.exec("DELETE FROM chunks WHERE note_id = ?", id);
    this.sql.exec("DELETE FROM notes WHERE id = ?", id);
  }

  searchChunks(q, k) {
    let qn = 0;
    for (let i = 0; i < q.length; i++) qn += q[i] * q[i];
    qn = Math.sqrt(qn) || 1;
    const scored = [];
    for (const r of this.sql.exec("SELECT c.note_id, c.ts, c.text, c.emb, n.title FROM chunks c JOIN notes n ON n.id = c.note_id")) {
      const v = new Float32Array(r.emb);
      let dot = 0, vn = 0;
      for (let i = 0; i < v.length; i++) { dot += v[i] * q[i]; vn += v[i] * v[i]; }
      scored.push({ noteId: r.note_id, ts: r.ts, text: r.text, title: r.title, score: dot / ((Math.sqrt(vn) || 1) * qn) });
    }
    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, k);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    // -------------------------------------------------------------
    // POST /api/audio (Intake from Mobile App or Recapper ESP32)
    // -------------------------------------------------------------
    if (url.pathname === '/api/audio' && request.method === 'POST') {
      try {
        const mode = url.searchParams.get('mode') || 'phone_mic';
        const rawAudioBuffer = await request.arrayBuffer();

        if (!rawAudioBuffer || rawAudioBuffer.byteLength === 0) {
          return new Response(JSON.stringify({ status: 'error', message: 'Empty audio stream' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        // Determine if payload requires WAV formatting (Raw PCM from ESP32)
        let audioPayload = rawAudioBuffer;
        if (mode.includes('recapper') || mode === 'push_to_talk') {
          audioPayload = pcmToWav(rawAudioBuffer, 16000);
        }

        // 1. Workers AI Whisper Transcription
        const whisperRes = await env.AI.run('@cf/openai/whisper-large-v3-turbo', {
          audio: [...new Uint8Array(audioPayload)],
        });

        const text = whisperRes.text ? whisperRes.text.trim() : '';

        if (!text) {
          return new Response(JSON.stringify({ status: 'ignored', message: 'Silence detected' }), {
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const noteId = `recap_${Date.now()}`;
        const timestamp = Math.floor(Date.now() / 1000);

        // 2. Vectorize Text Embedding (BGE-Small)
        const embeddingRes = await env.AI.run('@cf/baai/bge-small-en-v1.5', { text: [text] });
        const vector = embeddingRes.data[0];

        // 3. Save to Vectorize Index
        await env.VECTOR_INDEX.insert([
          { id: noteId, values: vector, metadata: { mode, timestamp } },
        ]);

        // 4. Save Record to D1 Database
        await env.DB.prepare(
          'INSERT INTO notes (id, text, mode, timestamp) VALUES (?, ?, ?, ?)'
        ).bind(noteId, text, mode, timestamp).run();

        return new Response(
          JSON.stringify({ status: 'success', id: noteId, text, mode }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      } catch (err) {
        return new Response(JSON.stringify({ status: 'error', error: err.message }), {
          status: 500,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }
    }

    // -------------------------------------------------------------
    // GET /api/notes (Retrieve Feed)
    // -------------------------------------------------------------
    if (url.pathname === '/api/notes' && request.method === 'GET') {
      const { results } = await env.DB.prepare('SELECT * FROM notes ORDER BY timestamp DESC LIMIT 50').all();
      return new Response(JSON.stringify({ notes: results }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // -------------------------------------------------------------
    // GET /api/search?q=... (Vector Search + Llama 3.1 Synthesis)
    // -------------------------------------------------------------
    if (url.pathname === '/api/search' && request.method === 'GET') {
      const query = url.searchParams.get('q');
      if (!query) {
        return new Response(JSON.stringify({ status: 'error', message: 'Missing query' }), { status: 400 });
      }

      const queryEmbedding = await env.AI.run('@cf/baai/bge-small-en-v1.5', { text: [query] });
      const vectorMatches = await env.VECTOR_INDEX.query(queryEmbedding.data[0], { topK: 5 });
      const matchedIds = vectorMatches.matches.map((m) => m.id);

      if (matchedIds.length === 0) {
        return new Response(
          JSON.stringify({ query, ai_answer: "No relevant transcripts found.", matched_notes: [] }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      const placeholders = matchedIds.map(() => '?').join(',');
      const { results: matchedNotes } = await env.DB.prepare(
        `SELECT * FROM notes WHERE id IN (${placeholders})`
      ).bind(...matchedIds).all();

      const context = matchedNotes.map((n) => `- [${n.mode}] ${n.text}`).join('\n');
      const prompt = `User Query: "${query}"\n\nRecap Transcripts:\n${context}\n\nProvide a clear summary answering the user based on these notes.`;

      const aiResponse = await env.AI.run('@cf/meta/llama-3.1-8b-instruct', {
        messages: [{ role: 'user', content: prompt }],
      });

      return new Response(
        JSON.stringify({ query, ai_answer: aiResponse.response, matched_notes: matchedNotes }),
        { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      );
    }

    return new Response('Not Found', { status: 404 });
  },
};

function pcmToWav(pcmArrayBuffer, sampleRate = 16000) {
  const pcmBytes = new Uint8Array(pcmArrayBuffer);
  const dataSize = pcmBytes.byteLength;
  const wavBuffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(wavBuffer);

  const writeString = (v, offset, str) => {
    for (let i = 0; i < str.length; i++) v.setUint8(offset + i, str.charCodeAt(i));
  };

  writeString(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeString(view, 8, 'WAVE');
  writeString(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeString(view, 36, 'data');
  view.setUint32(40, dataSize, true);

  new Uint8Array(wavBuffer).set(pcmBytes, 44);
  return wavBuffer;
}

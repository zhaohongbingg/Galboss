import fs from 'node:fs';

const URL_ = process.env.BB_MCP_URL || 'http://localhost:3000/bb-mcp';
const SESSION_FILE = process.env.BB_MCP_SESSION || 'session.json';

let sessionId = null;
if (fs.existsSync(SESSION_FILE)) {
  try { sessionId = JSON.parse(fs.readFileSync(SESSION_FILE, 'utf8')).sessionId; } catch {}
}

function headers(extra = {}) {
  const h = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
    ...extra,
  };
  if (sessionId) h['mcp-session-id'] = sessionId;
  return h;
}

function parseBody(text) {
  const trimmed = text.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try { return JSON.parse(trimmed); } catch { return null; }
  }
  // SSE framing: collect all `data:` payloads
  const out = [];
  for (const line of trimmed.split(/\r?\n/)) {
    if (!line.startsWith('data:')) continue;
    const payload = line.slice(5).trim();
    if (!payload) continue;
    try { out.push(JSON.parse(payload)); } catch {}
  }
  return out.length ? out : null;
}

async function post(body) {
  const res = await fetch(URL_, { method: 'POST', headers: headers(), body: JSON.stringify(body) });
  const sid = res.headers.get('mcp-session-id');
  if (sid) {
    sessionId = sid;
    fs.writeFileSync(SESSION_FILE, JSON.stringify({ sessionId }));
  }
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 500)}`);
  return parseBody(text);
}

async function notify(method, params) {
  await post({ jsonrpc: '2.0', method, params });
}

const INIT_BODY = {
  jsonrpc: '2.0', id: 1, method: 'initialize',
  params: {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'codebuddy-bb-client', version: '1.0.0' },
  },
};

export async function initialize() {
  let r = null;
  try {
    r = await post(INIT_BODY);
  } catch (err) {
    const msg = String(err.message);
    if (/already initialized/i.test(msg)) {
      // 复用服务端已有会话，正常。
    } else if (/session not found|reinitialize|invalid session/i.test(msg)) {
      // 缓存的 session.json 已过期 —— Blockbench 每次重启都会这样（同一端口，会话换了）。
      // 以前这里直接抛错、整个流水线跑不起来，必须手动删 session.json。
      // 现在自动丢掉旧 id、重新握手一次。
      resetSession();
      r = await post(INIT_BODY);
    } else {
      throw err;
    }
  }
  try { await notify('notifications/initialized', {}); } catch {}
  return r;
}

let nextId = 100;

export async function callTool(name, args = {}) {
  const r = await post({
    jsonrpc: '2.0', id: nextId++, method: 'tools/call',
    params: { name, arguments: args },
  });
  return r;
}

export async function listTools() {
  return post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
}

export async function rpc(method, params) {
  return post({ jsonrpc: '2.0', id: nextId++, method, params });
}

export function resetSession() {
  sessionId = null;
  if (fs.existsSync(SESSION_FILE)) fs.unlinkSync(SESSION_FILE);
}

export { post, sessionId };

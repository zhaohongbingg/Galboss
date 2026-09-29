import { initialize, listTools, callTool, rpc, resetSession } from './_mcp_client.mjs';

const args = process.argv.slice(2);
const mode = args[0];

function out(v) {
  console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));
}

if (mode === '--reset') {
  resetSession();
  out('session reset');
  process.exit(0);
}

await initialize();

if (mode === '--list') {
  const r = await listTools();
  const tools = r?.result?.tools || [];
  for (const t of tools) {
    out(`### ${t.name}\n${t.description || ''}\nINPUT: ${JSON.stringify(t.inputSchema)}\n`);
  }
  out(`TOTAL: ${tools.length}`);
} else if (mode === '--rpc') {
  out(await rpc(args[1], JSON.parse(args[2] || '{}')));
} else if (mode === '--eval-file') {
  const fs = await import('node:fs');
  const code = fs.readFileSync(args[1], 'utf8');
  const r = await callTool('risky_eval', { code });
  const content = r?.result?.content;
  out(`--- risky_eval(${args[1]}) ---`);
  out(Array.isArray(content) ? content.map((c) => c.text ?? JSON.stringify(c)).join('\n') : r);
} else if (mode === '--file') {
  // args[1] = json file containing [{name, arguments}, ...]
  const fs = await import('node:fs');
  const plan = JSON.parse(fs.readFileSync(args[1], 'utf8'));
  for (const step of plan) {
    const r = await callTool(step.name, step.arguments || {});
    const content = r?.result?.content;
    const text = Array.isArray(content)
      ? content.map((c) => c.text ?? JSON.stringify(c)).join('\n')
      : JSON.stringify(r);
    out(`--- ${step.name} ---\n${step.label ? step.label + '\n' : ''}${text}`);
  }
} else {
  const name = args[0];
  const payload = args[1] ? JSON.parse(args[1]) : {};
  const r = await callTool(name, payload);
  const content = r?.result?.content;
  out(Array.isArray(content) ? content.map((c) => c.text ?? JSON.stringify(c)).join('\n') : r);
}

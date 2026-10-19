// 本地 MCP 测试服务：实现真实 MCP JSON-RPC 2.0 over stdio 协议。
// 仅用于协议验收（tools/list + tools/call），不连接外部服务。
// 用法：node mcp-echo-server.mjs

import readline from 'node:readline';

const rl = readline.createInterface({ input: process.stdin, terminal: false });

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

const TOOLS = [
  {
    name: 'echo',
    description: '回显输入文本（只读，用于协议验收）。',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string', description: '要回显的文本' } },
      required: ['text'],
    },
  },
  {
    name: 'uppercase',
    description: '把输入文本转为大写（只读，用于协议验收）。',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string', description: '要转换的文本' } },
      required: ['text'],
    },
  },
];

rl.on('line', (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  if (msg.method === 'initialize') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'mcp-echo-server', version: '1.0.0' },
      },
    });
    return;
  }
  if (msg.method === 'notifications/initialized') {
    return; // 通知无需响应
  }
  if (msg.method === 'tools/list') {
    send({ jsonrpc: '2.0', id: msg.id, result: { tools: TOOLS } });
    return;
  }
  if (msg.method === 'tools/call') {
    const name = msg.params?.name;
    const args = msg.params?.arguments || {};
    if (name === 'echo') {
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: { content: [{ type: 'text', text: String(args.text ?? '') }] },
      });
      return;
    }
    if (name === 'uppercase') {
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: { content: [{ type: 'text', text: String(args.text ?? '').toUpperCase() }] },
      });
      return;
    }
    send({ jsonrpc: '2.0', id: msg.id, error: { code: -32602, message: 'Unknown tool: ' + name } });
    return;
  }
  send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'Method not found' } });
});

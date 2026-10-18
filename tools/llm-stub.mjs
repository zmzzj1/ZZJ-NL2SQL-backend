/**
 * 联调用的 OpenAI 兼容测试桩（不是真实模型）。
 * 目的：在没有 LLM 凭据的环境中，验证 Spring Boot 到上游的 HTTP/SSE 解析、
 * 后端事件改写、前端流式渲染的完整链路。
 *
 * 启动：node tools/llm-stub.mjs [port]
 * 默认端口 9099，接口 POST /v1/chat/completions。
 */
import http from 'node:http';

const port = Number(process.argv[2] ?? 9099);

/** 根据服务端注入的真实 Schema 返回不同 SQL，用于 A/B 双库隔离联调。 */
function buildSql(prompt) {
  if (prompt.includes('blog_posts')) {
    return "SELECT COUNT(*) AS `文章数`\nFROM blog_posts";
  }
  if (prompt.includes('shop_orders')) {
    if (prompt.includes('金额') || prompt.includes('每月')) {
      return "SELECT DATE_FORMAT(created_at, '%Y-%m') AS `月份`, SUM(amount) AS `订单金额`\nFROM shop_orders\nGROUP BY DATE_FORMAT(created_at, '%Y-%m')\nORDER BY `月份`";
    }
    return "SELECT COUNT(*) AS `订单数`\nFROM shop_orders";
  }
  return 'SELECT 1 AS `result`';
}

function sendEvent(res, payload) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

const server = http.createServer((req, res) => {
  console.log(`[llm-stub] ${req.method} ${req.url}`);
  req.on('error', (err) => console.error('[llm-stub] req error', err));

  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'not found' } }));
    return;
  }

  let body = '';
  req.on('data', (chunk) => {
    body += chunk;
  });
  req.on('end', () => {
    let prompt = '';
    try {
      const parsed = JSON.parse(body);
      prompt = parsed?.messages?.at(-1)?.content ?? '';
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'invalid json' } }));
      return;
    }

    if (req.headers.authorization !== 'Bearer stub-key') {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'invalid api key' } }));
      return;
    }

    if (prompt.includes('触发上游错误')) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'stub upstream failure' } }));
      return;
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=UTF-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });

    const sql = buildSql(prompt);
    const pieces = sql.match(/[\s\S]{1,16}/g) ?? [sql];
    let index = 0;
    // 输入包含「慢速」时放慢输出，便于验证前端取消生成。
    const intervalMs = prompt.includes('慢速') ? 900 : 120;
    const timer = setInterval(() => {
      if (index >= pieces.length) {
        clearInterval(timer);
        sendEvent(res, { choices: [{ delta: {}, finish_reason: 'stop' }] });
        res.write('data: [DONE]\n\n');
        res.end();
        return;
      }
      sendEvent(res, { choices: [{ delta: { content: pieces[index] }, index: 0 }] });
      index++;
    }, intervalMs);

    // Node 16+ 中 req 的 close 在请求体读完后立即触发；应监听响应端关闭。
    res.on('close', () => clearInterval(timer));
  });
});

server.on('clientError', (err, socket) => {
  console.error('[llm-stub] clientError', err.message);
  socket.destroy();
});

server.listen(port, '127.0.0.1', () => {
  console.log(`[llm-stub] listening on http://127.0.0.1:${port}/v1 (not a real model)`);
  console.log('[llm-stub] schema-aware SQL stub ready');
});

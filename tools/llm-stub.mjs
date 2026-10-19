/**
 * 联调用的 OpenAI 兼容测试桩（不是真实模型）。
 * 目的：在没有 LLM 凭据的环境中，验证 Spring Boot 到上游的 HTTP/SSE 解析、
 * 后端事件改写、前端流式渲染的完整链路。
 *
 * 通过 system prompt 的 [PLANNER]/[SQL]/[FIXER]/[SUMMARY] 标记模拟不同阶段，
 * 便于真实 HTTP + 真实数据库的确定性集成测试。
 *
 * 启动：node tools/llm-stub.mjs [port]
 * 默认端口 9099，接口 POST /v1/chat/completions。
 */
import http from 'node:http';

const port = Number(process.argv[2] ?? 9099);

function buildPlan(prompt) {
  if (prompt.includes('退款') || prompt.includes('不存在的表')) {
    return JSON.stringify({
      answerable: false,
      reason: '当前数据源不包含退款表或所需字段，无法回答该问题。',
      steps: [],
    });
  }
  if (prompt.includes('持续错误')) {
    return JSON.stringify({
      answerable: true,
      reason: '',
      steps: [{ objective: '持续错误统计订单数量' }],
    });
  }
  if (prompt.includes('错误列')) {
    return JSON.stringify({
      answerable: true,
      reason: '',
      steps: [{ objective: '用错误列统计订单数量' }],
    });
  }
  if (prompt.includes('再找出') || prompt.includes('两步')) {
    return JSON.stringify({
      answerable: true,
      reason: '',
      steps: [
        { objective: '按月统计订单数量' },
        { objective: '找出订单量最高的月份' },
      ],
    });
  }
  return JSON.stringify({
    answerable: true,
    reason: '',
    steps: [{ objective: '统计订单数量' }],
  });
}

function buildSql(prompt) {
  if (prompt.includes('有效订单')) {
    return "SELECT COUNT(*) AS `有效订单数`\nFROM shop_orders\nWHERE status = 'paid'";
  }
  if (prompt.includes('持续错误')) {
    return 'SELECT another_bad_column FROM shop_orders';
  }
  if (prompt.includes('错误列')) {
    return 'SELECT bad_column FROM shop_orders';
  }
  if (prompt.includes('blog_posts')) {
    return 'SELECT COUNT(*) AS `文章数`\nFROM blog_posts';
  }
  if (prompt.includes('shop_orders')) {
    if (prompt.includes('最高')) {
      return "SELECT DATE_FORMAT(created_at, '%Y-%m') AS `月份`, COUNT(*) AS `订单数`\n"
        + 'FROM shop_orders\n'
        + "GROUP BY DATE_FORMAT(created_at, '%Y-%m')\n"
        + 'ORDER BY `订单数` DESC\nLIMIT 1';
    }
    if (prompt.includes('按月') || prompt.includes('每月')) {
      return "SELECT DATE_FORMAT(created_at, '%Y-%m') AS `月份`, COUNT(*) AS `订单数`\n"
        + 'FROM shop_orders\n'
        + "GROUP BY DATE_FORMAT(created_at, '%Y-%m')\n"
        + 'ORDER BY `月份`';
    }
    return 'SELECT COUNT(*) AS `订单数`\nFROM shop_orders';
  }
  return 'SELECT 1 AS `result`';
}

function buildFix(prompt) {
  if (prompt.includes('持续错误')) {
    return 'SELECT still_bad_column FROM shop_orders';
  }
  return 'SELECT COUNT(*) AS `订单数`\nFROM shop_orders';
}

function buildSummary(prompt) {
  if (prompt.includes('最高')) {
    return '已完成两步查询：先按月统计订单数，再找出订单量最高的月份；结论基于真实执行结果。';
  }
  return '查询已完成，结论基于当前数据源的真实执行结果。';
}

function buildRoute(prompt) {
  const trimmed = prompt.trim();
  if (/用户当前问题：\s*(你好|hi|hello)/i.test(prompt)) {
    return JSON.stringify({ route: 'CHITCHAT', reasonCode: '' });
  }
  if (prompt.includes('口径') || prompt.includes('怎么定义')) {
    return JSON.stringify({ route: 'CLARIFY', reasonCode: '缺少业务口径，请先说明统计口径或维护对应业务术语。' });
  }
  if (prompt.includes('什么是') || prompt.includes('术语')) {
    return JSON.stringify({ route: 'KNOWLEDGE', reasonCode: '' });
  }
  if (prompt.includes('再找出') || prompt.includes('两步') || prompt.includes('退款') || prompt.includes('不存在的表')) {
    return JSON.stringify({ route: 'COMPLEX', reasonCode: '需要多步分析' });
  }
  return JSON.stringify({ route: 'SIMPLE', reasonCode: '单条 SQL 即可' });
}

function buildKnowledge() {
  return '有效订单指已支付且未取消的订单。该定义来自用户维护的业务术语，未维护的口径不应自行假设。';
}

function buildMemory() {
  return '会话摘要：用户关注订单统计与峰值月份；结论基于真实查询结果，不保存原始行数据。';
}

function buildContent(system, prompt) {
  if (system.includes('[PLANNER]')) return buildPlan(prompt);
  if (system.includes('[MANAGER]')) return buildRoute(prompt);
  if (system.includes('[KNOWLEDGE]')) return buildKnowledge();
  if (system.includes('[MEMORY]')) return buildMemory();
  if (system.includes('[FIXER]')) return buildFix(prompt);
  if (system.includes('[SUMMARY]')) return buildSummary(prompt);
  return buildSql(prompt);
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
    let system = '';
    let prompt = '';
    try {
      const parsed = JSON.parse(body);
      system = parsed?.messages?.find((message) => message.role === 'system')?.content ?? '';
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

    const content = buildContent(system, prompt);
    const pieces = content.match(/[\s\S]{1,16}/g) ?? [content];
    let index = 0;
    // 输入包含「慢速」时放慢输出，便于验证前端取消生成。
    const intervalMs = prompt.includes('慢速') ? 900 : 60;
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
  console.log('[llm-stub] planner/sql/fixer/summary stub ready');
});

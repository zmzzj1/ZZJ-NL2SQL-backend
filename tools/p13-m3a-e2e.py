#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P13-M3A 真实联调：Gateway(8080) -> zzj-admin(8083) --Dubbo--> zzj-service(8081) -> MySQL。

不依赖任何 mock：注册/登录走真实接口，管理员角色直接改 MySQL 持久化 role，
Provider 可用性通过真实停止 zzj-service 进程验证。

用法：
    python p13-m3a-e2e.py                 # 完整流程（含停服 503 场景）
    M3A_SKIP_OUTAGE=1 python p13-m3a-e2e.py
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("M3A_BASE", "http://127.0.0.1:8080")
MYSQL = os.environ.get("M3A_MYSQL_BIN", "mysql")
MYSQL_ARGS = [
    "-h", os.environ.get("M3A_MYSQL_HOST", "127.0.0.1"),
    "-P", os.environ.get("M3A_MYSQL_PORT", "3306"),
    "-u", os.environ.get("M3A_MYSQL_USER", "root"),
    os.environ.get("M3A_MYSQL_DATABASE", "sql_platform"),
]

RESULTS = []


def log(case, status, detail):
    RESULTS.append((case, status, detail))
    print(f"[{status:6}] {case}: {detail}", flush=True)


class Client:
    def __init__(self, email, password="Passw0rd!2026"):
        self.email = email
        self.password = password
        self.cookies = {}
        self.csrf = None

    def req(self, method, path, payload=None, timeout=30, extra_headers=None):
        data = json.dumps(payload).encode() if payload is not None else None
        headers = {"Content-Type": "application/json"}
        if self.csrf:
            headers["X-XSRF-TOKEN"] = self.csrf
        if extra_headers:
            headers.update(extra_headers)
        cookie = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if cookie:
            headers["Cookie"] = cookie
        req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                self._absorb(resp)
                return resp.status, (json.loads(raw) if raw.strip() else {})
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            self._absorb(e)
            try:
                return e.code, json.loads(raw) if raw.strip() else {}
            except ValueError:
                return e.code, {"raw": raw[:200]}

    def _absorb(self, resp):
        for key in ("Set-Cookie", "set-cookie"):
            for value in resp.headers.get_all(key) or []:
                pair = value.split(";")[0]
                if "=" in pair:
                    name, _, val = pair.partition("=")
                    if val:
                        self.cookies[name.strip()] = val.strip()
                    else:
                        self.cookies.pop(name.strip(), None)
        for name in list(self.cookies):
            if name.upper().startswith("XSRF"):
                self.csrf = self.cookies[name]

    def bootstrap(self):
        code, body = self.req("GET", "/api/v1/auth/csrf")
        self.csrf = body.get("token") or self.csrf
        return code

    def register_and_login(self):
        self.req("POST", "/api/v1/auth/register", {"email": self.email, "password": self.password})
        return self.req("POST", "/api/v1/auth/login", {"email": self.email, "password": self.password})[0]


def mysql(sql):
    mysql_env = os.environ.copy()
    password = os.environ.get("M3A_MYSQL_PASSWORD")
    if password:
        mysql_env["MYSQL_PWD"] = password
    proc = subprocess.run([MYSQL] + MYSQL_ARGS + ["-e", sql], capture_output=True, text=True, env=mysql_env)
    if proc.returncode != 0:
        raise RuntimeError(f"mysql failed: {proc.stderr.strip()[:200]}")
    return proc.stdout


def promote(email, role):
    mysql(f"UPDATE users SET role='{role}' WHERE email='{email}';")


def main():
    stamp = int(time.time() * 1000)
    admin = Client(f"m3a.admin.{stamp}@example.com")
    normal = Client(f"m3a.user.{stamp}@example.com")

    # ---- B01 匿名访问必须 401（网关入口认证先于管理服务）----
    anon = Client("anon@example.com")
    st, body = anon.req("GET", "/api/v1/admin/users")
    log("B01.ANON_401", "PASS" if st == 401 else "FAIL", f"匿名 /admin/users -> {st} code={body.get('code')}")

    # ---- B02 普通登录用户访问管理接口必须 403 ----
    admin.bootstrap(); admin.register_and_login()
    normal.bootstrap(); normal.register_and_login()
    st, body = normal.req("GET", "/api/v1/admin/users")
    log("B02.USER_403", "PASS" if st == 403 else "FAIL", f"普通用户 /admin/users -> {st} code={body.get('code')}")

    st, body = normal.req("GET", "/api/v1/admin/dashboard")
    log("B02b.USER_DASHBOARD_403", "PASS" if st == 403 else "FAIL",
        f"普通用户 /admin/dashboard -> {st} code={body.get('code')}")

    # ---- B03 提升为平台管理员后重新登录，可读取真实用户分页 ----
    promote(admin.email, "ADMIN")
    login_code = admin.register_and_login()
    st, page = admin.req("GET", "/api/v1/admin/users?page=1&size=20")
    ok = st == 200 and isinstance(page.get("items"), list) and page.get("total", 0) > 0
    log("B03.ADMIN_USERS_200", "PASS" if ok else "FAIL",
        f"管理员 /admin/users -> {st} total={page.get('total')} 本页={len(page.get('items') or [])} (relogin={login_code})")

    # ---- B04 DTO 不得携带任何凭据字段 ----
    blob = json.dumps(page, ensure_ascii=False).lower()
    leaked = [k for k in ("password", "passwordhash", "apikey", "api_key", "secret") if k in blob]
    log("B04.NO_CREDENTIALS", "PASS" if not leaked else "FAIL",
        "用户分页响应未出现凭据字段" if not leaked else f"出现敏感字段 {leaked}")

    # ---- B05 概览：真实统计值 ----
    st, dash = admin.req("GET", "/api/v1/admin/dashboard")
    numeric = {k: v for k, v in dash.items() if isinstance(v, (int, float))}
    ok = st == 200 and len(numeric) >= 4
    log("B05.ADMIN_DASHBOARD_200", "PASS" if ok else "FAIL", f"/admin/dashboard -> {st} {numeric}")

    # ---- B06 分页与关键词 ----
    st, paged = admin.req("GET", "/api/v1/admin/users?page=1&size=1")
    ok = st == 200 and len(paged.get("items") or []) <= 1 and paged.get("total", 0) >= 1
    log("B06.PAGE_SIZE_1", "PASS" if ok else "FAIL",
        f"size=1 -> {st} 本页={len(paged.get('items') or [])} total={paged.get('total')}")

    st, clamped = admin.req("GET", "/api/v1/admin/users?page=0&size=9999")
    size = clamped.get("size")
    ok = st == 200 and isinstance(size, int) and 1 <= size <= 100
    log("B06b.PAGE_CLAMP", "PASS" if ok else "FAIL", f"page=0&size=9999 -> {st} 收敛后 size={size}")

    st, kw = admin.req("GET", f"/api/v1/admin/users?page=1&size=20&keyword={admin.email}")
    items = kw.get("items") or []
    ok = st == 200 and len(items) == 1 and items[0].get("email") == admin.email
    log("B06c.KEYWORD", "PASS" if ok else "FAIL",
        f"keyword 精确命中 -> {st} 命中={[i.get('email') for i in items]}")

    # ---- B07 其余 admin 路由仍由核心业务服务提供（未被管理服务抢走）----
    st, body = admin.req("GET", "/api/v1/admin/overview")
    ok = st in (200, 403)
    log("B07.OVERVIEW_STILL_SERVICE", "PASS" if ok else "FAIL",
        f"/admin/overview -> {st} code={body.get('code')}（200/403 表示仍由 Service 处理）")

    st, body = admin.req("GET", "/api/v1/admin/model-usage")
    log("B07b.MODEL_USAGE_STILL_SERVICE", "PASS" if st in (200, 403) else "FAIL",
        f"/admin/model-usage -> {st} code={body.get('code')}")

    # ---- B08 Provider 不可用必须失败关闭（503），不得返回伪造数据 ----
    if os.environ.get("M3A_SKIP_OUTAGE"):
        log("B08.PROVIDER_OUTAGE_503", "SKIP", "按环境变量跳过停服场景")
    else:
        print("\n>>> 请现在停止 zzj-service 进程，然后回车继续（30s 内完成）...", flush=True)
        try:
            sys.stdin.readline()
        except Exception:
            time.sleep(20)
        time.sleep(3)
        st, body = admin.req("GET", "/api/v1/admin/users", timeout=40)
        log("B08.PROVIDER_OUTAGE_503", "PASS" if st == 503 else "FAIL",
            f"Provider 不可用时 /admin/users -> {st} code={body.get('code')}")

    passed = sum(1 for _, s, _ in RESULTS if s == "PASS")
    failed = sum(1 for _, s, _ in RESULTS if s == "FAIL")
    skipped = sum(1 for _, s, _ in RESULTS if s == "SKIP")
    print(f"\n合计 PASS={passed} FAIL={failed} SKIP={skipped}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

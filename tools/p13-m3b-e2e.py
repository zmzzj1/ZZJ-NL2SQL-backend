#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P13-M3B 真实联调：用户状态与 Token 配额管理闭环。

链路：Gateway(8080) -> zzj-admin(8083, Dubbo Consumer) -> zzj-service(8081, Provider) -> MySQL。
覆盖 T1 管理权限、T2 状态更新、T3 配额修改、T4 路由与隔离。
T5 前端交互无浏览器自动化，标记 NOT RUN。

不依赖 mock：注册/登录走真实接口；管理员角色直接改 MySQL 持久化 role；
状态与配额变更通过真实查询（GET /admin/users 与直查 MySQL）双向验证。

用法（在项目任意位置运行，mysql 客户端在 PATH 或设 M3B_MYSQL_BIN）：
    python p13-m3b-e2e.py
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("M3B_BASE", "http://127.0.0.1:8080")
MYSQL = os.environ.get("M3B_MYSQL_BIN", "D:/mysql/mysql-8.4.11-winx64/bin/mysql.exe")
MYSQL_ARGS = [
    "-h", os.environ.get("M3B_MYSQL_HOST", "127.0.0.1"),
    "-P", os.environ.get("M3B_MYSQL_PORT", "3306"),
    "-u", os.environ.get("M3B_MYSQL_USER", "platform_app"),
    os.environ.get("M3B_MYSQL_DATABASE", "sql_platform"),
]
MYSQL_PWD = os.environ.get("M3B_MYSQL_PASSWORD")

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
        r = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(r, timeout=timeout) as resp:
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
    env = os.environ.copy()
    if MYSQL_PWD:
        env["MYSQL_PWD"] = MYSQL_PWD
    proc = subprocess.run([MYSQL] + MYSQL_ARGS + ["-e", sql], capture_output=True, text=True, env=env)
    if proc.returncode != 0:
        raise RuntimeError(f"mysql failed: {proc.stderr.strip()[:200]}")
    return proc.stdout


def user_id(email):
    out = mysql(f"SELECT id FROM users WHERE email='{email}';")
    for line in out.splitlines()[1:]:  # skip header
        line = line.strip()
        if line:
            return int(line)
    return None


def user_status(uid):
    out = mysql(f"SELECT status FROM users WHERE id={uid};")
    for line in out.splitlines()[1:]:
        line = line.strip()
        if line:
            return line
    return None


def user_quota(uid):
    out = mysql(
        f"SELECT token_limit FROM platform_quotas WHERE scope_type='USER' AND scope_id={uid};")
    for line in out.splitlines()[1:]:
        line = line.strip()
        if line:
            return int(line)
    return None


def promote(email, role):
    mysql(f"UPDATE users SET role='{role}' WHERE email='{email}';")


def main():
    stamp = int(time.time() * 1000)
    admin = Client(f"m3b.admin.{stamp}@example.com")
    normal = Client(f"m3b.user.{stamp}@example.com")
    target = Client(f"m3b.target.{stamp}@example.com")
    normal2 = Client(f"m3b.user2.{stamp}@example.com")

    # 预注册所有账号并建立基础会话（target/normal/normal2 保持 ACTIVE 普通用户）
    admin.bootstrap(); admin.register_and_login()
    normal.bootstrap(); normal.register_and_login()
    target.bootstrap(); target.register_and_login()
    normal2.bootstrap(); normal2.register_and_login()

    admin_id = user_id(admin.email)
    normal_id = user_id(normal.email)
    target_id = user_id(target.email)
    normal2_id = user_id(normal2.email)

    # ---- T1 管理权限 ----
    anon = Client("anon@example.com")
    st, body = anon.req("GET", "/api/v1/admin/check")
    log("T1.ANON_CHECK_401", "PASS" if st == 401 else "FAIL", f"匿名 /admin/check -> {st}")
    st, body = anon.req("PUT", "/api/v1/admin/users/1/status", {"status": "DISABLED"})
    log("T1.ANON_STATUS_401", "PASS" if st == 401 else "FAIL", f"匿名 PUT status -> {st}")

    def tolerant(client, method, path, payload=None, retries=2):
        # 容忍网关会话校验的 Redis 瞬态失败关闭（503）；权限拒绝应以 401/403 体现。
        st, body = client.req(method, path, payload)
        attempt = 0
        while st == 503 and attempt < retries:
            time.sleep(2)
            st, body = client.req(method, path, payload)
            attempt += 1
        return st, body

    st, body = tolerant(normal, "GET", "/api/v1/admin/check")
    log("T1.USER_CHECK_403", "PASS" if st == 403 else "FAIL", f"普通用户 /admin/check -> {st}")
    st, body = tolerant(normal, "PUT", f"/api/v1/admin/users/{target_id}/status", {"status": "DISABLED"})
    log("T1.USER_STATUS_403", "PASS" if st == 403 else "FAIL", f"普通用户 PUT status -> {st}")

    # 伪造身份头不能越权：普通用户带 X-User-Id / X-Internal-* 头访问仍 403
    st, body = normal.req("GET", "/api/v1/admin/check",
                          extra_headers={"X-User-Id": str(admin_id), "X-Internal-Admin": "true"})
    log("T1.SPOOF_HEADER_403", "PASS" if st == 403 else "FAIL",
        f"伪造头普通用户 /admin/check -> {st}（期望 403，控制器从会话取身份）")

    # 提升管理员并重登录使会话携带 ADMIN 角色
    promote(admin.email, "ADMIN")
    admin.bootstrap(); admin.register_and_login()

    st, body = admin.req("GET", "/api/v1/admin/check")
    ok = st == 200 and body.get("isAdmin") is True
    log("T1.ADMIN_CHECK_200", "PASS" if ok else "FAIL",
        f"管理员 /admin/check -> {st} isAdmin={body.get('isAdmin')} role={body.get('role')}")

    # ---- T2 状态更新 ----
    st, body = admin.req("PUT", f"/api/v1/admin/users/{target_id}/status", {"status": "DISABLED"})
    ok = st == 200 and body.get("status") == "DISABLED"
    log("T2.STATUS_UPDATE_200", "PASS" if ok else "FAIL",
        f"PUT status DISABLED -> {st} resp.status={body.get('status')}")

    # 重新查询 + 直查库 双向验证持久化
    _, page = admin.req("GET", f"/api/v1/admin/users?page=1&size=20&keyword={target.email}")
    items = page.get("items") or []
    from_list = next((i for i in items if i.get("email") == target.email), None)
    list_status = from_list.get("status") if from_list else None
    db_status = user_status(target_id)
    ok = list_status == "DISABLED" and db_status == "DISABLED"
    log("T2.STATUS_PERSISTED", "PASS" if ok else "FAIL",
        f"列表 status={list_status} 库 status={db_status}")

    # 无效状态值 -> 400
    st, _ = admin.req("PUT", f"/api/v1/admin/users/{target_id}/status", {"status": "FOOBAR"})
    log("T2.INVALID_STATUS_400", "PASS" if st == 400 else "FAIL", f"PUT 非法状态 -> {st}")

    # 不存在用户 -> 404
    st, _ = admin.req("PUT", "/api/v1/admin/users/999999/status", {"status": "DISABLED"})
    log("T2.UNKNOWN_USER_404", "PASS" if st == 404 else "FAIL", f"PUT 不存在用户 -> {st}")

    # 被停用用户既有的登录态失效 + 重新登录被门禁拦截
    # target 旧 cookie 失效；新 client 重新登录应因 isEnabled=false 被拒
    retry = Client(target.email, target.password)
    retry.bootstrap()
    login_code = retry.register_and_login()
    # register_and_login 内部先 register(已存在忽略) 再 login
    # 单独用 login 确认被禁用拦截
    _, login_body = retry.req("POST", "/api/v1/auth/login", {"email": target.email, "password": target.password})
    ok = login_body.get("code") in ("ACCOUNT_DISABLED", "UNAUTHENTICATED") or login_code == 401
    log("T2.DISABLED_LOGIN_BLOCKED", "PASS" if ok else "FAIL",
        f"禁用用户重新登录 -> code={login_code} body.code={login_body.get('code')}")

    # 恢复 target 为 ACTIVE，避免影响后续/隔离用例
    admin.req("PUT", f"/api/v1/admin/users/{target_id}/status", {"status": "ACTIVE"})
    mysql(f"UPDATE users SET status='ACTIVE' WHERE id={target_id};")

    # ---- T3 Token 配额 ----
    NEW_QUOTA = 123456
    st, body = admin.req("PUT", f"/api/v1/admin/users/{target_id}/quota", {"tokenQuota": NEW_QUOTA})
    ok = st == 200 and body.get("tokenQuota") == NEW_QUOTA
    log("T3.QUOTA_UPDATE_200", "PASS" if ok else "FAIL",
        f"PUT quota {NEW_QUOTA} -> {st} resp.tokenQuota={body.get('tokenQuota')}")

    db_q = user_quota(target_id)
    # 列表中该用户 tokenQuota 字段
    _, page = admin.req("GET", f"/api/v1/admin/users?page=1&size=20&keyword={target.email}")
    items = page.get("items") or []
    from_list = next((i for i in items if i.get("email") == target.email), None)
    list_q = from_list.get("tokenQuota") if from_list else None
    ok = db_q == NEW_QUOTA and list_q == NEW_QUOTA
    log("T3.QUOTA_PERSISTED", "PASS" if ok else "FAIL",
        f"库 token_limit={db_q} 列表 tokenQuota={list_q}")

    # 负数 -> 400
    st, _ = admin.req("PUT", f"/api/v1/admin/users/{target_id}/quota", {"tokenQuota": -1})
    log("T3.QUOTA_NEG_400", "PASS" if st == 400 else "FAIL", f"PUT 负数配额 -> {st}")

    # 溢出 -> 400
    st, _ = admin.req("PUT", f"/api/v1/admin/users/{target_id}/quota", {"tokenQuota": 99999999999999})
    log("T3.QUOTA_OVERFLOW_400", "PASS" if st == 400 else "FAIL", f"PUT 溢出配额 -> {st}")

    # 不存在用户 -> 404
    st, _ = admin.req("PUT", "/api/v1/admin/users/999999/quota", {"tokenQuota": 10})
    log("T3.QUOTA_UNKNOWN_404", "PASS" if st == 404 else "FAIL", f"PUT 不存在用户配额 -> {st}")

    # 不影响其他用户：normal2 配额应不变（初始 0）
    other_q = user_quota(normal2_id)
    isolated = other_q != NEW_QUOTA  # 未被 target 的配额值污染即隔离成立（从未设配额时为 None）
    log("T3.QUOTA_ISOLATION", "PASS" if isolated else "FAIL",
        f"其他用户 normal2 token_limit={other_q}（未被污染为 {NEW_QUOTA}）")

    # ---- T4 路由与隔离 ----
    # 新管理路径必须转发到 Admin（非 404，且得到 Admin 的特定响应）
    st, _ = admin.req("GET", "/api/v1/admin/users")
    log("T4.USERS_ROUTED_TO_ADMIN", "PASS" if st == 200 else "FAIL", f"/admin/users -> {st}（200=Admin 处理）")
    st, body = admin.req("GET", "/api/v1/admin/check")
    log("T4.CHECK_ROUTED_TO_ADMIN", "PASS" if st == 200 and body.get("isAdmin") else "FAIL",
        f"/admin/check -> {st} isAdmin={body.get('isAdmin')}")
    st, _ = admin.req("PUT", f"/api/v1/admin/users/{target_id}/status", {"status": "ACTIVE"})
    log("T4.STATUS_ROUTED_TO_ADMIN", "PASS" if st == 200 else "FAIL", f"PUT status -> {st}（200=Admin 处理）")
    st, _ = admin.req("PUT", f"/api/v1/admin/users/{target_id}/quota", {"tokenQuota": 1})
    log("T4.QUOTA_ROUTED_TO_ADMIN", "PASS" if st == 200 else "FAIL", f"PUT quota -> {st}（200=Admin 处理）")

    # 原有管理路径仍由核心业务服务（Service）提供：不应被 Admin 接管而 404
    st, _ = admin.req("GET", "/api/v1/admin/overview")
    log("T4.OVERVIEW_STILL_SERVICE", "PASS" if st in (200, 403) else "FAIL",
        f"/admin/overview -> {st}（200/403=仍由 Service 处理，非 Admin 的 404）")
    st, _ = admin.req("GET", "/api/v1/admin/model-usage")
    log("T4.MODEL_USAGE_STILL_SERVICE", "PASS" if st in (200, 403) else "FAIL",
        f"/admin/model-usage -> {st}（200/403=仍由 Service 处理）")

    # ---- T5 前端交互：无浏览器自动化，NOT RUN ----
    log("T5.FRONTEND_INTERACTION", "NOT RUN",
        "前端 AdminPage 已新增状态/配额操作与确认弹窗（src/pages/AdminPage.tsx, src/api/admin.ts）；"
        "本环境无浏览器自动化，需人工在页面验证列表分页/搜索、状态切换、配额更新、失败反馈。")

    passed = sum(1 for _, s, _ in RESULTS if s == "PASS")
    failed = sum(1 for _, s, _ in RESULTS if s == "FAIL")
    skipped = sum(1 for _, s, _ in RESULTS if s == "NOT RUN")
    print(f"\n合计 PASS={passed} FAIL={failed} NOT_RUN={skipped}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

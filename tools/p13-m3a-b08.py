#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""M3A B08 聚焦验证：Dubbo Provider(zzj-service) 不可用时，管理服务必须失败关闭(503)。

依赖同目录 p13-m3a-e2e.py 的 Client/promote（已验证能正确携带 XSRF cookie+头）。

用法：
    python p13-m3a-b08.py login    # 注册/登录/提权为 ADMIN，把 satoken cookie 落盘到 /tmp/m3a_b08.json
    python p13-m3a-b08.py call     # 读取落盘 cookie，调用 /api/v1/admin/users，打印状态码（Provider 在/不在决定 200/503）
"""
import importlib.util
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("e2e", os.path.join(HERE, "p13-m3a-e2e.py"))
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)

BASE = os.environ.get("M3A_BASE", "http://127.0.0.1:8080")
COOKIE_FILE = "/tmp/m3a_b08.json"


def _cookie_header(client):
    return "; ".join(f"{k}={v}" for k, v in client.cookies.items())


def do_login():
    stamp = int(time.time() * 1000)
    client = e2e.Client(f"m3a.b08.{stamp}@example.com")
    client.bootstrap()
    client.register_and_login()
    e2e.promote(client.email, "ADMIN")
    client.register_and_login()  # 重新登录使会话携带 ADMIN 角色
    cookie = _cookie_header(client)
    with open(COOKIE_FILE, "w") as f:
        json.dump({"cookie": cookie, "email": client.email}, f)
    print(f"LOGIN_OK email={client.email} cookie_len={len(cookie)}")


def do_call():
    with open(COOKIE_FILE) as f:
        info = json.load(f)
    cookie = info["cookie"]
    req = urllib.request.Request(BASE + "/api/v1/admin/users?page=1&size=1",
                                 headers={"Cookie": cookie})
    try:
        with urllib.request.urlopen(req, timeout=40) as resp:
            raw = resp.read().decode("utf-8", "replace")
            print(f"CALL_STATUS={resp.status}")
            print(f"CALL_BODY={raw[:160]}")
    except urllib.error.HTTPError as e:
        print(f"CALL_STATUS={e.code}")
        print(f"CALL_BODY={e.read().decode('utf-8', 'replace')[:160]}")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "call"
    if mode == "login":
        do_login()
    else:
        do_call()

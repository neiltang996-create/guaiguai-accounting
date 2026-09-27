#!/usr/bin/env python3
"""
家庭账本 · 同步服务端（FamilyLedger Sync Server）
================================================

跑在你家 NAS 上，7x24 常驻。两台手机不管在家（局域网）还是在外（5G/公网），
都连它同步同一份数据。公开客户端默认离线；私人地址与 token
在打包时就编译进去了。

## 协议（与 App 端 `sync/HomeServerTransport.kt` 严格对应）

    GET  /health                 → 200 {"app":"familyledger","v":1,"time":<ms>}
    GET  /devices                → 200 {"devices":["dev-a","dev-b"]}
    GET  /ops/{deviceId}         → 200 text/plain; 该设备的 op 日志，每行一个 JSON
    GET  /ops/{deviceId}/count   → 200 {"count":N}       （客户端据此只上传增量）
    POST /ops                    → 请求体是 JSONL；按每行的 deviceId 分组追加
                                   200 {"applied":N,"files":{"dev-a":123}}

    必填请求头：X-Family-Id: <familyId>      —— 家庭隔离；不匹配返回 403
    可选请求头：Authorization: Bearer <token> —— 若启动时配了 TOKEN 则必填

## 存储

    <data-dir>/<familyId>/<deviceId>.jsonl     只追加，永不删除

**只追加**是刻意的设计：服务端不做任何合并、不需要理解业务语义，
合并全部由手机端用 last-writer-wins 完成。服务端必须使用原子写入与备份，
而且手机端离线期间的记录不会丢。

## 运行

    python3 familyledger_sync_server.py --port 47822 --data /volume1/familyledger
    # 或带 token：
    FAMILYLEDGER_TOKEN=xxxx python3 familyledger_sync_server.py --port 47822 --data /volume1/familyledger

只依赖 Python 3 标准库，不需要 pip 安装任何东西。
"""

import re
import argparse
import hmac
import hashlib
import tarfile
import tempfile
from pathlib import Path
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_TAG = "familyledger"
PROTOCOL_VERSION = 1
MAX_ATTACHMENT = 2 * 1024 * 1024
MAX_BODY = 64 * 1024 * 1024  # 64MB，足够几万条流水

CONFIG = {
    "data_dir": "/volume1/familyledger",
    "token": "",
    "verbose": True,
}

DATA_LOCK = threading.RLock()
_locks = {}
_locks_guard = threading.Lock()


def _lock_for(path):
    with _locks_guard:
        lk = _locks.get(path)
        if lk is None:
            lk = threading.Lock()
            _locks[path] = lk
        return lk


def attachment_file(family_id, digest):
    if not re.fullmatch(r"[a-f0-9]{64}", digest):
        return None
    directory = family_dir(family_id)
    return os.path.join(directory, "receipts", digest + ".jpg") if directory else None


def log(*a):
    if CONFIG["verbose"]:
        print(time.strftime("[%Y-%m-%d %H:%M:%S]"), *a, flush=True)


def family_dir(family_id):
    # 防目录穿越：只允许字母数字、下划线、短横线
    safe = "".join(c for c in family_id if c.isalnum() or c in "-_")
    if not safe or safe != family_id:
        return None
    return os.path.join(CONFIG["data_dir"], safe)


def device_file(family_id, device_id):
    if not device_id or len(device_id) > 128:
        return None
    safe = "".join(c for c in device_id if c.isalnum() or c in "-_")
    if not safe or safe != device_id:
        return None
    fd = family_dir(family_id)
    return os.path.join(fd, safe + ".jsonl") if fd else None


FAMILY_FILE = "family.json"


def family_file():
    return os.path.join(CONFIG["data_dir"], FAMILY_FILE)


def read_family_id():
    fp = family_file()
    if not os.path.exists(fp):
        return None
    try:
        with open(fp, "r", encoding="utf-8") as f:
            return (json.load(f) or {}).get("familyId") or None
    except Exception:
        return None


def write_family_id(fid):
    atomic_write(family_file(), json.dumps({"familyId": fid}).encode())


def new_family_id():
    import secrets
    alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"   # 去掉易混字符，人工念得出
    return "".join(secrets.choice(alphabet) for _ in range(8))


def count_lines(path):
    if not os.path.exists(path):
        return 0
    n = 0
    with open(path, "rb") as f:
        for _ in f:
            n += 1
    return n


def atomic_write(path, data):
    parent = os.path.dirname(path)
    os.makedirs(parent, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".pending-", dir=parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(tmp, path)
        directory = os.open(parent, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)


def valid_op(item, device):
    seq = item.get("seq")
    return (isinstance(seq, int) and not isinstance(seq, bool) and seq > 0
        and item.get("opId") == "%s:%d" % (device, seq)
        and isinstance(item.get("hlc"), int)
        and item.get("opType") in ("UPSERT", "DELETE")
        and isinstance(item.get("payloadJson"), str)
        and isinstance(item.get("entityTable"), str)
        and isinstance(item.get("entityId"), str))


def backup_snapshot(destination, keep=30):
    """同一服务进程的写锁确保快照一致；写完校验归档再执行保留策略。"""
    root = Path(CONFIG["data_dir"]).resolve()
    target = Path(destination).resolve()
    if root == target or root in target.parents: raise ValueError("备份目录必须在数据目录之外")
    target.mkdir(parents=True, exist_ok=True)
    with DATA_LOCK:
        stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + str(time.time_ns())
        archive = target / ("familyledger-" + stamp + ".tar.gz")
        temporary = str(archive) + ".tmp"
        with tarfile.open(temporary, "w:gz") as tar:
            tar.add(root, arcname="data")
        with tarfile.open(temporary, "r:gz") as tar:
            for member in tar.getmembers():
                if member.isfile():
                    data = tar.extractfile(member).read()
                    if member.name.endswith(".jsonl"):
                        for line in data.splitlines(): json.loads(line)
        with open(temporary, "rb") as source: os.fsync(source.fileno())
        os.replace(temporary, archive)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        atomic_write(str(archive) + ".sha256", (digest + "  " + archive.name + "\n").encode())
        for old in sorted(target.glob("familyledger-*.tar.gz"))[:-max(1, keep)]:
            old.unlink()
            old.with_name(old.name + ".sha256").unlink(missing_ok=True)
        return str(archive)


def backup_loop(destination, keep):
    while True:
        try: log("备份完成", backup_snapshot(destination, keep))
        except Exception as error: log("备份失败", type(error).__name__)
        time.sleep(86400)


class Handler(BaseHTTPRequestHandler):
    server_version = "FamilyLedgerSync/1.2"
    protocol_version = "HTTP/1.1"

    # ---------- 工具 ----------

    def _send(self, code, body=b"", ctype="application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False))

    def _token_ok(self):
        """只校验 token。用于 /health 与 /family（客户端此时还不知道家庭码）。

        支持两种携带方式：
          1. `X-Sync-Token: <token>`  ← **推荐**，App 用这个
          2. `Authorization: Bearer <token>`

        为什么要多一个自定义头：外网走群晖 QuickConnect 中继时，中继会返回一次
        跨域名跳转（xxx.cn.quickconnect.cn → xxx.cn4.quickconnect.cn）。
        而 HTTP 客户端（OkHttp / curl 等）在跨域跳转时会**按安全策略丢弃
        Authorization 头**，导致本来能通的请求变成 401。自定义头不会被丢弃，
        所以中继跳转对它是透明的。
        """
        if not CONFIG["token"]:
            self._json(503, {"error": "server token not configured"})
            return False
        got = (self.headers.get("X-Sync-Token") or "").strip()
        if not hmac.compare_digest(got, CONFIG["token"]):
            auth = self.headers.get("Authorization", "")
            if not hmac.compare_digest(auth, "Bearer " + CONFIG["token"]):
                self._json(401, {"error": "token 不正确"})
                return False
        return True

    def _auth_ok(self):
        """token + 家庭码都要有。家庭码是数据隔离的命名空间。"""
        if not self._token_ok():
            return None
        fam = self.headers.get("X-Family-Id", "").strip()
        if not fam:
            self._json(403, {"error": "缺少 X-Family-Id"})
            return None
        if not family_dir(fam):
            self._json(400, {"error": "familyId 不合法"})
            return None
        return fam

    def _read_body(self, limit=MAX_BODY):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if n <= 0:
            return b""
        if n > limit:
            return None
        buf = b""
        while len(buf) < n:
            chunk = self.rfile.read(min(65536, n - len(buf)))
            if not chunk:
                break
            buf += chunk
        return buf if len(buf) == n else None

    def log_message(self, fmt, *args):
        if CONFIG["verbose"]:
            log("%s - %s" % (self.address_string(), fmt % args))

    # ---------- 路由 ----------

    def do_GET(self):
        path = self.path.split("?", 1)[0].rstrip("/") or "/"

        # /health 与 /family 只校验 token：客户端此时还不知道家庭码
        if path in ("/health", "/family"):
            if not self._token_ok():
                return
            if path == "/health":
                return self._json(200, {"app": APP_TAG, "v": PROTOCOL_VERSION, "time": int(time.time() * 1000)})
            return self._json(200, {"familyId": read_family_id()})

        fam = self._auth_ok()
        if fam is None:
            return

        if path.startswith("/attachments/"):
            fp = attachment_file(fam, path[len("/attachments/"):])
            if not fp:
                return self._json(400, {"error": "invalid attachment id"})
            if not os.path.isfile(fp):
                return self._json(404, {"error": "attachment not found"})
            with open(fp, "rb") as f:
                return self._send(200, f.read(), "image/jpeg")

        if path == "/devices":
            fd = family_dir(fam)
            if not fd:
                return self._json(400, {"error": "familyId 不合法"})
            names = []
            if os.path.isdir(fd):
                names = sorted(
                    f[:-6] for f in os.listdir(fd)
                    if f.endswith(".jsonl") and os.path.isfile(os.path.join(fd, f))
                )
            return self._json(200, {"devices": names})

        if path.startswith("/ops/"):
            rest = path[len("/ops/"):]
            if rest.endswith("/count"):
                dev = rest[:-len("/count")]
                fp = device_file(fam, dev)
                if not fp:
                    return self._json(400, {"error": "deviceId 不合法"})
                return self._json(200, {"count": count_lines(fp)})

            dev = rest
            fp = device_file(fam, dev)
            if not fp:
                return self._json(400, {"error": "deviceId 不合法"})
            if not os.path.exists(fp):
                # 对方还没同步过 —— 返回空体而不是 404，客户端少写分支
                return self._send(200, b"", "text/plain; charset=utf-8")
            lk = _lock_for(fp)
            with lk:
                with open(fp, "rb") as f:
                    data = f.read()
            return self._send(200, data, "text/plain; charset=utf-8")

        return self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?", 1)[0].rstrip("/") or "/"

        if path == "/family":
            if not self._token_ok():
                return
            body = self._read_body()
            try:
                incoming = str((json.loads(body.decode("utf-8")) or {}).get("familyId") or "").strip()
            except Exception:
                return self._json(400, {"error": "请求体不是合法 JSON"})
            if not family_dir(incoming):
                return self._json(400, {"error": "familyId 不合法"})
            with DATA_LOCK:
                current = read_family_id()
                if current is None:
                    write_family_id(incoming)
                    os.makedirs(family_dir(incoming), exist_ok=True)
                    log("登记家庭码: %s" % incoming)
                    return self._json(200, {"familyId": incoming, "created": True})
            if current != incoming:
                # 已被第一台手机注册过。不覆盖 —— 让第二台手机去 adopt 现有的。
                return self._json(409, {"familyId": current, "error": "家庭码已存在，请改用该家庭码"})
            return self._json(200, {"familyId": current, "created": False})

        fam = self._auth_ok()
        if fam is None:
            return
        if path.startswith("/attachments/"):
            digest = path[len("/attachments/"):]
            fp = attachment_file(fam, digest)
            if not fp:
                return self._json(400, {"error": "invalid attachment id"})
            body = self._read_body(MAX_ATTACHMENT)
            if body is None:
                return self._json(413, {"error": "attachment too large"})
            if not body.startswith(b"\xff\xd8") or not body.endswith(b"\xff\xd9") or hashlib.sha256(body).hexdigest() != digest:
                return self._json(400, {"error": "invalid attachment content or hash"})
            with DATA_LOCK, _lock_for(fp):
                if not os.path.exists(fp):
                    os.makedirs(os.path.dirname(fp), exist_ok=True)
                    fd, temporary = tempfile.mkstemp(dir=os.path.dirname(fp), prefix="receipt-")
                    try:
                        with os.fdopen(fd, "wb") as f:
                            f.write(body); f.flush(); os.fsync(f.fileno())
                        os.replace(temporary, fp)
                    finally:
                        if os.path.exists(temporary): os.unlink(temporary)
            return self._json(200, {"stored": True})

        if path != "/ops":
            return self._json(404, {"error": "not found"})

        body = self._read_body()
        if body is None:
            return self._json(413, {"error": "请求体过大或长度不合法"})

        fd = family_dir(fam)
        if not fd:
            return self._json(400, {"error": "familyId 不合法"})
        os.makedirs(fd, exist_ok=True)

        # 按 deviceId 分组
        groups = {}
        bad = 0
        for raw in body.split(b"\n"):
            raw = raw.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw.decode("utf-8"))
            except Exception:
                bad += 1
                continue
            if not isinstance(obj, dict):
                bad += 1
                continue
            dev = str(obj.get("deviceId") or "").strip()
            if not device_file(fam, dev):
                bad += 1
                continue
            groups.setdefault(dev, []).append(raw)

        if bad:
            return self._json(400, {"error": "包含损坏的操作，整批未写入", "bad": bad})
        try:
            with DATA_LOCK:
                planned = []
                for dev, lines in groups.items():
                    fp = device_file(fam, dev)
                    existing = Path(fp).read_bytes() if os.path.exists(fp) else b""
                    known = {}
                    for old in existing.splitlines():
                        if not old.strip(): continue
                        item = json.loads(old)
                        known[item["opId"]] = item
                    additions = []
                    for raw in lines:
                        item = json.loads(raw)
                        if not valid_op(item, dev):
                            return self._json(400, {"error": "操作结构无效，整批未写入"})
                        previous = known.get(item["opId"])
                        if previous is not None:
                            if previous != item:
                                return self._json(409, {"error": "同一操作 ID 的内容冲突，整批未写入"})
                            continue
                        known[item["opId"]] = item
                        additions.append(raw)
                    planned.append((fp, dev, existing, additions))
                applied = 0
                files = {}
                for fp, dev, existing, additions in planned:
                    if additions:
                        payload = existing + (b"\n" if existing and not existing.endswith(b"\n") else b"") + b"\n".join(additions) + b"\n"
                        atomic_write(fp, payload)
                        applied += len(additions)
                    files[dev] = count_lines(fp)
        except (ValueError, KeyError, OSError):
            return self._json(503, {"error": "存储校验或写入失败，请保留原文件并检查备份"})
        return self._json(200, {"applied": applied, "files": files, "bad": bad})

    def do_PUT(self):
        return self._json(405, {"error": "method not allowed"})

    def do_DELETE(self):
        # 只追加，不提供删除：手机端离线数据永不丢
        return self._json(405, {"error": "服务端只追加，不支持删除"})


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def main():
    ap = argparse.ArgumentParser(description="FamilyLedger 同步服务端")
    ap.add_argument("--port", type=int, default=int(os.environ.get("FAMILYLEDGER_PORT", "47822")))
    ap.add_argument("--data", default=os.environ.get("FAMILYLEDGER_DATA", "/volume1/familyledger"))
    ap.add_argument("--token", default=os.environ.get("FAMILYLEDGER_TOKEN", ""))
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    CONFIG["data_dir"] = args.data
    CONFIG["token"] = args.token
    if not args.token or args.token.startswith("REPLACE_WITH_"):
        raise SystemExit("必须通过 FAMILYLEDGER_TOKEN 或 --token 配置同步令牌")
    os.makedirs(args.data, exist_ok=True)
    backup_dir = os.environ.get("FAMILYLEDGER_BACKUP_DIR")
    if backup_dir:
        threading.Thread(target=backup_loop, args=(backup_dir, int(os.environ.get("FAMILYLEDGER_BACKUP_KEEP", "30"))), daemon=True).start()
    CONFIG["verbose"] = not args.quiet

    os.makedirs(args.data, exist_ok=True)
    log("家庭账本同步服务启动")
    log("  数据目录 : %s" % args.data)
    log("  监听端口 : %d" % args.port)
    log("  Token    : %s" % ("已启用" if args.token else "未启用（建议启用）"))
    for ip in local_ips():
        log("  本机地址 : http://%s:%d" % (ip, args.port))
    known = read_family_id()
    log("  家庭码   : %s" % (known if known else "（未登记，第一台手机会自动登记）"))

    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到退出信号，关闭")
        srv.shutdown()


if __name__ == "__main__":
    main()

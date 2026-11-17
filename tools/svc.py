"""Start, kill and query the two Java services (used by chaos.py, loadtest.py and run_all.py)."""
import json
import os
import signal
import subprocess
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {**os.environ, "JAVA_TOOL_OPTIONS": ""}


class Service:
    def __init__(self, name, main, port, args, logdir):
        self.name, self.main, self.port, self.args, self.logdir = name, main, port, args, logdir
        self.proc = None
        self.starts = 0

    def start(self):
        os.makedirs(self.logdir, exist_ok=True)
        log = open(os.path.join(self.logdir, f"{self.name}.log"), "a")
        self.proc = subprocess.Popen(["java", "-cp", os.path.join(ROOT, "out"), self.main, "--port", str(self.port), *self.args],
                                     stdout=log, stderr=subprocess.STDOUT, env=ENV)
        self.starts += 1
        for _ in range(200):
            try:
                if get(self.port, "/health").get("ok"):
                    return time.time()
            except Exception:
                time.sleep(0.05)
        raise RuntimeError(f"{self.name} did not start")

    def kill9(self):
        self.proc.send_signal(signal.SIGKILL)
        self.proc.wait()

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait()


def get(port, path, timeout=2):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=timeout) as r:
        return json.loads(r.read())


def metrics(port):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=2) as r:
        out = {}
        for line in r.read().decode().splitlines():
            k, _, v = line.rpartition(" ")
            out[k] = float(v)
        return out


def post(port, path, body, timeout=2):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def pair(workdir, scale=0.002, wal="GROUP_COMMIT", planner="lp", window_s=5):
    orders = Service("order-service", "fc.OrderService", 8101,
                     ["--data", os.path.join(workdir, "orders"), "--wal", wal, "--allocator", "http://127.0.0.1:8102"],
                     workdir)
    alloc = Service("allocator", "fc.Allocator", 8102,
                    ["--data", os.path.join(workdir, "allocator"), "--wal", wal, "--orders", "http://127.0.0.1:8101",
                     "--scale", str(scale), "--planner", planner, "--window-s", str(window_s)], workdir)
    return orders, alloc

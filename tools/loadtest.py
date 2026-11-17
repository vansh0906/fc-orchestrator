#!/usr/bin/env python3
"""Load test the order service's durable accept path: fsync per write vs group commit.

Each mode: fresh services, C client threads posting unique orders as fast as they can for S seconds.
Reports accepted orders/s, client-side p50/p99 latency and fsyncs per order (from /metrics).
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import svc  # noqa: E402


def run_mode(mode, clients=32, seconds=4.0):
    work = tempfile.mkdtemp(prefix=f"fc-load-{mode}-")
    orders, alloc = svc.pair(work, wal=mode)
    orders.start()
    alloc.start()
    lat, lock, stop = [], threading.Lock(), time.time() + seconds

    def client(k):
        n = 0
        mine = []
        while time.time() < stop:
            t = time.perf_counter()
            code, _ = svc.post(8101, "/orders", {"id": f"{mode}-{k}-{n}", "skus": [n % 400]}, timeout=5)
            if code == 202:
                mine.append((time.perf_counter() - t) * 1000)
            n += 1
        with lock:
            lat.extend(mine)

    th = [threading.Thread(target=client, args=(k,)) for k in range(clients)]
    t0 = time.time()
    for t in th:
        t.start()
    for t in th:
        t.join()
    took = time.time() - t0
    m = svc.metrics(8101)
    orders.stop()
    alloc.stop()
    shutil.rmtree(work, ignore_errors=True)
    lat.sort()
    n = len(lat)
    return {"mode": mode, "clients": clients, "accepted": n, "orders_per_s": round(n / took),
            "p50_ms": round(lat[n // 2], 2), "p99_ms": round(lat[int(0.99 * n)], 2),
            "fsyncs_per_order": round(m.get("wal_fsyncs_total", 0) / max(n, 1), 3)}


if __name__ == "__main__":
    rows = [run_mode(m) for m in ("PER_WRITE", "GROUP_COMMIT")]
    print(json.dumps(rows, indent=2))

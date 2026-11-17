#!/usr/bin/env python3
"""Chaos test: send orders while killing each service with SIGKILL, then prove no order was lost or completed twice.

Timeline (seconds from start of load):  4: kill -9 allocator   6: restart it   9: kill -9 order-service   11: restart it
Clients retry every order until it is accepted (same id: the service must de-duplicate). 5% of orders are also
re-sent on purpose. Every 0.25 s the script records backlog and breaker state and evaluates two alarms.
"""
import json
import os
import random
import shutil
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analytics  # noqa: E402
import svc  # noqa: E402

N_ORDERS = 4000
RATE = 250.0            # orders per second offered
CLIENTS = 16
ALARM_BACKLOG = 400     # open orders
ALARM_FOR_S = 1.0


def run(workdir, n_orders=N_ORDERS, rate=RATE, kills=True):
    orders, alloc = svc.pair(workdir)
    orders.start()
    alloc.start()
    rng = random.Random(1)
    ids = [f"ord-{i:05d}" for i in range(n_orders)]
    skus = {i: [rng.randrange(400) for _ in range(rng.randint(1, 3))] for i in ids}
    accepted, attempts, lock = {}, {}, threading.Lock()
    t0 = time.time()
    events = []

    def client(k):
        r = random.Random(k)
        for n, oid in enumerate(ids[k::CLIENTS]):
            due = t0 + (k + n * CLIENTS) / rate
            time.sleep(max(0, due - time.time()))
            resend = r.random() < 0.05
            tries = 0
            while True:
                tries += 1
                try:
                    code, _ = svc.post(8101, "/orders", {"id": oid, "skus": skus[oid]}, timeout=2)
                except Exception:
                    code = 0
                if code in (200, 202):
                    with lock:
                        accepted[oid] = time.time() - t0
                        attempts[oid] = tries
                    break
                time.sleep(min(1.0, 0.02 * 2 ** min(tries, 6)) * r.random())
            if resend:
                try:
                    svc.post(8101, "/orders", {"id": oid, "skus": skus[oid]}, timeout=2)
                except Exception:
                    pass

    threads = [threading.Thread(target=client, args=(k,), daemon=True) for k in range(CLIENTS)]
    for t in threads:
        t.start()

    series, stop = [], threading.Event()

    def monitor():
        while not stop.is_set():
            row = {"t": round(time.time() - t0, 2)}
            try:
                s = svc.get(8101, "/stats", timeout=0.5)
                row.update(open=s["accepted"] + s["forwarded"], done=s["done"], breaker=s["breaker"])
            except Exception:
                row.update(open=None, done=None, breaker="DOWN")
            series.append(row)
            time.sleep(0.25)

    mon = threading.Thread(target=monitor, daemon=True)
    mon.start()
    plan = [(4, "kill", alloc), (6, "start", alloc), (9, "kill", orders), (11, "start", orders)] if kills else []
    for at, what, s in plan:
        time.sleep(max(0, t0 + at - time.time()))
        if what == "kill":
            s.kill9()
        else:
            s.start()
        events.append({"t": round(time.time() - t0, 2), "event": f"{what} {s.name}"})
    for t in threads:
        t.join()
    load_end = time.time() - t0
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            if svc.get(8101, "/stats")["done"] >= n_orders:
                break
        except Exception:
            pass
        time.sleep(0.2)
    drained = time.time() - t0
    stop.set()
    mon.join()
    ostats, astats = svc.get(8101, "/stats"), svc.get(8102, "/stats")
    statuses = {}
    for oid in ids:
        st = svc.get(8101, f"/orders/{oid}")["status"]
        statuses[st] = statuses.get(st, 0) + 1
    orders.stop()
    alloc.stop()

    db = analytics.load(workdir)
    a = analytics.report(db, events)
    return {"orders": n_orders, "offered_rate": rate, "load_s": round(load_end, 1), "drained_s": round(drained, 1),
            "events": events, "status_counts": statuses, "lost": n_orders - statuses.get("DONE", 0),
            "completed_twice": a["completed_twice"], "tasks_twice": a["tasks_twice"],
            "client_retries": sum(v - 1 for v in attempts.values()),
            "duplicate_requests_absorbed": ostats["duplicate_requests"], "duplicate_callbacks_absorbed": ostats["duplicate_done"],
            "allocator": astats, "alarms": alarms(series), "series": series, "analytics": a}


def alarms(series):
    """Backlog alarm: open orders > threshold for ALARM_FOR_S. Dependency alarm: breaker OPEN or service DOWN."""
    out = []
    for name, cond in (("OrderBacklogHigh", lambda r: r["open"] is not None and r["open"] > ALARM_BACKLOG),
                       ("DependencyDown", lambda r: r["breaker"] in ("OPEN", "DOWN"))):
        since, firing = None, None
        for r in series:
            if cond(r):
                since = r["t"] if since is None else since
                if firing is None and r["t"] - since >= (ALARM_FOR_S if name == "OrderBacklogHigh" else 0):
                    firing = r["t"]
            else:
                if firing is not None:
                    out.append({"alarm": name, "fired_s": firing, "cleared_s": r["t"]})
                since, firing = None, None
        if firing is not None:
            out.append({"alarm": name, "fired_s": firing, "cleared_s": None})
    return out


if __name__ == "__main__":
    work = tempfile.mkdtemp(prefix="fc-chaos-")
    res = run(work)
    shutil.rmtree(work, ignore_errors=True)
    show = {k: v for k, v in res.items() if k != "series"}
    print(json.dumps(show, indent=2))
    sys.exit(0 if res["lost"] == 0 and res["completed_twice"] == 0 else 1)

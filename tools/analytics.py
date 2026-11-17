"""SQL analytics over the services' write-ahead logs (SQLite, standard library only).

Tables
  orders(id PRIMARY KEY, accepted_ms, done_ms)        from the order service WAL
  order_events(id, kind, ms)                           every accept/done record (to catch double completions)
  tasks(id, kind, station, eta_ms)                     every allocator task/assign/done record
"""
import json
import os
import sqlite3

SCHEMA = """
CREATE TABLE orders(id TEXT PRIMARY KEY, accepted_ms INTEGER, done_ms INTEGER);
CREATE TABLE order_events(id TEXT, kind TEXT, ms INTEGER);
CREATE TABLE tasks(id TEXT, kind TEXT, station INTEGER, eta_ms INTEGER);
CREATE INDEX ix_events ON order_events(id, kind);
CREATE INDEX ix_tasks ON tasks(id, kind);
"""


def _records(path):
    if not os.path.exists(path):
        return
    with open(path, "rb") as f:
        for raw in f.read().split(b"\n"):
            if raw.strip():
                try:
                    yield json.loads(raw)
                except json.JSONDecodeError:
                    pass          # torn last line after a crash


def load(workdir, db_path=":memory:"):
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)
    for r in _records(os.path.join(workdir, "orders", "orders.wal")):
        if r["t"] == "accept":
            db.execute("INSERT OR IGNORE INTO orders(id, accepted_ms) VALUES (?, ?)", (r["id"], r["ms"]))
            db.execute("INSERT INTO order_events VALUES (?, 'accept', ?)", (r["id"], r["ms"]))
        elif r["t"] == "done":
            db.execute("UPDATE orders SET done_ms = ? WHERE id = ? AND done_ms IS NULL", (r["ms"], r["id"]))
            db.execute("INSERT INTO order_events VALUES (?, 'done', ?)", (r["id"], r["ms"]))
    for r in _records(os.path.join(workdir, "allocator", "allocator.wal")):
        db.execute("INSERT INTO tasks VALUES (?, ?, ?, ?)", (r["id"], r["t"], r.get("s"), r.get("eta")))
    db.commit()
    return db


def percentile(db, where_sql, params, p):
    n = db.execute(f"SELECT COUNT(*) FROM orders WHERE done_ms IS NOT NULL AND {where_sql}", params).fetchone()[0]
    if n == 0:
        return None
    off = max(0, int(round(p / 100 * n + 0.4999)) - 1)
    return db.execute(f"SELECT done_ms - accepted_ms FROM orders WHERE done_ms IS NOT NULL AND {where_sql} "
                      "ORDER BY done_ms - accepted_ms LIMIT 1 OFFSET ?", (*params, off)).fetchone()[0]


def report(db, events=None):
    t0 = db.execute("SELECT MIN(accepted_ms) FROM orders").fetchone()[0] or 0
    out = {
        "orders": db.execute("SELECT COUNT(*) FROM orders").fetchone()[0],
        "completed": db.execute("SELECT COUNT(*) FROM orders WHERE done_ms IS NOT NULL").fetchone()[0],
        "completed_twice": db.execute(
            "SELECT COUNT(*) FROM (SELECT id FROM order_events WHERE kind='done' GROUP BY id HAVING COUNT(*) > 1)").fetchone()[0],
        "tasks_twice": db.execute(
            "SELECT COUNT(*) FROM (SELECT id FROM tasks WHERE kind='task' GROUP BY id HAVING COUNT(*) > 1)").fetchone()[0],
        "latency_ms": {f"p{p}": percentile(db, "1=1", (), p) for p in (50, 95, 99)},
        "by_station": [dict(zip(("station", "orders", "mean_latency_ms"), row)) for row in db.execute(
            "SELECT t.station, COUNT(*), ROUND(AVG(o.done_ms - o.accepted_ms)) FROM tasks t JOIN orders o USING(id) "
            "WHERE t.kind='assign' GROUP BY t.station ORDER BY t.station")],
        "busiest_second": db.execute(
            "SELECT (done_ms - ?) / 1000 AS sec, COUNT(*) AS n FROM orders WHERE done_ms IS NOT NULL "
            "GROUP BY sec ORDER BY n DESC LIMIT 1", (t0,)).fetchone(),
    }
    if events:
        # latency of orders accepted in each phase of the chaos timeline
        cuts = [0] + [e["t"] for e in events] + [1e9]
        names = ["before"] + [e["event"] for e in events]
        phases = []
        for a, b, name in zip(cuts, cuts[1:], names):
            w = "accepted_ms >= ? AND accepted_ms < ?"
            prm = (t0 + a * 1000, t0 + b * 1000)
            n = db.execute(f"SELECT COUNT(*) FROM orders WHERE {w}", prm).fetchone()[0]
            phases.append({"phase": f"after {name}" if name != "before" else "before faults", "orders": n,
                           "p50_ms": percentile(db, w, prm, 50), "p99_ms": percentile(db, w, prm, 99)})
        out["phases"] = phases
    return out

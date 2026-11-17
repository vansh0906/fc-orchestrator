# On-call runbook

Both services expose `/health`, `/stats` and `/metrics` (Prometheus text). The chaos test evaluates these alarms
every 0.25 s; in production they would be CloudWatch or Prometheus alert rules.

## OrderBacklogHigh

**Fires when:** open orders (`orders_open` on the order service) > 400 for 1 s.

1. Check the allocator: `curl :8102/health` and `curl :8102/stats`.
   - **Allocator down:** restart it. It replays its WAL, and the order service re-sends anything not yet forwarded (safe, de-duplicated).
   - **Allocator up, `pending` growing:** the floor is saturated. Check the `plan_ms` p99 and the drive-unit and station counts.
2. Check `forward_failures_total` and `client_breaker_rejections_total` on the order service. If the breaker is open, follow DependencyDown.
3. **Cleared when:** the backlog falls below 400. In the chaos test it cleared 2.5 s and 2.9 s after the two restarts.

## DependencyDown

**Fires when:** the order service's circuit breaker to the allocator is OPEN, or a service does not answer `/stats`.

1. Find which service is down (`/health` on 8101 and 8102) and restart it. Both recover from their WAL on start.
2. Watch `wal_torn_bytes_on_recovery`. A non-zero value means a record was being written during the crash; that request was never acknowledged, so the client retries it.
3. Confirm recovery: the breaker returns to CLOSED, and `orders_unforwarded` and the allocator's `done` count drain to 0.

## After any incident

- Run `python3 tools/analytics.py`-style queries on the WALs to confirm nothing was completed twice:
  `SELECT id FROM order_events WHERE kind='done' GROUP BY id HAVING COUNT(*) > 1` should return no rows.
- Write down the timeline (kill, restart, alarm fired, alarm cleared) and the latency per phase, as `results/RESULTS.md` does for the chaos test.

# Findings

Numbers come from `python3 tools/run_all.py` (results/*.json) unless a row says "first run".

| ID | Finding | Evidence | Change |
|---|---|---|---|
| F-01 | **Completion callbacks one at a time could not keep up.** The first chaos run never finished: the allocator had completed every order, but reported them back one HTTP call and two fsyncs at a time. | First run: 1,089 of 4,000 orders still not DONE after 120 s; about 22 callbacks/s. | Batched completions (`POST /orders/done` with up to 500 ids, one WAL write per batch on each side). All 4,000 done 0.4 s after the load ended. |
| F-02 | **A crash must lose nothing and duplicate nothing.** | `kill -9` of the allocator (t = 4 s) and of the order service (t = 9 s) under 250 orders/s: 4,000/4,000 DONE, 0 lost, 0 completed twice, 0 received twice. Checked with SQL over both WALs. | An order is acknowledged only after its WAL record is fsynced. Forwarding and completion are at-least-once with retries, and both receivers de-duplicate by order id (idempotency key). On restart each service replays its WAL; a torn last record is dropped (unit-tested). |
| F-03 | **Clients will retry and resend, so the API must be idempotent.** | 151 client retries and 93 deliberate resends during the faults: all answered with the existing order, none created twice. | Claim the id in memory, then write the WAL; a racing duplicate waits until the first record is durable before answering, so it can never be told "accepted" for something that is not on disk. |
| F-04 | **Failing fast protects the caller.** | While the allocator was down the breaker opened (DependencyDown alarm 4.3–6.8 s), the forwarder backed off with jitter, and orders kept being accepted and queued. | Circuit breaker (3 failures → open, 500 ms cool-down, one trial call), exponential backoff with full jitter, timeouts on every call. |
| F-05 | **An fsync per accept caps throughput and hurts tail latency.** | 32 clients: per-write fsync 1,191 orders/s, p99 129 ms; group commit 1,706 orders/s, p99 46 ms, 0.53 fsyncs per order. | Group commit: concurrent accepts wait on one shared fsync; still nothing is acknowledged before it is on disk. The client is Python on the same machine, so the ceiling is partly the load generator. |
| F-06 | **Assigning orders by LP beats nearest-station greedy.** | Floor model, 4 h at 85% station load: best p95 latency 129.2 s (LP) vs 137.8 s (greedy); at a 6 s window, 152.8 vs 158.6 m of travel per order; utilisation 0.80 vs 0.82. | Transportation LP per batch window (simplex, ~0.5 ms for 40 orders x 4 stations), capped at the 120 oldest orders so it stays fast after a backlog. |
| F-07 | **The batching window is a real trade-off.** Longer windows give the LP more choice (less travel), but orders wait longer. | p95 has a minimum near 5 s for LP and 12 s for greedy. Golden-section search on log(window) found 4.9 s / 128.7 s in 12 simulations. For greedy it stopped at 14.8 s / 140.5 s, slightly worse than the grid's 12 s / 137.8 s, because the simulated objective is noisy. | Default window 5 s. Run the search with common random numbers (the same order stream for every candidate), and check it against a coarse grid. |
| F-08 | **Rounding station capacity per window caused artificial queueing.** | First floor model: p95 jumped between neighbouring windows (e.g. 6 s and 10 s worse than 4, 8 and 12 s). | Carry the fractional capacity to the next window. The curves are now smooth. |

## How this would map to AWS (not deployed)

| Here | On AWS |
|---|---|
| order-service WAL + idempotency map | DynamoDB table keyed by order id (conditional put for idempotency) |
| forwarder / outbox with retries | SQS queues with a dead-letter queue (the pattern of my fleet-telemetry project) |
| two JVM processes | ECS/Fargate services behind a load balancer, health checks on `/health` |
| `/metrics` + alarms in chaos.py | CloudWatch metrics and alarms paging the on-call |

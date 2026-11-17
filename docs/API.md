# API

## order-service (default port 8101)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/orders` | `{"id": "A-1", "skus": [12, 40]}` | 202 accepted (durable); 200 duplicate with current status; 400 bad input; 503 not stored, retry |
| GET | `/orders/{id}` | | `{"id", "status": "ACCEPTED" or "FORWARDED" or "DONE"}` |
| POST | `/orders/done` | `{"ids": [...]}` (from the allocator) | `{"updated", "duplicates", "unknown"}`; idempotent |
| GET | `/stats` | | counts per status, duplicates absorbed, breaker state |
| GET | `/metrics`, `/health` | | Prometheus text; `{"ok": true}` |

The id is the idempotency key: send the same id again after a timeout and you get the original order back.

## allocator (default port 8102)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/tasks` | `{"orders": [{"id", "skus"}...]}` | `{"accepted", "duplicates"}`; idempotent per id |
| GET | `/stats`, `/metrics`, `/health` | | |

Options: `--planner lp|greedy`, `--window-s 5`, `--scale 0.002` (wall seconds per simulated second),
`--units 160`, `--wal GROUP_COMMIT|PER_WRITE`, `--data <dir>`.

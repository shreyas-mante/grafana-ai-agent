# Runbook: High HTTP 5xx error rate

## Symptoms
- Alert `HighErrorRate` or `HighPaymentErrorRate` firing.
- `http_server_requests_seconds_count{status=~"5.."}` rising as a share of all requests.

## Diagnosis
1. Find which endpoints fail: break the error rate down `by (uri)`.
2. Check the top exceptions reported by the application (PulseAgent errors view).
3. Check whether a deploy happened in the 30 minutes before the spike.
4. Check saturation signals: connection pools, thread pools, CPU, memory, GC.
5. Check downstream dependencies: databases, caches, third-party APIs.

## Fix
- If a deploy correlates with the spike and the exception points at changed code, roll back first and fix forward later.
- If a dependency is degraded, enable its circuit breaker or fallback.
- If a resource pool is exhausted, follow the "Database connection pool saturation" runbook.

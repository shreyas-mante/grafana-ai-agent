# Runbook: High API latency

## Symptoms
- p95 or p99 latency above SLO (for example p95 > 500 ms).

## Diagnosis
- p95 latency: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))`.
- Latency rises with traffic: capacity problem; scale out.
- Latency rises with flat traffic: a slow dependency, lock contention, GC pauses, or pool waits.
- Use traces to find which span grew.

## Fix
- Add timeouts to every remote call (connect and read), so a slow dependency cannot hold threads or connections for long.
- Cache stable reads, and move non-critical work to async queues.
- Roll back a recent deploy if the latency started right after it.

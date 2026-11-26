# Runbook: Database connection pool saturation

Applies to: any JVM service using HikariCP or a similar connection pool.

## Symptoms
- Errors like `Connection is not available, request timed out after Nms` (HikariPool, SQLTransientConnectionException).
- Pool active connections at or near the maximum (`hikaricp_connections_active` / `hikaricp_connections_max`, or the service's own pool gauge).
- p95 latency rises sharply while CPU and memory stay near baseline.
- HTTP 5xx error rate rises on endpoints that touch the database.

## Common causes
1. Slow work done while holding a connection: an HTTP call to a third party, file I/O, or sleeping inside a transaction or a pooled section. One slow dependency then drains the whole pool.
2. Connection leaks: a code path that acquires a connection and never releases it (missing finally/try-with-resources).
3. Long-running queries or lock contention in the database.
4. Traffic spike beyond what the pool size supports.

## Diagnosis
- Compare pool usage with request rate: if traffic is flat but pool usage hits 100%, suspect slow work inside the pooled section or a leak.
- Check traces or code for remote calls made between acquiring and releasing a connection.
- Check the most recent deploy: a change that moved code into a transaction is a frequent trigger.

## Fix
Move slow external calls (payment gateways, HTTP APIs) outside the transaction or pooled section, so a connection is held only for the actual database work. Make sure every acquire has a matching release in a finally block. As a short-term mitigation, roll back the offending deploy; raising the pool size only delays exhaustion.

## Escalation
Page the owning team if 5xx stays above 5% for more than 10 minutes.

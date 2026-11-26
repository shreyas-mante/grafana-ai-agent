# INC-219: Checkout outage, connection pool exhausted by a slow payment gateway

Date: 2026-03-14
Severity: SEV-1
Service: checkout-service
Duration: 42 minutes

## Symptoms
Checkout 5xx error rate reached 31%. p95 latency went from 220 ms to 6 s. Errors were `HikariPool-1 - Connection is not available, request timed out`. CPU and memory were normal.

## Root cause
A refactor (PR #311) moved the call to the external payment gateway inside the `@Transactional` method that saves the order. When the gateway slowed down to about 2 seconds per call, every request held a database connection for the whole gateway call, and the pool of 10 connections was exhausted.

## Fix
Call the payment gateway before opening the transaction, and keep the transaction only around the database write. Added a 2-second read timeout to the gateway client. Rolled back PR #311 as the immediate mitigation.

## Lessons
- Never make remote calls while holding a database connection.
- Alert on pool saturation, not only on error rate.

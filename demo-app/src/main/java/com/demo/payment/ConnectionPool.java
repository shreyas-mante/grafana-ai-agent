package com.demo.payment;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** A tiny stand-in for a database connection pool (like HikariCP), with Prometheus gauges. */
@Component
public class ConnectionPool {

    static final int MAX = 10;
    private final Semaphore permits = new Semaphore(MAX, true);

    public ConnectionPool(MeterRegistry registry) {
        Gauge.builder("demo_db_pool_active", permits, p -> MAX - p.availablePermits()).register(registry);
        Gauge.builder("demo_db_pool_max", () -> MAX).register(registry);
    }

    public void acquire() {
        try {
            if (!permits.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                throw new PoolExhaustedException("HikariPool-1 - Connection is not available, request timed out after 500ms (active="
                        + (MAX - permits.availablePermits()) + ", max=" + MAX + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoolExhaustedException("Interrupted while waiting for a connection");
        }
    }

    public void release() {
        permits.release();
    }

    public static class PoolExhaustedException extends RuntimeException {
        public PoolExhaustedException(String message) { super(message); }
    }
}

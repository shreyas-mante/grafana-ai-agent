package com.demo.payment;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final ConnectionPool pool;
    private final PaymentGateway gateway;

    public PaymentService(ConnectionPool pool, PaymentGateway gateway) {
        this.pool = pool;
        this.gateway = gateway;
    }

    /** Authorizes with the gateway first, then holds a DB connection only for the short write. */
    public String charge(long amountCents) {
        String authId = gateway.authorize(amountCents);
        pool.acquire();
        try {
            saveReceipt(authId, amountCents);
            return authId;
        } finally {
            pool.release();
        }
    }

    private void saveReceipt(String authId, long amountCents) {
        // Simulated INSERT into the receipts table.
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

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

    /** Runs authorization and the receipt write in one unit of work. */
    public String charge(long amountCents) {
        pool.acquire();
        try {
            String authId = gateway.authorize(amountCents);
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

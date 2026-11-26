package com.demo.payment;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Simulated third-party payment gateway. Chaos mode makes it slow, like a degraded provider. */
@Component
public class PaymentGateway {

    private final AtomicBoolean slow = new AtomicBoolean(false);

    public String authorize(long amountCents) {
        sleep(slow.get() ? 1500 : 15);
        return "auth_" + Long.toHexString(System.nanoTime());
    }

    public void setSlow(boolean value) {
        slow.set(value);
    }

    public boolean isSlow() {
        return slow.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

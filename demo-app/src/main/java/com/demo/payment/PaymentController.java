package com.demo.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {

    private final PaymentService payments;
    private final PaymentGateway gateway;

    public PaymentController(PaymentService payments, PaymentGateway gateway) {
        this.payments = payments;
        this.gateway = gateway;
    }

    @PostMapping("/api/payments")
    public Map<String, Object> charge(@RequestParam(defaultValue = "1000") long amountCents) {
        return Map.of("authorizationId", payments.charge(amountCents), "amountCents", amountCents);
    }

    /** Demo only: makes the payment gateway slow (on) or healthy (off). Bound to localhost in the demo. */
    @PostMapping("/chaos/{mode}")
    public Map<String, Object> chaos(@PathVariable String mode) {
        gateway.setSlow("on".equals(mode));
        return Map.of("gatewaySlow", gateway.isSlow());
    }
}

package com.demo.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Sends steady synthetic traffic to the payment API so Prometheus has something to show. */
@Component
public class LoadGenerator {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight = new Semaphore(60);
    private final RestClient client;
    private final int rps;
    private volatile boolean ready;

    public LoadGenerator(@Value("${server.port}") int port, @Value("${demo.load-rps:20}") int rps) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().requestFactory(factory).baseUrl("http://127.0.0.1:" + port).build();
        this.rps = rps;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        ready = true;
    }

    @Scheduled(fixedRate = 1000)
    void tick() {
        if (!ready) return;
        for (int i = 0; i < rps; i++) {
            if (!inFlight.tryAcquire()) return;
            pool.submit(() -> {
                try {
                    client.post().uri("/api/payments?amountCents={a}", 500 + (int) (Math.random() * 5000)).retrieve().toBodilessEntity();
                } catch (Exception ignored) {
                    // failures are what the demo is about
                } finally {
                    inFlight.release();
                }
            });
        }
    }
}

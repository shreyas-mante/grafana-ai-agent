package com.pulseagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.investigation.InvestigationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grafana / Alertmanager webhook receiver. Configure the contact point with
 * "Authorization: Bearer <ingest key>". Returns 202 immediately; work runs asynchronously.
 */
@RestController
public class WebhookController {

    private final InvestigationService investigations;

    public WebhookController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    @PostMapping("/api/v1/webhooks/grafana")
    public ResponseEntity<?> grafana(@RequestBody JsonNode payload) {
        List<String> started = new ArrayList<>();
        int considered = 0;
        for (JsonNode alert : payload.path("alerts")) {
            if (++considered > 10) break;
            String status = alert.path("status").asText(payload.path("status").asText());
            if (!"firing".equalsIgnoreCase(status)) continue;
            String name = clean(alert.path("labels").path("alertname").asText("alert"), 120);
            String svc = clean(alert.path("labels").path("service").asText(alert.path("labels").path("job").asText(null)), 100);
            if (svc != null && !svc.matches("[A-Za-z0-9._-]+")) svc = null;
            String summary = clean(alert.path("annotations").path("summary").asText(""), 300);
            investigations.startFromAlert(name, svc, summary).ifPresent(i -> started.add(i.id));
        }
        return ResponseEntity.accepted().body(Map.of("investigations", started));
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String c = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return JsonUtil.truncate(c, max);
    }
}

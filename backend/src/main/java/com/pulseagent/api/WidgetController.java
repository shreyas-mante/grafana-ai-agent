package com.pulseagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.investigation.Investigation;
import com.pulseagent.investigation.InvestigationService;
import com.pulseagent.tenant.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API for the embeddable chat widget. Widget keys are public by nature (they live in a web
 * page), so they can only start investigations and read a short summary of the ones they
 * started, never code, logs, settings or other investigations.
 */
@RestController
public class WidgetController {

    public record AskRequest(@NotBlank @Size(max = 500) String question,
                             @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]*") String service) {}

    private final InvestigationService investigations;
    private final Map<String, String> startedBy = new ConcurrentHashMap<>();

    public WidgetController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    @PostMapping("/api/widget/investigations")
    public ResponseEntity<?> ask(@Valid @RequestBody AskRequest req) {
        Actor a = Actor.current();
        Investigation inv = investigations.start(a.workspaceId(), req.question(),
                req.service() == null || req.service().isBlank() ? null : req.service(), 30, null, "widget");
        if (startedBy.size() > 10_000) startedBy.clear();
        startedBy.put(inv.id, a.id());
        return ResponseEntity.accepted().body(Map.of("id", inv.id));
    }

    @GetMapping("/api/widget/investigations/{id}")
    public ResponseEntity<?> status(@PathVariable String id) {
        Actor a = Actor.current();
        if (!id.matches(InvestigationController.ID) || !a.id().equals(startedBy.get(id))) return ResponseEntity.notFound().build();
        return investigations.get(a.workspaceId(), id).<ResponseEntity<?>>map(inv -> {
            ObjectNode o = JsonUtil.obj();
            o.put("status", inv.status().name()).put("steps", inv.stepCount());
            JsonNode r = inv.report();
            if (r != null) {
                o.put("title", r.path("title").asText(""));
                o.put("summary", r.path("summary").asText(""));
                o.put("likelyCause", r.path("likelyCause").asText(""));
                o.put("confidence", r.path("confidence").asText(""));
                ArrayNode next = o.putArray("nextSteps");
                r.path("nextSteps").forEach(s -> { if (next.size() < 3) next.add(JsonUtil.truncate(s.asText(), 300)); });
            }
            if (inv.status() == Investigation.Status.FAILED) o.put("failure", "The investigation could not finish.");
            return ResponseEntity.ok(o);
        }).orElse(ResponseEntity.notFound().build());
    }
}

package com.pulseagent.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.investigation.Investigation;
import com.pulseagent.investigation.InvestigationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Investigations API, used by the web UI (/api) and by integrations with an API key (/api/v1). */
@RestController
@RequestMapping({"/api/investigations", "/api/v1/investigations"})
public class InvestigationController {

    public record CreateRequest(
            @NotBlank @Size(max = 1000) String question,
            @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]*") String service,
            @Min(5) @Max(1440) Integer lookbackMinutes,
            @Pattern(regexp = "(err_[0-9a-f]{16})?") String errorGroupId) {}

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}

    private static final String ID = "inv_[0-9a-f]{16}";
    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ObjectNode> create(@Valid @RequestBody CreateRequest req) {
        String svc = req.service() == null || req.service().isBlank() ? null : req.service();
        String err = req.errorGroupId() == null || req.errorGroupId().isBlank() ? null : req.errorGroupId();
        Investigation inv = service.start(req.question(), svc, req.lookbackMinutes(), err, err != null ? "error" : "user");
        ObjectNode body = inv.toJson(false);
        body.put("statusUrl", "/api/v1/investigations/" + inv.id);
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping
    public ArrayNode list() {
        ArrayNode a = JsonUtil.arr();
        service.list().forEach(i -> a.add(i.toJson(false)));
        return a;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ObjectNode> get(@PathVariable String id) {
        if (!id.matches(ID)) return ResponseEntity.notFound().build();
        return service.get(id).map(i -> ResponseEntity.ok(i.toJson(true))).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/chat")
    public ResponseEntity<?> chat(@PathVariable String id, @Valid @RequestBody ChatRequest req) {
        if (!id.matches(ID)) return ResponseEntity.notFound().build();
        var inv = service.get(id);
        if (inv.isEmpty()) return ResponseEntity.notFound().build();
        if (inv.get().status() != Investigation.Status.COMPLETED) {
            return ResponseEntity.status(409).body(Map.of("error", "Wait for the investigation to finish first."));
        }
        String answer = service.chat(inv.get(), req.message());
        return ResponseEntity.ok(Map.of("answer", answer));
    }
}

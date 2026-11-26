package com.pulseagent.api;

import com.pulseagent.actions.ActionService;
import com.pulseagent.investigation.ScheduleService;
import com.pulseagent.tenant.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Scheduled checks (members) and remediation approvals (approve/reject: owners only). */
@RestController
public class OpsController {

    public record ScheduleRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 1000) String question,
                                  @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]*") String service,
                                  @Min(15) @Max(1440) int intervalMinutes) {}

    public record ToggleRequest(boolean enabled) {}

    private static final String SCH = "sch_[A-Za-z0-9]{12}";
    private static final String ACT = "act_[A-Za-z0-9]{12}";

    private final ScheduleService schedules;
    private final ActionService actions;

    public OpsController(ScheduleService schedules, ActionService actions) {
        this.schedules = schedules;
        this.actions = actions;
    }

    @GetMapping("/api/schedules")
    public List<Map<String, Object>> schedules() {
        return schedules.list(Actor.current().workspaceId());
    }

    @PostMapping("/api/schedules")
    public ResponseEntity<?> create(@Valid @RequestBody ScheduleRequest req) {
        String id = schedules.create(Actor.current(), req.name().replaceAll("[\\p{Cntrl}]", ""), req.question(),
                req.service() == null || req.service().isBlank() ? null : req.service(), req.intervalMinutes());
        return ResponseEntity.status(201).body(Map.of("id", id));
    }

    @PostMapping("/api/schedules/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable String id, @RequestBody ToggleRequest req) {
        if (!id.matches(SCH) || !schedules.setEnabled(Actor.current(), id, req.enabled())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("enabled", req.enabled()));
    }

    @DeleteMapping("/api/schedules/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!id.matches(SCH) || !schedules.delete(Actor.current(), id)) return ResponseEntity.notFound().build();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/actions")
    public List<Map<String, Object>> actions() {
        return actions.list(Actor.current().workspaceId());
    }

    @PostMapping("/api/actions/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id) {
        if (!id.matches(ACT)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("result", actions.approve(Actor.current(), id)));
    }

    @PostMapping("/api/actions/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id) {
        if (!id.matches(ACT)) return ResponseEntity.notFound().build();
        return actions.reject(Actor.current(), id).<ResponseEntity<?>>map(err -> ResponseEntity.status(409).body(Map.of("error", err)))
                .orElse(ResponseEntity.ok(Map.of("ok", true)));
    }
}

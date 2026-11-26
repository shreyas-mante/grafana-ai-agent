package com.pulseagent.api;

import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/knowledge", "/api/v1/knowledge"})
public class KnowledgeController {

    public record AddRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "runbook|incident|doc") String type,
            @NotBlank @Size(max = 200_000) String content) {}

    private final KnowledgeBase kb;
    private final AuditService audit;

    public KnowledgeController(KnowledgeBase kb, AuditService audit) {
        this.kb = kb;
        this.audit = audit;
    }

    @GetMapping
    public List<KnowledgeBase.Doc> list() {
        return kb.list(Actor.current().workspaceId());
    }

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody AddRequest req) {
        Actor a = Actor.current();
        var doc = kb.add(a.workspaceId(), req.title(), req.type(), req.content(), "upload");
        audit.log(a, "knowledge.added", doc.title());
        return ResponseEntity.status(201).body(doc);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        Actor a = Actor.current();
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) return ResponseEntity.notFound().build();
        if (!kb.delete(a.workspaceId(), id)) return ResponseEntity.notFound().build();
        audit.log(a, "knowledge.deleted", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q, @RequestParam(required = false) String type) {
        if (q.length() > 1000) q = q.substring(0, 1000);
        if (type != null && !type.matches("runbook|incident|doc")) type = null;
        return Map.of("results", kb.search(Actor.current().workspaceId(), q, 5, type));
    }
}

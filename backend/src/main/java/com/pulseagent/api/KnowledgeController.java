package com.pulseagent.api;

import com.pulseagent.rag.KnowledgeBase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    public KnowledgeController(KnowledgeBase kb) {
        this.kb = kb;
    }

    @GetMapping
    public List<KnowledgeBase.Doc> list() {
        return kb.list();
    }

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody AddRequest req) {
        return ResponseEntity.status(201).body(kb.add(req.title(), req.type(), req.content(), "upload"));
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q, @RequestParam(required = false) String type) {
        if (q.length() > 1000) q = q.substring(0, 1000);
        if (type != null && !type.matches("runbook|incident|doc")) type = null;
        return Map.of("results", kb.search(q, 5, type));
    }
}

package com.pulseagent.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.errors.ErrorEventRequest;
import com.pulseagent.errors.ErrorGroup;
import com.pulseagent.errors.ErrorStore;
import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.EnvFactory;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ErrorController {

    private final ErrorStore store;
    private final EnvFactory envs;

    public ErrorController(ErrorStore store, EnvFactory envs) {
        this.store = store;
        this.envs = envs;
    }

    /** Ingest endpoint for the SDK. Accepts the ingest key (least privilege) or a member. */
    @PostMapping("/api/v1/errors")
    public ResponseEntity<?> ingest(@Valid @RequestBody ErrorEventRequest event) {
        String ws = Actor.current().workspaceId();
        ErrorGroup g = store.record(ws, event, envs.forWorkspace(ws).appPackages());
        return ResponseEntity.accepted().body(Map.of("errorId", g.id(), "count", g.count()));
    }

    @GetMapping("/api/errors")
    public ArrayNode list(@RequestParam(required = false) String service) {
        String ws = Actor.current().workspaceId();
        if (service != null && !service.matches("[A-Za-z0-9._-]{0,100}")) service = null;
        var packages = envs.forWorkspace(ws).appPackages();
        ArrayNode out = JsonUtil.arr();
        for (ErrorGroup g : store.recent(ws, service, 100)) {
            ObjectNode o = out.addObject();
            o.put("id", g.id()).put("service", g.service()).put("environment", g.environment())
                    .put("exceptionClass", g.exceptionClass()).put("message", JsonUtil.truncate(g.message(), 400))
                    .put("count", g.count()).put("firstSeen", g.firstSeen().toString()).put("lastSeen", g.lastSeen().toString())
                    .put("httpMethod", g.httpMethod()).put("path", g.path()).put("commitSha", g.commitSha());
            ErrorStore.appFrames(g.frames(), packages, 1).forEach(f ->
                    o.put("topFrame", f.className() + "." + f.methodName() + "(" + f.fileName() + ":" + f.lineNumber() + ")"));
        }
        return out;
    }
}

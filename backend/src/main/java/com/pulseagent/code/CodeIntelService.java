package com.pulseagent.code;

import com.pulseagent.code.CodeModels.BlameInfo;
import com.pulseagent.code.CodeModels.CodeContext;
import com.pulseagent.code.CodeModels.CodeProvider;
import com.pulseagent.code.CodeModels.CommitInfo;
import com.pulseagent.config.AppProperties;
import com.pulseagent.errors.ErrorEventRequest;
import com.pulseagent.security.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Maps a stack frame to the exact file and line at the deployed commit, with blame details. */
@Service
public class CodeIntelService {

    private static final Logger log = LoggerFactory.getLogger(CodeIntelService.class);
    private static final int CONTEXT_LINES = 12;
    private final CodeProvider provider;

    public CodeIntelService(AppProperties props) {
        CodeProvider p = null;
        try {
            if (props.code().githubConfigured()) p = new GitHubProvider(props.code());
            else if (props.code().localConfigured()) p = new LocalGitProvider(props.code().localRepoPath());
        } catch (Exception e) {
            log.warn("Code integration disabled: {}", e.getMessage());
        }
        this.provider = p;
        log.info("Code provider: {}", p == null ? "none" : p.name());
    }

    public String providerName() { return provider == null ? "none" : provider.name(); }

    public Optional<CodeContext> context(ErrorEventRequest.Frame frame, String commitSha) {
        if (provider == null || frame == null || frame.lineNumber() <= 0) return Optional.empty();
        String ref = commitSha != null && commitSha.matches("[0-9a-f]{7,40}") ? commitSha : "HEAD";
        try {
            Optional<String> path = resolvePath(frame, ref);
            if (path.isEmpty() && !"HEAD".equals(ref)) {
                ref = "HEAD";
                path = resolvePath(frame, ref);
            }
            if (path.isEmpty()) return Optional.empty();
            String note = null;
            String content = provider.fileContent(ref, path.get());
            if (content == null && !"HEAD".equals(ref)) {
                note = "Commit " + ref + " was not found; showing the latest code instead.";
                ref = "HEAD";
                content = provider.fileContent(ref, path.get());
            }
            if (content == null) return Optional.empty();
            List<String> all = content.lines().toList();
            int line = Math.min(frame.lineNumber(), Math.max(1, all.size()));
            int start = Math.max(1, line - CONTEXT_LINES);
            int end = Math.min(all.size(), line + CONTEXT_LINES);
            List<String> snippet = all.subList(start - 1, end).stream()
                    .map(l -> Redactor.redact(l.length() > 300 ? l.substring(0, 300) : l)).toList();
            BlameInfo blame = provider.blame(ref, path.get(), line);
            return Optional.of(new CodeContext(provider.name(), path.get(), line, ref, start, snippet, blame, note));
        } catch (Exception e) {
            log.warn("Code lookup failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public List<CommitInfo> recentCommits(int hours) {
        if (provider == null) return List.of();
        try {
            return provider.recentCommits(hours);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Matches the frame's package path and file name against files that exist in the repository. */
    private Optional<String> resolvePath(ErrorEventRequest.Frame frame, String ref) {
        String cls = frame.className();
        int dollar = cls.indexOf('$');
        if (dollar > 0) cls = cls.substring(0, dollar);
        int lastDot = cls.lastIndexOf('.');
        String pkgPath = lastDot > 0 ? cls.substring(0, lastDot).replace('.', '/') : "";
        String fileName = frame.fileName() != null && !frame.fileName().isBlank()
                ? frame.fileName() : cls.substring(lastDot + 1) + ".java";
        String suffix = (pkgPath.isEmpty() ? "" : pkgPath + "/") + fileName;
        List<String> files = provider.listFiles(ref);
        Optional<String> exact = files.stream()
                .filter(f -> f.equals(suffix) || f.endsWith("/" + suffix))
                .min(Comparator.comparingInt(String::length));
        if (exact.isPresent()) return exact;
        List<String> byName = files.stream().filter(f -> f.endsWith("/" + fileName)).toList();
        return byName.size() == 1 ? Optional.of(byName.get(0)) : Optional.empty();
    }
}

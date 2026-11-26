package com.pulseagent.rag;

import com.pulseagent.config.AppProperties;
import com.pulseagent.llm.EmbeddingService;
import com.pulseagent.security.Redactor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Retrieval-augmented knowledge base over runbooks, past incidents and uploaded documents.
 * Documents are split into heading-based chunks, embedded, and searched by cosine similarity.
 */
@Service
public class KnowledgeBase {

    public record Doc(String id, String title, String type, String source, String createdAt, int chunks) {}

    public record Hit(String docId, String title, String type, double score, String excerpt) {}

    private record Chunk(String docId, String title, String type, String text, float[] vector) {}

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final int CHUNK_CHARS = 1200;
    private static final Set<String> TYPES = Set.of("runbook", "incident", "doc");

    private final EmbeddingService embeddings;
    private final Path seedDir;
    private final Path uploadDir;
    private final List<Doc> docs = new CopyOnWriteArrayList<>();
    private final List<Chunk> chunks = new CopyOnWriteArrayList<>();

    public KnowledgeBase(EmbeddingService embeddings, AppProperties props) {
        this.embeddings = embeddings;
        this.seedDir = Path.of(props.knowledge().dir()).toAbsolutePath().normalize();
        this.uploadDir = Path.of(props.knowledge().dataDir()).toAbsolutePath().normalize().resolve("kb");
    }

    @PostConstruct
    void load() {
        Thread.ofVirtual().name("kb-loader").start(() -> {
            try {
                Files.createDirectories(uploadDir);
                trySetOwnerOnly(uploadDir.getParent());
                int n = loadDir(seedDir, "seed") + loadDir(uploadDir, "upload");
                log.info("Knowledge base ready: {} documents, {} chunks", n, chunks.size());
            } catch (Exception e) {
                log.warn("Knowledge base load failed: {}", e.getMessage());
            }
        });
    }

    private int loadDir(Path dir, String source) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir, 3)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted().toList();
        }
        int n = 0;
        for (Path f : files) {
            if (Files.size(f) > 500_000) continue;
            String content = Files.readString(f, StandardCharsets.UTF_8);
            String type = typeFor(f);
            String id = f.getFileName().toString().replaceAll("\\.(md|txt)$", "");
            index(id, titleOf(content, id), type, source, content);
            n++;
        }
        return n;
    }

    private static String typeFor(Path f) {
        String s = f.toString().toLowerCase(Locale.ROOT);
        String name = f.getFileName().toString();
        if (s.contains("/runbooks/") || name.startsWith("runbook-")) return "runbook";
        if (s.contains("/incidents/") || name.startsWith("incident-")) return "incident";
        return "doc";
    }

    /** Adds a document, persists it to the data directory, and indexes it. */
    public Doc add(String title, String type, String content, String source) {
        String t = TYPES.contains(type) ? type : "doc";
        String clean = Redactor.redact(content);
        String safeTitle = Redactor.redact(title.replaceAll("[\\r\\n]+", " ").trim());
        String id = t + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Files.createDirectories(uploadDir);
            Path file = uploadDir.resolve(id + ".md").normalize();
            if (!file.startsWith(uploadDir)) throw new IllegalStateException("invalid path");
            Files.writeString(file, "# " + safeTitle + "\n\n" + clean, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not persist document: {}", e.getMessage());
        }
        return index(id, safeTitle, t, source, "# " + safeTitle + "\n\n" + clean);
    }

    private synchronized Doc index(String id, String title, String type, String source, String content) {
        List<String> parts = chunk(content);
        List<String> forEmbedding = parts.stream().map(p -> title + "\n" + p).toList();
        List<float[]> vectors = embeddings.embedDocuments(forEmbedding);
        for (int i = 0; i < parts.size(); i++) chunks.add(new Chunk(id, title, type, parts.get(i), vectors.get(i)));
        Doc d = new Doc(id, title, type, source, Instant.now().toString(), parts.size());
        docs.add(d);
        return d;
    }

    public List<Hit> search(String query, int k, String typeFilter) {
        if (query == null || query.isBlank() || chunks.isEmpty()) return List.of();
        float[] q = embeddings.embedQuery(query.length() > 2000 ? query.substring(0, 2000) : query);
        List<Hit> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            if (typeFilter != null && !typeFilter.isBlank() && !typeFilter.equals(c.type())) continue;
            double s = EmbeddingService.cosine(q, c.vector());
            scored.add(new Hit(c.docId(), c.title(), c.type(), Math.round(s * 1000) / 1000.0,
                    c.text().length() > 900 ? c.text().substring(0, 900) + "…" : c.text()));
        }
        scored.sort(Comparator.comparingDouble(Hit::score).reversed());
        List<Hit> top = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Hit h : scored) {
            if (seen.add(h.docId())) top.add(h);
            if (top.size() >= Math.max(1, Math.min(k, 10))) break;
        }
        return top;
    }

    public List<Doc> list() {
        return docs.stream().sorted(Comparator.comparing(Doc::type).thenComparing(Doc::title)).toList();
    }

    public int size() { return docs.size(); }

    static List<String> chunk(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : content.split("\n")) {
            boolean heading = line.startsWith("## ") || line.startsWith("### ");
            if ((heading && cur.length() > 200) || cur.length() + line.length() > CHUNK_CHARS) {
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private static String titleOf(String content, String fallback) {
        return content.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).trim()).orElse(fallback);
    }

    private static void trySetOwnerOnly(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception ignored) {
            // not a POSIX filesystem
        }
    }
}

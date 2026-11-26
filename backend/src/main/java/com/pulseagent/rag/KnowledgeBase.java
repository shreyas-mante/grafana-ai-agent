package com.pulseagent.rag;

import com.pulseagent.llm.EmbeddingService;
import com.pulseagent.llm.GeminiClient;
import com.pulseagent.llm.LlmFactory;
import com.pulseagent.security.Redactor;
import com.pulseagent.tenant.Crypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieval-augmented knowledge base per workspace: runbooks, past incidents and team docs.
 * Documents live in the database; each workspace's search index is built lazily in memory,
 * split into heading-based chunks, and searched by cosine similarity.
 */
@Service
public class KnowledgeBase {

    public record Doc(String id, String title, String type, String source, String createdAt) {}

    public record Hit(String docId, String title, String type, double score, String excerpt) {}

    private record Chunk(String docId, String title, String type, String text, float[] vector) {}

    private record Index(String mode, List<Chunk> chunks, long builtAt) {}

    private static final int CHUNK_CHARS = 1200;
    private static final Set<String> TYPES = Set.of("runbook", "incident", "doc");

    private final JdbcTemplate db;
    private final LlmFactory llm;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();

    public KnowledgeBase(JdbcTemplate db, LlmFactory llm) {
        this.db = db;
        this.llm = llm;
    }

    public Doc add(String ws, String title, String type, String content, String source) {
        String t = TYPES.contains(type) ? type : "doc";
        String safeTitle = Redactor.redact(title.replaceAll("[\\r\\n]+", " ").trim());
        if (safeTitle.length() > 200) safeTitle = safeTitle.substring(0, 200);
        String id = t + "-" + Crypto.randomToken(6).replaceAll("[^A-Za-z0-9]", "x").toLowerCase();
        String body = "# " + safeTitle + "\n\n" + Redactor.redact(content);
        Timestamp now = Timestamp.from(Instant.now());
        db.update("insert into kb_docs(workspace_id, id, title, type, source, content, created_at) values (?,?,?,?,?,?,?)",
                ws, id, safeTitle, t, source.length() > 100 ? source.substring(0, 100) : source, body, now);
        indexes.remove(ws);
        return new Doc(id, safeTitle, t, source, now.toInstant().toString());
    }

    public boolean delete(String ws, String id) {
        boolean ok = db.update("delete from kb_docs where workspace_id = ? and id = ?", ws, id) > 0;
        if (ok) indexes.remove(ws);
        return ok;
    }

    public List<Doc> list(String ws) {
        return db.query("select id, title, type, source, created_at from kb_docs where workspace_id = ? order by type, title",
                (rs, i) -> new Doc(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant().toString()), ws);
    }

    public int size(String ws) {
        Integer n = db.queryForObject("select count(*) from kb_docs where workspace_id = ?", Integer.class, ws);
        return n == null ? 0 : n;
    }

    public String mode(String ws) {
        return index(ws).mode();
    }

    public List<Hit> search(String ws, String query, int k, String typeFilter) {
        if (query == null || query.isBlank()) return List.of();
        Index idx = index(ws);
        if (idx.chunks().isEmpty()) return List.of();
        Optional<GeminiClient> gemini = llm.geminiFor(ws);
        float[] q;
        try {
            q = EmbeddingService.embedQuery(gemini, idx.mode(), query.length() > 2000 ? query.substring(0, 2000) : query);
        } catch (Exception e) {
            indexes.remove(ws);
            return List.of();
        }
        List<Hit> scored = new ArrayList<>();
        for (Chunk c : idx.chunks()) {
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

    private Index index(String ws) {
        Optional<GeminiClient> gemini = llm.geminiFor(ws);
        String wanted = gemini.isPresent() ? "gemini" : "local";
        Index existing = indexes.get(ws);
        // Rebuild when the embedding mode changes; a failed Gemini build retries at most every 10 minutes.
        if (existing != null && (existing.mode().equals(wanted) || System.currentTimeMillis() - existing.builtAt() < 600_000)) {
            return existing;
        }
        synchronized (this) {
            existing = indexes.get(ws);
            if (existing != null && existing.mode().equals(wanted)) return existing;
            List<String[]> docs = db.query("select id, title, type, content from kb_docs where workspace_id = ?",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, ws);
            List<Chunk> meta = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (String[] d : docs) {
                for (String part : chunk(d[3])) {
                    meta.add(new Chunk(d[0], d[1], d[2], part, null));
                    texts.add(d[1] + "\n" + part);
                }
            }
            EmbeddingService.Batch batch = EmbeddingService.embedDocuments(gemini, texts);
            List<Chunk> chunks = new ArrayList<>(meta.size());
            for (int i = 0; i < meta.size(); i++) {
                Chunk m = meta.get(i);
                chunks.add(new Chunk(m.docId(), m.title(), m.type(), m.text(), batch.vectors().get(i)));
            }
            Index idx = new Index(batch.mode(), List.copyOf(chunks), System.currentTimeMillis());
            indexes.put(ws, idx);
            return idx;
        }
    }

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
}

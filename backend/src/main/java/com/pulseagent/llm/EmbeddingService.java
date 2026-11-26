package com.pulseagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text embeddings for RAG. Uses Gemini embeddings when a key is configured and working,
 * otherwise a local hashed bag-of-words model so search still works offline and for free.
 * The mode is fixed once chosen, because vectors from the two models are not comparable.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int LOCAL_DIMS = 1024;

    private final GeminiClient gemini;
    private volatile String mode;

    public EmbeddingService(GeminiClient gemini) {
        this.gemini = gemini;
    }

    public synchronized String mode() {
        if (mode == null) {
            mode = "local";
            if (gemini.available()) {
                try {
                    gemini.embed(List.of("connectivity check"), "RETRIEVAL_QUERY");
                    mode = "gemini";
                } catch (Exception e) {
                    log.warn("Gemini embeddings unavailable ({}); using local embeddings.", e.getMessage());
                }
            }
            log.info("Embedding mode: {}", mode);
        }
        return mode;
    }

    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if ("gemini".equals(mode())) {
            try {
                return normalizeAll(gemini.embed(texts, "RETRIEVAL_DOCUMENT"));
            } catch (Exception e) {
                throw new LlmException("Embedding failed: " + e.getMessage());
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(local(t));
        return out;
    }

    public float[] embedQuery(String text) {
        if ("gemini".equals(mode())) {
            return normalize(gemini.embed(List.of(text), "RETRIEVAL_QUERY").get(0));
        }
        return local(text);
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    /** Hashing-trick embedding over word unigrams and bigrams, log-scaled and L2-normalised. */
    static float[] local(String text) {
        float[] v = new float[LOCAL_DIMS];
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        String prev = null;
        for (String t : tokens) {
            if (t.length() < 2) continue;
            add(v, t, 1f);
            if (prev != null) add(v, prev + "_" + t, 0.6f);
            prev = t;
        }
        for (int i = 0; i < v.length; i++) v[i] = (float) Math.signum(v[i]) * (float) Math.log1p(Math.abs(v[i]));
        return normalize(v);
    }

    private static void add(float[] v, String token, float w) {
        int h = token.hashCode();
        int idx = Math.floorMod(h, LOCAL_DIMS);
        v[idx] += ((h >>> 16) & 1) == 0 ? w : -w;
    }

    private static List<float[]> normalizeAll(List<float[]> list) {
        list.forEach(EmbeddingService::normalize);
        return list;
    }

    private static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / n);
        return v;
    }
}

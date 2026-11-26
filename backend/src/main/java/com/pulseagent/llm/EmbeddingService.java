package com.pulseagent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Text embeddings for RAG: Gemini embeddings when the workspace uses Gemini, otherwise a
 * local hashed bag-of-words model so search works offline and for free. A whole index always
 * uses one mode, because vectors from the two models are not comparable.
 */
public final class EmbeddingService {

    private static final int LOCAL_DIMS = 1024;

    public record Batch(String mode, List<float[]> vectors) {}

    private EmbeddingService() {}

    public static Batch embedDocuments(Optional<GeminiClient> gemini, List<String> texts) {
        if (gemini.isPresent() && !texts.isEmpty()) {
            try {
                return new Batch("gemini", normalizeAll(gemini.get().embed(texts, "RETRIEVAL_DOCUMENT")));
            } catch (Exception e) {
                // fall back to local below
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(local(t));
        return new Batch("local", out);
    }

    public static float[] embedQuery(Optional<GeminiClient> gemini, String mode, String text) {
        if ("gemini".equals(mode) && gemini.isPresent()) return normalize(gemini.get().embed(List.of(text), "RETRIEVAL_QUERY").get(0));
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
        v[Math.floorMod(h, LOCAL_DIMS)] += ((h >>> 16) & 1) == 0 ? w : -w;
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

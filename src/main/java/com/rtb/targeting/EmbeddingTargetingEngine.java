package com.rtb.targeting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Matches campaigns to conversation context using embedding cosine similarity.
 *
 * For AI-chat ad networks, the signal is the conversation — free-form text,
 * not predefined segments. This engine computes a context embedding from the
 * request's context_text and matches against pre-computed campaign embeddings.
 *
 * Campaign embeddings are generated offline (Python + sentence-transformers).
 * Context embeddings use word-average from a pre-computed vocabulary.
 * Production systems use ONNX Runtime for real-time sentence encoding.
 */
public final class EmbeddingTargetingEngine implements TargetingEngine {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingTargetingEngine.class);

    private final Map<String, float[]> campaignEmbeddings;
    private final Map<String, float[]> wordEmbeddings;
    private final double similarityThreshold;
    private final int embeddingDim;

    public EmbeddingTargetingEngine(String campaignEmbeddingsPath, String wordEmbeddingsPath,
                                    double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
        this.campaignEmbeddings = loadEmbeddings(campaignEmbeddingsPath);
        this.wordEmbeddings = loadEmbeddings(wordEmbeddingsPath);
        this.embeddingDim = campaignEmbeddings.isEmpty() ? 384 :
                campaignEmbeddings.values().iterator().next().length;
        logger.info("Loaded {} campaign embeddings, {} word embeddings (dim={}), threshold={}",
                campaignEmbeddings.size(), wordEmbeddings.size(), embeddingDim, similarityThreshold);
    }

    @Override
    public List<AdCandidate> match(List<Campaign> campaigns, UserProfile user, AdContext context) {
        String contextText = context.contextText();
        if (contextText == null || contextText.isBlank()) {
            return List.of();
        }

        float[] contextEmbedding = computeContextEmbedding(contextText);
        if (contextEmbedding == null) {
            return List.of();
        }

        List<AdCandidate> candidates = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            float[] campaignEmb = campaignEmbeddings.get(campaign.id());
            if (campaignEmb == null) continue;

            double similarity = cosineSimilarity(contextEmbedding, campaignEmb);
            if (similarity >= similarityThreshold) {
                AdCandidate candidate = new AdCandidate(campaign);
                candidate.setScore(similarity);
                candidates.add(candidate);
            }
        }

        logger.debug("Embedding targeting: contextText='{}', matches={}", contextText, candidates.size());
        return candidates;
    }

    /** Average word embeddings from context_text to create a pseudo-sentence embedding. */
    private float[] computeContextEmbedding(String contextText) {
        // TODO: replace with ONNX-based sentence-transformers for production-grade embeddings
        String[] words = contextText.toLowerCase().split("\\W+");
        float[] sum = new float[embeddingDim];
        int count = 0;

        for (String word : words) {
            float[] wordEmb = wordEmbeddings.get(word);
            if (wordEmb != null) {
                for (int i = 0; i < embeddingDim; i++) {
                    sum[i] += wordEmb[i];
                }
                count++;
            }
        }

        if (count == 0) return null;

        // Normalize to unit vector
        float norm = 0;
        for (int i = 0; i < embeddingDim; i++) {
            sum[i] /= count;
            norm += sum[i] * sum[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0) return null;
        for (int i = 0; i < embeddingDim; i++) {
            sum[i] /= norm;
        }
        return sum;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    @SuppressWarnings("unchecked")
    private Map<String, float[]> loadEmbeddings(String path) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<Double>> raw;

            Path filePath = Path.of(path);
            if (Files.exists(filePath)) {
                raw = mapper.readValue(filePath.toFile(), new TypeReference<>() {});
            } else {
                var is = getClass().getClassLoader().getResourceAsStream(path);
                if (is == null) throw new IllegalArgumentException("Embeddings not found: " + path);
                try (is) {
                    raw = mapper.readValue(is, new TypeReference<>() {});
                }
            }

            Map<String, float[]> result = new java.util.HashMap<>();
            for (var entry : raw.entrySet()) {
                List<Double> values = entry.getValue();
                float[] arr = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    arr[i] = values.get(i).floatValue();
                }
                result.put(entry.getKey(), arr);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load embeddings: " + path, e);
        }
    }
}

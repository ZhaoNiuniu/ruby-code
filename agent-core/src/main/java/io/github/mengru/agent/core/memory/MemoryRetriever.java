package io.github.mengru.agent.core.memory;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.ConversationMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MemoryRetriever {

    public static final int DEFAULT_MAX_RESULTS = 3;

    private final MemoryCatalog catalog;
    private final int maxResults;

    public MemoryRetriever(MemoryCatalog catalog) {
        this(catalog, DEFAULT_MAX_RESULTS);
    }

    public MemoryRetriever(MemoryCatalog catalog, int maxResults) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be greater than zero");
        }
        this.maxResults = maxResults;
    }

    public List<MemoryDefinition> retrieve(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (catalog.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = terms(queryText(request));
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        return catalog.memories().stream()
                .map(memory -> new ScoredMemory(memory, score(memory, queryTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredMemory::score).reversed()
                        .thenComparing(scored -> scored.memory().name()))
                .limit(maxResults)
                .map(ScoredMemory::memory)
                .toList();
    }

    private static int score(MemoryDefinition memory, Set<String> queryTerms) {
        Set<String> memoryTerms = terms(memory.name() + " " + memory.description() + " " + memory.type().value());
        int score = 0;
        for (String term : queryTerms) {
            if (memoryTerms.contains(term)) {
                score += 3;
            } else if (memory.content().toLowerCase(Locale.ROOT).contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private static String queryText(AgentRequest request) {
        StringBuilder builder = new StringBuilder(request.task());
        int start = Math.max(0, request.conversationHistory().size() - 6);
        for (ConversationMessage message : request.conversationHistory().subList(start, request.conversationHistory().size())) {
            builder.append(' ').append(message.content());
        }
        return builder.toString();
    }

    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_-]+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private record ScoredMemory(MemoryDefinition memory, int score) {
    }
}

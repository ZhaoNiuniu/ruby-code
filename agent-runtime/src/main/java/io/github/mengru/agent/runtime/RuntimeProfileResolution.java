package io.github.mengru.agent.runtime;

import java.util.List;
import java.util.Objects;

public record RuntimeProfileResolution(RuntimeSettings settings, List<String> sources) {

    public RuntimeProfileResolution {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        sources = List.copyOf(sources);
    }
}

package io.github.mengru.agent.core.trace;

import io.github.mengru.agent.api.TraceEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TraceFormatter {

    private TraceFormatter() {
    }

    public static String format(TraceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        StringBuilder builder = new StringBuilder("[trace] ")
                .append(event.type().name().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, String> entry : event.attributes().entrySet()) {
            builder.append(' ')
                    .append(entry.getKey())
                    .append('=')
                    .append(formatValue(entry.getValue()));
        }
        return builder.toString();
    }

    private static String formatValue(String value) {
        String normalized = value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        if (normalized.matches("[A-Za-z0-9_./:@=-]+")) {
            return normalized;
        }
        return "\"" + normalized.replace("\"", "\\\"") + "\"";
    }
}

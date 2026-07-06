package io.github.mengru.agent.mcp;

final class McpOutputSupport {

    private static final int OUTPUT_LIMIT = 20_000;
    private static final int TRUNCATED_HEAD = 10_000;
    private static final int TRUNCATED_TAIL = 8_000;

    private McpOutputSupport() {
    }

    static String truncate(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= OUTPUT_LIMIT) {
            return text;
        }
        int omitted = text.length() - TRUNCATED_HEAD - TRUNCATED_TAIL;
        return text.substring(0, TRUNCATED_HEAD)
                + "\n\n[tool output truncated: originalLength=" + text.length()
                + ", keptHead=" + TRUNCATED_HEAD
                + ", keptTail=" + TRUNCATED_TAIL
                + ", omitted=" + omitted + "]\n\n"
                + text.substring(text.length() - TRUNCATED_TAIL);
    }
}

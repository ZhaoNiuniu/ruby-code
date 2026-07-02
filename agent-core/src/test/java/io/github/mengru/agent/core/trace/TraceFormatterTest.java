package io.github.mengru.agent.core.trace;

import io.github.mengru.agent.api.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class TraceFormatterTest {

    @Test
    void formatsSingleLineTraceWithEscapedValues() {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("status", "start");
        attributes.put("summary", "hello\n\"world\"");

        String line = TraceFormatter.format(TraceEvent.of(TraceEvent.Type.MODEL_CALL, attributes));

        assertThat(line).isEqualTo("[trace] model_call status=start summary=\"hello\\n\\\"world\\\"\"");
        assertThat(line).doesNotContain("\n");
    }
}

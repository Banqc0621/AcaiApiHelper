package com.hronline.http;

import com.hronline.model.Environment;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreRequestProcessorTest {

    @Test
    void appliesVariableParamAndHeaderOverridesWithoutMutatingBaseEnvironment() {
        Environment base = new Environment("测试环境", "https://example.test");
        base.getVariables().put("token", "base-token");
        base.getVariables().put("traceId", "base-trace");

        String script = "# safe pre-request DSL\n"
                + "set token=script-token\n"
                + "param userId={{token}}\n"
                + "header X-Trace={{traceId}}";
        PreRequestProcessor.Result result = PreRequestProcessor.apply(script,
                Map.of("token", "table-token", "traceId", "trace-42"),
                new LinkedHashMap<>(Map.of("userId", "original")),
                new LinkedHashMap<>(Map.of("Accept", "application/json")), base);

        assertEquals("script-token", result.getEnvironment().getVariables().get("token"));
        assertEquals("script-token", result.getParams().get("userId"));
        assertEquals("trace-42", result.getHeaders().get("X-Trace"));
        assertEquals("application/json", result.getHeaders().get("Accept"));
        assertEquals("base-token", base.getVariables().get("token"), "基础环境不得被接口级覆盖污染");
    }

    @Test
    void rejectsUnknownOrMalformedCommands() {
        assertThrows(IllegalArgumentException.class,
                () -> PreRequestProcessor.apply("exec dangerous()", Map.of(), Map.of(), Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> PreRequestProcessor.apply("header missingEquals", Map.of(), Map.of(), Map.of(), null));
    }
}

package com.hronline.settings;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RestAutoLabSettingsStatePreRequestTest {

    @Test
    void persistsAndClearsPerApiPreRequestConfiguration() {
        RestAutoLabSettingsState settings = new RestAutoLabSettingsState();
        String apiKey = "POST|/users";

        settings.savePreRequestScript(apiKey, "set token=abc");
        settings.saveApiVariableOverrides(apiKey, Map.of("tenant", "demo"));
        assertEquals("set token=abc", settings.loadPreRequestScripts().get(apiKey));
        assertEquals("demo", settings.loadApiVariableOverrides().get(apiKey).get("tenant"));

        settings.savePreRequestScript(apiKey, "");
        settings.saveApiVariableOverrides(apiKey, Map.of());
        assertFalse(settings.loadPreRequestScripts().containsKey(apiKey));
        assertFalse(settings.loadApiVariableOverrides().containsKey(apiKey));
    }
}

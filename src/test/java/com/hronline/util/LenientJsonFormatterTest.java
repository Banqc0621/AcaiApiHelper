package com.hronline.util;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LenientJsonFormatterTest {

    @Test
    void formatsMissingCommasBetweenObjectFields() {
        String input = "{\n"
                + "\"collectionId\":10065\n"
                + "\"recipientUserId\":\"100\"\n"
                + "\"quantity\":1\n"
                + "\"bizType\":\"AIR_DROP\"\n"
                + "}";
        String formatted = LenientJsonFormatter.format(input);
        assertEquals(10065, JsonParser.parseString(formatted).getAsJsonObject()
                .get("collectionId").getAsInt());
        assertEquals("100", JsonParser.parseString(formatted).getAsJsonObject()
                .get("recipientUserId").getAsString());
        assertTrue(formatted.contains("\"quantity\": 1"));
    }

    @Test
    void formatsCommonJson5Input() {
        String formatted = LenientJsonFormatter.format("{name:'demo', enabled:true, items:[1,2,],}");
        assertTrue(formatted.contains("\"name\": \"demo\""));
        assertTrue(formatted.contains("\"enabled\": true"));
        assertTrue(formatted.contains("\n    2"));
    }

    @Test
    void formatsUnquotedKeysWithMissingCommas() {
        String formatted = LenientJsonFormatter.format("{collectionId:10065 recipientUserId:'100'}");
        assertEquals(10065, JsonParser.parseString(formatted).getAsJsonObject()
                .get("collectionId").getAsInt());
        assertEquals("100", JsonParser.parseString(formatted).getAsJsonObject()
                .get("recipientUserId").getAsString());
    }
}

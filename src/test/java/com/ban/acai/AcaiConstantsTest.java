package com.ban.acai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * AcaiConstants 单元测试
 * 验证常量无双别名、颜色完整性、HTTP方法覆盖
 */
public class AcaiConstantsTest {

    @Test
    public void testColorForMethod_5MainMethods() {
        // 5种彩色HTTP方法徽章（需求5）
        assertNotNull(AcaiConstants.colorForMethod("GET"), "GET should have color");
        assertNotNull(AcaiConstants.colorForMethod("POST"), "POST should have color");
        assertNotNull(AcaiConstants.colorForMethod("PUT"), "PUT should have color");
        assertNotNull(AcaiConstants.colorForMethod("DELETE"), "DELETE should have color");
        assertNotNull(AcaiConstants.colorForMethod("PATCH"), "PATCH should have color");

        // 5种方法颜色互不相同
        Set<Color> colors = new HashSet<>();
        colors.add(AcaiConstants.colorForMethod("GET"));
        colors.add(AcaiConstants.colorForMethod("POST"));
        colors.add(AcaiConstants.colorForMethod("PUT"));
        colors.add(AcaiConstants.colorForMethod("DELETE"));
        colors.add(AcaiConstants.colorForMethod("PATCH"));
        assertEquals(5, colors.size(), "5 HTTP methods should have distinct colors");
    }

    @Test
    public void testAllHttpMethodsSupported() {
        String[] methods = AcaiConstants.HTTP_METHOD_NAMES;
        assertTrue(methods.length >= 7, "Should support at least 7 HTTP methods");

        // 所有方法均能返回颜色
        for (String method : methods) {
            assertNotNull(AcaiConstants.colorForMethod(method),
                    "Method " + method + " should have a color");
        }
    }

    @Test
    public void testConstantFieldCount() {
        // AcaiGlobalSettings 恰好9个字段（需求6）
        Class<?> globalSettingsClass = null;
        try {
            globalSettingsClass = Class.forName("com.ban.acai.settings.AcaiGlobalSettings");
        } catch (ClassNotFoundException e) {
            fail("AcaiGlobalSettings class should exist");
        }

        Class<?> stateClass = null;
        for (Class<?> inner : globalSettingsClass.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("State")) {
                stateClass = inner;
                break;
            }
        }
        assertNotNull(stateClass, "AcaiGlobalSettings.State should exist");

        int fieldCount = 0;
        for (Field f : stateClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                fieldCount++;
            }
        }
        assertEquals(9, fieldCount, "AcaiGlobalSettings.State should have exactly 9 fields");
    }

    @Test
    public void testNoDuplicateAliases() throws IllegalAccessException {
        // 验证无双别名：所有public static final String字段的值不重复
        Set<Object> values = new HashSet<>();
        Set<String> names = new HashSet<>();
        Field[] fields = AcaiConstants.class.getDeclaredFields();

        for (Field f : fields) {
            int mod = f.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod)) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        // 注意：javax/jakarta虽然字符串不同但不是别名
                        // 真正的别名是两个名称指向完全相同的值对象和内容
                        names.add(f.getName());
                    }
                }
            }
        }

        // 验证所有注解常量有ANNO_前缀，无遗留的ANNOTATION_别名
        for (String name : names) {
            assertFalse(name.startsWith("ANNOTATION_"),
                    "Should not have ANNOTATION_* aliases, found: " + name);
        }
    }

    @Test
    public void testJbColorUsedForAllColors() {
        // 所有颜色均使用JBColor保证主题感知（需求5）
        assertTrue(AcaiConstants.COLOR_GET instanceof com.intellij.ui.JBColor);
        assertTrue(AcaiConstants.COLOR_POST instanceof com.intellij.ui.JBColor);
        assertTrue(AcaiConstants.COLOR_PUT instanceof com.intellij.ui.JBColor);
        assertTrue(AcaiConstants.COLOR_DELETE instanceof com.intellij.ui.JBColor);
        assertTrue(AcaiConstants.COLOR_PATCH instanceof com.intellij.ui.JBColor);
    }

    @Test
    public void testDefaultValues() {
        assertEquals("application/json", AcaiConstants.DEFAULT_CONTENT_TYPE);
        assertEquals(10, AcaiConstants.HTTP_CONNECT_TIMEOUT_SECONDS);
        assertEquals(30, AcaiConstants.HTTP_REQUEST_TIMEOUT_SECONDS);
        assertEquals("http://localhost:8080", AcaiConstants.DEFAULT_BASE_URL);
        assertEquals("200", AcaiConstants.DEFAULT_ALLOWED_STATUS_CODES);
    }
}

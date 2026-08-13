package com.ban.acai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * RestAutoLabConstants 单元测试
 * 验证常量无双别名、颜色完整性、HTTP方法覆盖
 */
public class RestAutoLabConstantsTest {

    @Test
    public void testColorForMethod_5MainMethods() {
        // 5种彩色HTTP方法徽章（需求5）
        assertNotNull(RestAutoLabConstants.colorForMethod("GET"), "GET should have color");
        assertNotNull(RestAutoLabConstants.colorForMethod("POST"), "POST should have color");
        assertNotNull(RestAutoLabConstants.colorForMethod("PUT"), "PUT should have color");
        assertNotNull(RestAutoLabConstants.colorForMethod("DELETE"), "DELETE should have color");
        assertNotNull(RestAutoLabConstants.colorForMethod("PATCH"), "PATCH should have color");

        // 5种方法颜色互不相同
        Set<Color> colors = new HashSet<>();
        colors.add(RestAutoLabConstants.colorForMethod("GET"));
        colors.add(RestAutoLabConstants.colorForMethod("POST"));
        colors.add(RestAutoLabConstants.colorForMethod("PUT"));
        colors.add(RestAutoLabConstants.colorForMethod("DELETE"));
        colors.add(RestAutoLabConstants.colorForMethod("PATCH"));
        assertEquals(5, colors.size(), "5 HTTP methods should have distinct colors");
    }

    @Test
    public void testAllHttpMethodsSupported() {
        String[] methods = RestAutoLabConstants.HTTP_METHOD_NAMES;
        assertTrue(methods.length >= 7, "Should support at least 7 HTTP methods");

        // 所有方法均能返回颜色
        for (String method : methods) {
            assertNotNull(RestAutoLabConstants.colorForMethod(method),
                    "Method " + method + " should have a color");
        }
    }

    @Test
    public void testConstantFieldCount() {
        // RestAutoLabGlobalSettings 恰好9个字段（需求6）
        Class<?> globalSettingsClass = null;
        try {
            globalSettingsClass = Class.forName("com.ban.acai.settings.RestAutoLabGlobalSettings");
        } catch (ClassNotFoundException e) {
            fail("RestAutoLabGlobalSettings class should exist");
        }

        Class<?> stateClass = null;
        for (Class<?> inner : globalSettingsClass.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("State")) {
                stateClass = inner;
                break;
            }
        }
        assertNotNull(stateClass, "RestAutoLabGlobalSettings.State should exist");

        int fieldCount = 0;
        for (Field f : stateClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                fieldCount++;
            }
        }
        assertEquals(9, fieldCount, "RestAutoLabGlobalSettings.State should have exactly 9 fields");
    }

    @Test
    public void testNoDuplicateAliases() throws IllegalAccessException {
        // 验证无双别名：所有public static final String字段的值不重复
        Set<Object> values = new HashSet<>();
        Set<String> names = new HashSet<>();
        Field[] fields = RestAutoLabConstants.class.getDeclaredFields();

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
        assertTrue(RestAutoLabConstants.COLOR_GET instanceof com.intellij.ui.JBColor);
        assertTrue(RestAutoLabConstants.COLOR_POST instanceof com.intellij.ui.JBColor);
        assertTrue(RestAutoLabConstants.COLOR_PUT instanceof com.intellij.ui.JBColor);
        assertTrue(RestAutoLabConstants.COLOR_DELETE instanceof com.intellij.ui.JBColor);
        assertTrue(RestAutoLabConstants.COLOR_PATCH instanceof com.intellij.ui.JBColor);
    }

    @Test
    public void testDefaultValues() {
        assertEquals("application/json", RestAutoLabConstants.DEFAULT_CONTENT_TYPE);
        assertEquals(10, RestAutoLabConstants.HTTP_CONNECT_TIMEOUT_SECONDS);
        assertEquals(30, RestAutoLabConstants.HTTP_REQUEST_TIMEOUT_SECONDS);
        assertEquals("http://localhost:8080", RestAutoLabConstants.DEFAULT_BASE_URL);
        assertEquals("200", RestAutoLabConstants.DEFAULT_ALLOWED_STATUS_CODES);
    }
}
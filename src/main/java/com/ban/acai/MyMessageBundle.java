package com.ban.acai;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.util.function.Supplier;

/**
 * 国际化消息包管理器 - 加载 messages/MyMessageBundle.properties 中的国际化文案
 */
public final class MyMessageBundle {

    private static final String BUNDLE = "messages.MyMessageBundle";
    private static final DynamicBundle INSTANCE = new DynamicBundle(MyMessageBundle.class, BUNDLE);

    private MyMessageBundle() {}

    @NotNull
    public static @Nls String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, @NotNull Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }

    @NotNull
    public static Supplier<@Nls String> lazyMessage(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, @NotNull Object @NotNull ... params) {
        return INSTANCE.getLazyMessage(key, params);
    }
}

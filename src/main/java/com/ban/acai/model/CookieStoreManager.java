package com.ban.acai.model;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Cookie管理器
 */
public class CookieStoreManager {
    private final CookieManager cookieManager = new CookieManager();

    public CookieStoreManager() {
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
    }

    public void saveCookiesFromResponse(URI uri, Map<String, String> headers) {
        try {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Set-Cookie") || e.getKey().equalsIgnoreCase("Set-Cookie2")) {
                    List<HttpCookie> cookies = HttpCookie.parse(e.getValue());
                    for (HttpCookie c : cookies) {
                        cookieManager.getCookieStore().add(uri, c);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public String getCookieHeader(URI uri) {
        try {
            Map<String, List<String>> headers = cookieManager.get(uri, Map.of("Cookie", List.of("")));
            List<String> cookies = headers.get("Cookie");
            if (cookies != null && !cookies.isEmpty()) return String.join("; ", cookies);
            // Alternative: directly from store
            List<HttpCookie> list = cookieManager.getCookieStore().get(uri);
            if (list.isEmpty()) list = cookieManager.getCookieStore().getCookies();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(list.get(i).getName()).append("=").append(list.get(i).getValue());
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public List<HttpCookie> getAllCookies() { return cookieManager.getCookieStore().getCookies(); }
    public void clearAll() { cookieManager.getCookieStore().removeAll(); }
    public int size() { return cookieManager.getCookieStore().getCookies().size(); }
}

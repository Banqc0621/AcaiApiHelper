package com.hronline.chain;

import com.hronline.http.HttpExecutorService;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import com.hronline.model.TestProfile;
import com.hronline.model.TestResult;
import com.hronline.model.TestStatus;
import com.hronline.settings.RestAutoLabSettingsState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现用户报告的回归：依赖设置里配置了「上游响应字段 → 下游入参」映射，
 * 批量测试时历史记录里下游仍使用原值。
 * <p>全链路走真实生产代码：{@link RestAutoLabSettingsState} 依赖/参数/请求体
 * 的 Gson 持久化往返（与 依赖设置保存 + 批量测试加载 完全相同的路径）
 * → {@link ChainTestExecutor} → {@link HttpExecutorService} → 内嵌 HTTP 服务器。</p>
 */
class ChainDependencyRoundTripTest {

    /**
     * 场景一：POST 下游 + 收藏夹里保存的请求体（folderApiBodiesJson）。
     * 上游 GET /login 返回 {"data":{"id":"42","token":"abc"}}，
     * 依赖设置 data.id→userId、data.token→token，
     * 批量测试后下游请求体必须变成 userId=42、token=abc。
     */
    @Test
    void postDownstreamWithSavedBodyGetsInjectedValues() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> downstreamBodies = new CopyOnWriteArrayList<>();
        server.createContext("/login", exchange -> {
            byte[] body = "{\"data\":{\"id\":\"42\",\"token\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/orders", exchange -> {
            downstreamBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition login = api("GET", "/login");
            ApiDefinition createOrder = api("POST", "/orders");
            createOrder.setParameters(new ArrayList<>(List.of(
                    parameter("userId", ParameterLocation.BODY),
                    parameter("token", ParameterLocation.BODY))));

            // === 1. 用户在依赖设置里保存（与 DependencyGraphDialog 最终保存一致）===
            ApiDependency dependency = new ApiDependency(login.uniqueKey(), createOrder.uniqueKey(), "MANUAL");
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "userId"));
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.token", "token"));
            RestAutoLabSettingsState state = new RestAutoLabSettingsState();
            Map<String, List<ApiDependency>> depMap = new LinkedHashMap<>();
            depMap.put("folder-1", new ArrayList<>(List.of(dependency)));
            state.saveStarredFolderDependencies(depMap);

            // === 2. 用户保存的下游请求体（多行 pretty JSON，还原用户真实编辑格式）===
            Map<String, String> bodies = new LinkedHashMap<>();
            bodies.put("folder-1\n" + createOrder.uniqueKey(),
                    "{\n  \"userId\": 0,\n  \"token\": \"old\"\n}");
            state.saveFolderApiBodies(bodies);
            Map<String, Map<String, String>> paramsMap = new LinkedHashMap<>();
            paramsMap.put("folder-1\n" + createOrder.uniqueKey(),
                    new LinkedHashMap<>(Map.of("userId", "0", "token", "old")));
            state.saveFolderApiParams(paramsMap);

            // === 3. 批量测试加载（与 executeStarredChainBatch 完全一致的加载路径）===
            List<ApiDependency> deps = state.loadStarredFolderDependencies().get("folder-1");
            assertNotNull(deps, "依赖设置持久化后必须能加载回来");
            assertEquals(1, deps.size());
            assertEquals(2, deps.get(0).getMappings().size(), "持久化往返不能丢映射");

            TestProfile profile = new TestProfile("批量测试", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            Map<String, String> orderParams =
                    state.loadFolderApiParams().get("folder-1\n" + createOrder.uniqueKey());
            profile.setParams(createOrder.uniqueKey(), orderParams);
            profile.setRequestBody(createOrder.uniqueKey(),
                    state.loadFolderApiBodies().get("folder-1\n" + createOrder.uniqueKey()));

            List<TestResult> history = new CopyOnWriteArrayList<>();
            HttpExecutorService http = new HttpExecutorService(null);
            http.setHistoryListener(history::add);

            new ChainTestExecutor(http).execute(
                    List.of(login, createOrder), deps, profile, null,
                    (result, current, total) -> { });

            assertEquals(2, history.size());
            String sent = downstreamBodies.get(0);
            assertTrue(sent.contains("\"userId\":\"42\"") || sent.contains("\"userId\":42"),
                    "下游请求体必须注入上游 id=42，实际发送: " + sent);
            assertTrue(sent.contains("\"token\":\"abc\""),
                    "下游请求体必须注入上游 token=abc，实际发送: " + sent);
            // 历史记录里记录的请求体也必须是注入后的
            String recordedBody = history.get(1).getRequestBody();
            assertTrue(recordedBody != null && recordedBody.contains("42"),
                    "历史记录的请求体应包含注入值，实际: " + recordedBody);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 场景二：GET 下游（路径/查询参数）。上游 id 注入 PATH、token 注入 QUERY。
     */
    @Test
    void getDownstreamQueryParamsGetInjectedValues() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/login", exchange -> {
            byte[] body = "{\"data\":{\"id\":\"42\",\"token\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boxes/42", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition login = api("GET", "/login");
            ApiDefinition boxes = api("GET", "/boxes/{id}");
            boxes.setParameters(new ArrayList<>(List.of(
                    parameter("id", ParameterLocation.PATH),
                    parameter("token", ParameterLocation.QUERY))));

            ApiDependency dependency = new ApiDependency(login.uniqueKey(), boxes.uniqueKey(), "MANUAL");
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "id"));
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.token", "token"));

            RestAutoLabSettingsState state = new RestAutoLabSettingsState();
            Map<String, List<ApiDependency>> depMap = new LinkedHashMap<>();
            depMap.put("folder-1", new ArrayList<>(List.of(dependency)));
            state.saveStarredFolderDependencies(depMap);

            List<ApiDependency> deps = state.loadStarredFolderDependencies().get("folder-1");
            assertNotNull(deps);

            TestProfile profile = new TestProfile("批量测试", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            profile.setParams(boxes.uniqueKey(),
                    new LinkedHashMap<>(Map.of("id", "unresolved", "token", "unresolved")));

            new ChainTestExecutor(new HttpExecutorService(null)).execute(
                    List.of(login, boxes), deps, profile, null,
                    (result, current, total) -> { });

            assertEquals(List.of("/boxes/42?token=abc"), requests,
                    "GET 下游的路径/查询参数必须使用上游响应值");
        } finally {
            server.stop(0);
        }
    }

    private static ApiDefinition api(String method, String url) {
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(method);
        api.setUrl(url);
        return api;
    }

    private static ApiParameter parameter(String name, ParameterLocation location) {
        ApiParameter parameter = new ApiParameter();
        parameter.setName(name);
        parameter.setLocation(location);
        return parameter;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
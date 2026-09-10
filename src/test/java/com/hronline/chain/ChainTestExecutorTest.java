package com.hronline.chain;

import com.hronline.http.HttpExecutorService;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import com.hronline.model.TestProfile;
import com.hronline.model.TestResult;
import com.hronline.model.TestReport;
import com.hronline.model.TestStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainTestExecutorTest {

    @Test
    void executesInFavoriteOrderAndInjectsMultipleMappings() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/login", exchange -> {
            requests.add("login");
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

            ApiDependency dependency = new ApiDependency(login.uniqueKey(), boxes.uniqueKey());
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "id"));
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.token", "token"));

            TestProfile profile = new TestProfile("收藏夹", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            profile.setParams(boxes.uniqueKey(), Map.of("id", "unresolved", "token", "unresolved"));
            List<TestStatus> statuses = new ArrayList<>();
            List<TestResult> history = new CopyOnWriteArrayList<>();
            HttpExecutorService http = new HttpExecutorService(null);
            http.setHistoryListener(history::add);

            // 收藏夹顺序即执行顺序（不做拓扑排序）：login 在上、boxes 在下，
            // login 的响应字段先被提取，随后注入 boxes 的参数。
            TestReport report = new ChainTestExecutor(http).execute(
                    List.of(login, boxes), List.of(dependency), profile, null,
                    (result, current, total) -> statuses.add(result.getStatus()));

            assertEquals(List.of("login", "/boxes/42?token=abc"), requests);
            assertEquals(List.of(TestStatus.PASSED, TestStatus.PASSED), statuses);
            assertEquals(2, report.getResults().size());
            assertEquals(2, history.size(), "每个实际请求都必须写入一条历史");
            assertTrue(history.get(1).getRequestUrl().endsWith("/boxes/42?token=abc"));
            assertEquals("/boxes/42?token=abc", pathOnly(report.getResults().get(1).getRequestUrl()));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 一伦优化 #93：上游失败不跳过下游，收藏接口列表里每个接口都按顺序请求。
     * <p>这里 login 响应是 500（业务失败），boxes 用 profile 里的原占位参数 id=42 照样发出请求，
     * 没有 SKIPPED 状态。</p>
     */
    @Test
    void upstreamFailureDoesNotBlockDownstream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger downstreamHits = new AtomicInteger();
        server.createContext("/login", exchange -> {
            byte[] body = "{\"error\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boxes/42", exchange -> {
            downstreamHits.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition login = api("GET", "/login");
            ApiDefinition boxes = api("GET", "/boxes/{id}");
            boxes.setParameters(new ArrayList<>(List.of(parameter("id", ParameterLocation.PATH))));
            ApiDependency dependency = new ApiDependency(login.uniqueKey(), boxes.uniqueKey());
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "id"));

            TestProfile profile = new TestProfile("收藏夹", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            profile.setParams(boxes.uniqueKey(), Map.of("id", "42"));
            List<TestResult> results = new ArrayList<>();

            new ChainTestExecutor(new HttpExecutorService(null)).execute(
                    List.of(login, boxes), List.of(dependency), profile, null,
                    (result, current, total) -> results.add(result));

            assertEquals(1, downstreamHits.get(), "上游失败不影响下游请求");
            assertEquals(TestStatus.FAILED, results.get(0).getStatus());
            assertEquals(TestStatus.PASSED, results.get(1).getStatus());
            assertTrue(pathOnly(results.get(1).getRequestUrl()).endsWith("/boxes/42"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 一伦优化 #93：上游成功但响应里没有声明的字段时，下游继续请求，使用 profile 原占位参数。
     * 旧版会跳过下游，新版跟用户预期一致 —— 每一行接口都请求一遍。
     */
    @Test
    void upstreamSuccessWithMissingMappedFieldStillRequestsDownstream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger downstreamHits = new AtomicInteger();
        server.createContext("/login", exchange -> {
            byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boxes/fallback", exchange -> {
            downstreamHits.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition login = api("GET", "/login");
            ApiDefinition boxes = api("GET", "/boxes/{id}");
            boxes.setParameters(new ArrayList<>(List.of(parameter("id", ParameterLocation.PATH))));
            ApiDependency dependency = new ApiDependency(login.uniqueKey(), boxes.uniqueKey());
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "id"));

            TestProfile profile = new TestProfile("收藏夹", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            profile.setParams(boxes.uniqueKey(), Map.of("id", "fallback"));
            List<TestResult> results = new ArrayList<>();

            new ChainTestExecutor(new HttpExecutorService(null)).execute(
                    List.of(login, boxes), List.of(dependency), profile, null,
                    (result, current, total) -> results.add(result));

            assertEquals(1, downstreamHits.get(), "上游字段缺失仍要请求下游");
            assertEquals(TestStatus.PASSED, results.get(0).getStatus());
            assertEquals(TestStatus.PASSED, results.get(1).getStatus());
            assertTrue(pathOnly(results.get(1).getRequestUrl()).endsWith("/boxes/fallback"),
                    "未拿到依赖值时使用 profile 原占位参数");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 上游被异常规则/预期状态码判为 FAILED 时，响应体里的依赖值仍要提取注入，
     * 不能因为判定失败就丢掉（否则用户改异常规则后依赖注入会"突然失效"）。
     */
    @Test
    void extractsDependencyValuesEvenWhenUpstreamIsMarkedFailed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> downstreamUrls = new CopyOnWriteArrayList<>();
        server.createContext("/login", exchange -> {
            byte[] body = "{\"data\":{\"id\":\"77\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            downstreamUrls.add(exchange.getRequestURI().toString());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition login = api("GET", "/login");
            login.setExpectedStatusCodes(java.util.Set.of(200)); // 500 会被判为 FAILED
            ApiDefinition boxes = api("GET", "/boxes/{id}");
            boxes.setParameters(new ArrayList<>(List.of(parameter("id", ParameterLocation.PATH))));

            ApiDependency dependency = new ApiDependency(login.uniqueKey(), boxes.uniqueKey());
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "id"));

            TestProfile profile = new TestProfile("收藏夹", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            profile.setParams(boxes.uniqueKey(), Map.of("id", "old"));
            List<TestResult> results = new ArrayList<>();

            new ChainTestExecutor(new HttpExecutorService(null)).execute(
                    List.of(login, boxes), List.of(dependency), profile, null,
                    (result, current, total) -> results.add(result));

            assertEquals(TestStatus.FAILED, results.get(0).getStatus());
            assertEquals(TestStatus.PASSED, results.get(1).getStatus());
            assertEquals("/boxes/77", pathOnly(results.get(1).getRequestUrl()),
                    "上游判失败不影响依赖值提取注入");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 问题 1 回归：非 GET 接口的依赖值必须合并进「已保存的请求体」，而不是只写进
     * paramValues。否则下游历史记录里看到的入参永远不是上游响应的真实内容。
     * <p>上游 /login 返回 {@code data.id=42}，下游 POST /submit 保存的请求体是
     * {@code {"id":"old","name":"x"}}，注入后实际发出的请求体必须变成
     * {@code {"id":"42","name":"x"}}。</p>
     */
    @Test
    void injectsUpstreamValueIntoSavedPostBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> downstreamBodies = new CopyOnWriteArrayList<>();
        server.createContext("/login", exchange -> {
            byte[] body = "{\"data\":{\"id\":\"42\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/submit", exchange -> {
            downstreamBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition login = api("GET", "/login");
            ApiDefinition submit = api("POST", "/submit");
            submit.setParameters(new ArrayList<>(List.of(parameter("id", ParameterLocation.BODY))));

            ApiDependency dependency = new ApiDependency(login.uniqueKey(), submit.uniqueKey());
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.id", "id"));

            TestProfile profile = new TestProfile("收藏夹", baseUrl(server));
            profile.setParams(login.uniqueKey(), Map.of());
            profile.setParams(submit.uniqueKey(), Map.of("id", "old"));
            // 保存的请求体是用户编辑时定稿的静态内容
            profile.setRequestBody(submit.uniqueKey(), "{\"id\":\"old\",\"name\":\"x\"}");

            List<TestResult> results = new ArrayList<>();
            new ChainTestExecutor(new HttpExecutorService(null)).execute(
                    List.of(login, submit), List.of(dependency), profile, null,
                    (result, current, total) -> results.add(result));

            assertEquals(TestStatus.PASSED, results.get(0).getStatus());
            assertEquals(TestStatus.PASSED, results.get(1).getStatus());
            assertEquals(1, downstreamBodies.size());
            String sentBody = downstreamBodies.get(0);
            assertTrue(sentBody.contains("\"id\":\"42\""),
                    "注入的上游值必须覆盖请求体中的同名字段，实际: " + sentBody);
            assertTrue(sentBody.contains("\"name\":\"x\""),
                    "未注入的字段必须保留原值，实际: " + sentBody);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 回归：目标参数写的是嵌套路径（如 login.username），但已保存的请求体是扁平结构
     * （只有顶层 username）时，注入必须覆盖顶层同名字段，绝不能凭空创建
     * {@code "login":{...}} 包装 —— 那会让下游请求多出用户没配置的新增字段。
     */
    @Test
    void doesNotCreateNestedWrapperWhenBodyIsFlat() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> downstreamBodies = new CopyOnWriteArrayList<>();
        server.createContext("/verify", exchange -> {
            byte[] body = "{\"data\":{\"username\":\"滑块验证数据不能为空\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/auth/companyAdminLogin", exchange -> {
            downstreamBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiDefinition verify = api("GET", "/verify");
            ApiDefinition login = api("POST", "/auth/companyAdminLogin");
            login.setParameters(new ArrayList<>(List.of(parameter("username", ParameterLocation.BODY))));

            ApiDependency dependency = new ApiDependency(verify.uniqueKey(), login.uniqueKey());
            // 目标参数是嵌套写法，但下游请求体实际是扁平的
            dependency.getMappings().add(new ApiDependency.ValueMapping("data.username", "login.username"));

            TestProfile profile = new TestProfile("收藏夹", baseUrl(server));
            profile.setParams(verify.uniqueKey(), Map.of());
            profile.setParams(login.uniqueKey(), Map.of("login.username", "old"));
            profile.setRequestBody(login.uniqueKey(),
                    "{\"username\":\"12312\",\"password\":\"123456\",\"loginType\":\"0\",\"verifyCode\":\"1\"}");

            List<TestResult> results = new ArrayList<>();
            new ChainTestExecutor(new HttpExecutorService(null)).execute(
                    List.of(verify, login), List.of(dependency), profile, null,
                    (result, current, total) -> results.add(result));

            assertEquals(TestStatus.PASSED, results.get(0).getStatus());
            assertEquals(TestStatus.PASSED, results.get(1).getStatus());
            assertEquals(1, downstreamBodies.size());
            String sentBody = downstreamBodies.get(0);
            assertTrue(sentBody.contains("\"username\":\"滑块验证数据不能为空\""),
                    "注入值必须覆盖顶层同名字段，实际: " + sentBody);
            assertTrue(!sentBody.contains("\"login\":"),
                    "请求体中不能出现新增的 login 包装字段，实际: " + sentBody);
            assertTrue(sentBody.contains("\"password\":\"123456\""),
                    "未注入的字段必须保留原值，实际: " + sentBody);
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

    private static String pathOnly(String url) {
        int start = url.indexOf('/', url.indexOf("//") + 2);
        return start < 0 ? url : url.substring(start);
    }
}
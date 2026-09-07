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
    void executesSavedDependencyInTopologyOrderAndInjectsMultipleMappings() throws Exception {
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

            TestReport report = new ChainTestExecutor(http).execute(
                    List.of(boxes, login), List.of(dependency), profile, null,
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

    @Test
    void skipsDownstreamAfterUpstreamFailureAndDoesNotSendRequest() throws Exception {
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

            assertEquals(0, downstreamHits.get());
            assertEquals(TestStatus.FAILED, results.get(0).getStatus());
            assertEquals(TestStatus.SKIPPED, results.get(1).getStatus());
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

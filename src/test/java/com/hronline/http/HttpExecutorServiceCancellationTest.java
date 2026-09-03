package com.hronline.http;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import com.hronline.model.TestResult;
import com.hronline.model.TestStatus;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExecutorServiceCancellationTest {

    @Test
    void dependencyNestedParameterOverrideIsWrittenToJsonBody() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nested", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ApiParameter request = new ApiParameter();
            request.setName("request");
            request.setType("RequestDto");
            request.setLocation(ParameterLocation.BODY);

            ApiParameter appId = new ApiParameter();
            appId.setName("appId");
            appId.setType("String");
            appId.setLocation(ParameterLocation.BODY);
            ApiParameter options = new ApiParameter();
            options.setName("options");
            options.setType("Options");
            options.setLocation(ParameterLocation.BODY);
            ApiParameter enabled = new ApiParameter();
            enabled.setName("enabled");
            enabled.setType("Boolean");
            enabled.setLocation(ParameterLocation.BODY);
            options.setChildren(new ArrayList<>(List.of(enabled)));
            request.setChildren(new ArrayList<>(List.of(appId, options)));

            ApiDefinition api = new ApiDefinition();
            api.setHttpMethod("POST");
            api.setUrl("/nested");
            api.setParameters(new ArrayList<>(List.of(request)));

            TestResult result = new HttpExecutorService(null).executeRequest(
                    api, "http://127.0.0.1:" + server.getAddress().getPort(),
                    Map.of("request.appId", "from-upstream", "request.options.enabled", "false"),
                    Map.of(), null, HttpExecutorService.BODY_FORMAT_JSON, null, null);

            assertEquals(TestStatus.PASSED, result.getStatus());
            var body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals("from-upstream", body.get("appId").getAsString());
            assertFalse(body.getAsJsonObject("options").get("enabled").getAsBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptionCancelsBlockingSingleRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "restautolab-test-server");
            thread.setDaemon(true);
            return thread;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/slow", exchange -> {
            received.countDown();
            try {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            ApiDefinition api = new ApiDefinition();
            api.setHttpMethod("GET");
            api.setUrl("/slow");
            AtomicReference<TestResult> result = new AtomicReference<>();
            Thread requestThread = new Thread(() -> result.set(new HttpExecutorService(null)
                    .executeRequest(api, "http://127.0.0.1:" + server.getAddress().getPort(),
                            Map.of(), Map.of(), null, HttpExecutorService.BODY_FORMAT_JSON,
                            null, null)), "restautolab-request-test");

            requestThread.start();
            assertTrue(received.await(2, TimeUnit.SECONDS), "本地测试服务应收到请求");
            requestThread.interrupt();
            requestThread.join(2_000);

            assertFalse(requestThread.isAlive(), "中断后阻塞请求必须及时退出");
            assertEquals(TestStatus.CANCELLED, result.get().getStatus());
            assertEquals("⊘ 已取消", result.get().summary());
        } finally {
            release.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}

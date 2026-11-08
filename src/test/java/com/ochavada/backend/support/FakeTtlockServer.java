package com.ochavada.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Servidor HTTP local que imita la TTLock Open API v3 (OAuth, apertura, listado y detalle). Permite
 * ejercitar el cliente Feign real: codificación de formularios, timeouts y deserialización.
 */
public final class FakeTtlockServer {

    public static final String CLIENT_ID = "test-client-id";
    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String USERNAME = "propietario@test.local";

    private static final FakeTtlockServer INSTANCE = new FakeTtlockServer();

    private final HttpServer server;
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final AtomicInteger passwordGrants = new AtomicInteger();
    private final AtomicInteger refreshGrants = new AtomicInteger();
    private final Map<Long, Integer> unlockErrors = new ConcurrentHashMap<>();
    private final Map<Long, LockInfo> accountLocks = new ConcurrentHashMap<>();
    private final List<Map<String, String>> unlockRequests = new CopyOnWriteArrayList<>();

    private FakeTtlockServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/oauth2/token", ex -> handle(ex, this::token));
        server.createContext("/v3/lock/unlock", ex -> handle(ex, this::unlock));
        server.createContext("/v3/lock/list", ex -> handle(ex, this::list));
        server.createContext("/v3/lock/detail", ex -> handle(ex, this::detail));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "fake-ttlock-shutdown"));
    }

    public static FakeTtlockServer get() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ control desde los tests

    public void failUnlock(long lockId, int errcode) {
        unlockErrors.put(lockId, errcode);
    }

    /** Invalida todos los tokens emitidos: la próxima llamada recibirá errcode 10003. */
    public void expireAllTokens() {
        validTokens.clear();
    }

    public void addAccountLock(long lockId, String alias, int battery) {
        accountLocks.put(lockId, new LockInfo(lockId, alias, battery));
    }

    public List<Map<String, String>> unlockRequestsFor(long lockId) {
        return unlockRequests.stream().filter(r -> String.valueOf(lockId).equals(r.get("lockId"))).toList();
    }

    public int tokenGrants() {
        return passwordGrants.get() + refreshGrants.get();
    }

    // ------------------------------------------------------------------ endpoints

    private String token(Map<String, String> p) {
        if (!CLIENT_ID.equals(p.get("clientId")) || !CLIENT_SECRET.equals(p.get("clientSecret"))) {
            return "{\"errcode\":10001,\"errmsg\":\"invalid client\"}";
        }
        if ("refresh_token".equals(p.get("grant_type"))) {
            if (p.get("refresh_token") == null || !p.get("refresh_token").startsWith("refresh-")) {
                return "{\"errcode\":10011,\"errmsg\":\"invalid refresh_token\"}";
            }
            refreshGrants.incrementAndGet();
        } else {
            // La contraseña llega como MD5 en minúsculas (32 caracteres hexadecimales).
            if (!USERNAME.equals(p.get("username")) || p.get("password") == null
                    || !p.get("password").matches("^[0-9a-f]{32}$")) {
                return "{\"errcode\":10007,\"errmsg\":\"invalid account or invalid password\"}";
            }
            passwordGrants.incrementAndGet();
        }
        int n = tokenCounter.incrementAndGet();
        String token = "access-" + n;
        validTokens.add(token);
        return "{\"access_token\":\"" + token + "\",\"refresh_token\":\"refresh-" + n
                + "\",\"uid\":1234,\"expires_in\":7776000,\"scope\":\"user,key,room\"}";
    }

    private String unlock(Map<String, String> p) {
        if (!validTokens.contains(p.get("accessToken"))) {
            return "{\"errcode\":10003,\"errmsg\":\"invalid token\"}";
        }
        if (p.get("date") == null || !CLIENT_ID.equals(p.get("clientId"))) {
            return "{\"errcode\":-3,\"errmsg\":\"Invalid Parameter\"}";
        }
        unlockRequests.add(Map.copyOf(p));
        Integer error = unlockErrors.get(Long.parseLong(p.get("lockId")));
        return error == null ? "{\"errcode\":0,\"errmsg\":\"none error message or means yes\"}"
                : "{\"errcode\":" + error + ",\"errmsg\":\"simulated error\"}";
    }

    private String list(Map<String, String> p) {
        if (!validTokens.contains(p.get("accessToken"))) {
            return "{\"errcode\":10003,\"errmsg\":\"invalid token\"}";
        }
        String items = accountLocks.values().stream()
                .map(l -> "{\"lockId\":" + l.id() + ",\"lockAlias\":\"" + l.alias() + "\",\"lockMac\":\"aa:bb:cc:dd:ee:"
                        + String.format("%02x", l.id() % 256) + "\",\"electricQuantity\":" + l.battery()
                        + ",\"hasGateway\":1}")
                .collect(Collectors.joining(","));
        return "{\"list\":[" + items + "],\"pageNo\":1,\"pageSize\":100,\"pages\":1,\"total\":" + accountLocks.size() + "}";
    }

    private String detail(Map<String, String> p) {
        if (!validTokens.contains(p.get("accessToken"))) {
            return "{\"errcode\":10003,\"errmsg\":\"invalid token\"}";
        }
        LockInfo lock = accountLocks.get(Long.parseLong(p.get("lockId")));
        if (lock == null) {
            return "{\"errcode\":-1003,\"errmsg\":\"lock does not exist\"}";
        }
        return "{\"lockId\":" + lock.id() + ",\"lockAlias\":\"" + lock.alias() + "\",\"electricQuantity\":"
                + lock.battery() + ",\"hasGateway\":1}";
    }

    // ------------------------------------------------------------------ infraestructura

    private static void handle(HttpExchange exchange, java.util.function.Function<Map<String, String>, String> handler)
            throws IOException {
        Map<String, String> params = new HashMap<>(parse(exchange.getRequestURI().getRawQuery()));
        if ("POST".equals(exchange.getRequestMethod())) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
                respond(exchange, 415, "{\"errcode\":-3,\"errmsg\":\"form encoding required\"}");
                return;
            }
            params.putAll(parse(body));
        }
        respond(exchange, 200, handler.apply(params));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parse(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private record LockInfo(long id, String alias, int battery) {
    }
}

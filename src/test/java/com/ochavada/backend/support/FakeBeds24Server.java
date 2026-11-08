package com.ochavada.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Servidor HTTP local que imita la API V2 de Beds24 (autenticación, reservas, calendario de inventario y
 * alojamientos). Permite ejercitar el cliente Feign real: cabeceras, parámetros y deserialización.
 */
public final class FakeBeds24Server {

    public static final String REFRESH_TOKEN = "refresh-token-de-pruebas";
    public static final String INVITE_CODE = "codigo-invitacion-valido";
    public static final String INVITE_REFRESH_TOKEN = "refresh-token-por-invitacion";

    private static final FakeBeds24Server INSTANCE = new FakeBeds24Server();

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> validRefreshTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final AtomicInteger tokenGrants = new AtomicInteger();
    private final AtomicLong bookingIds = new AtomicLong(800_000);
    private final AtomicInteger failNextCalendar = new AtomicInteger();
    private final Map<Long, ObjectNode> bookings = new ConcurrentHashMap<>();
    private final List<JsonNode> calendarUpdates = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bookingWrites = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> bookingQueries = new CopyOnWriteArrayList<>();
    private volatile String propertiesResponse = "{\"success\":true,\"data\":[]}";

    private FakeBeds24Server() {
        validRefreshTokens.add(REFRESH_TOKEN);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v2/authentication/token", this::token);
        server.createContext("/v2/authentication/setup", this::setup);
        server.createContext("/v2/bookings", this::bookings);
        server.createContext("/v2/inventory/rooms/calendar", this::calendar);
        server.createContext("/v2/properties", ex -> authorized(ex, () -> propertiesResponse));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "fake-beds24-shutdown"));
    }

    public static FakeBeds24Server get() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
    }

    // ------------------------------------------------------------------ control desde los tests

    /** Crea o sustituye una reserva tal como la devolvería {@code GET /bookings}. */
    public void putBooking(Map<String, Object> booking) {
        ObjectNode node = json.valueToTree(booking);
        bookings.put(node.get("id").asLong(), node);
    }

    public void updateBooking(long id, Map<String, Object> changes) {
        ObjectNode node = bookings.get(id);
        changes.forEach((k, v) -> node.set(k, json.valueToTree(v)));
    }

    public JsonNode booking(long id) {
        return bookings.get(id);
    }

    /** Elementos de {@code POST /bookings} recibidos con este localizador o identificador. */
    public List<JsonNode> bookingWritesFor(String apiReference, Long id) {
        return bookingWrites.stream()
                .filter(b -> (apiReference != null && apiReference.equals(b.path("apiReference").asString(null)))
                        || (id != null && b.path("id").asLong(-1) == id))
                .toList();
    }

    /** Últimos días de calendario recibidos para una habitación, por fecha. */
    public Map<String, JsonNode> lastCalendarFor(long roomId) {
        Map<String, JsonNode> days = new LinkedHashMap<>();
        for (JsonNode update : calendarUpdates) {
            for (JsonNode room : update) {
                if (room.path("roomId").asLong() == roomId) {
                    room.path("calendar").forEach(d -> days.put(d.path("from").asString(), d));
                }
            }
        }
        return days;
    }

    public int calendarUpdatesFor(long roomId) {
        int n = 0;
        for (JsonNode update : calendarUpdates) {
            for (JsonNode room : update) {
                if (room.path("roomId").asLong() == roomId) {
                    n++;
                }
            }
        }
        return n;
    }

    public void failNextCalendarWith429(int times) {
        failNextCalendar.set(times);
    }

    /** Invalida los access tokens emitidos: la siguiente llamada recibirá 401. */
    public void expireAccessTokens() {
        validTokens.clear();
    }

    public int tokenGrants() {
        return tokenGrants.get();
    }

    public void setPropertiesResponse(String response) {
        this.propertiesResponse = response;
    }

    // ------------------------------------------------------------------ endpoints

    private void token(HttpExchange ex) throws IOException {
        String refresh = ex.getRequestHeaders().getFirst("refreshToken");
        if (refresh == null || !validRefreshTokens.contains(refresh)) {
            respond(ex, 401, "{\"success\":false,\"error\":\"Invalid refresh token\"}");
            return;
        }
        respond(ex, 200, issueToken(null));
    }

    private void setup(HttpExchange ex) throws IOException {
        if (!INVITE_CODE.equals(ex.getRequestHeaders().getFirst("code"))) {
            respond(ex, 400, "{\"success\":false,\"error\":\"Invalid code\"}");
            return;
        }
        validRefreshTokens.add(INVITE_REFRESH_TOKEN);
        respond(ex, 200, issueToken(INVITE_REFRESH_TOKEN));
    }

    private String issueToken(String refreshToken) {
        String token = "access-" + tokenCounter.incrementAndGet();
        validTokens.add(token);
        tokenGrants.incrementAndGet();
        return "{\"token\":\"" + token + "\",\"expiresIn\":86400"
                + (refreshToken == null ? "" : ",\"refreshToken\":\"" + refreshToken + "\"") + "}";
    }

    private void bookings(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) {
            return;
        }
        if ("POST".equals(ex.getRequestMethod())) {
            JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
            ArrayNode results = json.createArrayNode();
            for (JsonNode item : body) {
                bookingWrites.add(item);
                ObjectNode result = results.addObject();
                result.put("success", true);
                if (item.hasNonNull("id")) {
                    long id = item.get("id").asLong();
                    ObjectNode stored = bookings.computeIfAbsent(id, k -> json.createObjectNode().put("id", k));
                    stored.setAll((ObjectNode) item);
                    result.putObject("modified").put("id", id);
                } else {
                    long id = bookingIds.incrementAndGet();
                    ObjectNode stored = ((ObjectNode) item).deepCopy();
                    stored.put("id", id);
                    stored.put("channel", "api");
                    bookings.put(id, stored);
                    result.putObject("new").put("id", id);
                }
            }
            respond(ex, 201, json.writeValueAsString(results));
            return;
        }
        Map<String, List<String>> query = query(ex);
        Map<String, String> flat = new LinkedHashMap<>();
        query.forEach((k, v) -> flat.put(k, String.join(",", v)));
        bookingQueries.add(flat);
        List<ObjectNode> result = new ArrayList<>();
        if (query.containsKey("id")) {
            long id = Long.parseLong(query.get("id").getFirst());
            if (bookings.containsKey(id)) {
                result.add(bookings.get(id));
            }
        } else if (query.containsKey("modifiedFrom") || query.containsKey("arrivalFrom")) {
            List<String> statuses = query.getOrDefault("status", List.of());
            bookings.values().stream()
                    .filter(b -> statuses.isEmpty() || statuses.contains(b.path("status").asString()))
                    .forEach(result::add);
        }
        ObjectNode page = json.createObjectNode();
        page.put("success", true);
        page.put("type", "booking");
        page.put("count", result.size());
        page.putObject("pages").put("nextPageExists", false);
        ArrayNode data = page.putArray("data");
        result.forEach(data::add);
        respond(ex, 200, json.writeValueAsString(page));
    }

    private void calendar(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) {
            return;
        }
        if (failNextCalendar.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
            respond(ex, 429, "{\"success\":false,\"error\":\"Credit limit exceeded\"}");
            return;
        }
        JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
        calendarUpdates.add(body);
        ArrayNode results = json.createArrayNode();
        body.forEach(r -> results.addObject().put("success", true));
        respond(ex, 201, json.writeValueAsString(results));
    }

    private void authorized(HttpExchange ex, java.util.function.Supplier<String> body) throws IOException {
        if (checkToken(ex)) {
            respond(ex, 200, body.get());
        }
    }

    private boolean checkToken(HttpExchange ex) throws IOException {
        String token = ex.getRequestHeaders().getFirst("token");
        if (token == null || !validTokens.contains(token)) {
            respond(ex, 401, "{\"success\":false,\"error\":\"Token not valid\"}");
            return false;
        }
        return true;
    }

    private static Map<String, List<String>> query(HttpExchange ex) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            for (String v : value.split(",")) {
                params.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
            }
        }
        return params;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

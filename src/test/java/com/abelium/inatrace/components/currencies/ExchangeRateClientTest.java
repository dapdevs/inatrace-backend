package com.abelium.inatrace.components.currencies;

import com.abelium.inatrace.components.currencies.api.ApiCurrencyRatesResponse;
import com.abelium.inatrace.components.currencies.api.ApiCurrencySymbolsResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeRateClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ExchangeRateClient clientFor(HttpServer server) {
        this.server = server;
        server.start();
        ExchangeRateClient client = new ExchangeRateClient();
        ReflectionTestUtils.setField(client, "baseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "appId", "test-app-id");
        return client;
    }

    private HttpServer newServer() throws IOException {
        return HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void getSymbols_returnsParsedMap_onSuccess() throws IOException {
        HttpServer server = newServer();
        server.createContext("/currencies.json", exchange ->
                respond(exchange, 200, "{\"USD\":\"US Dollar\",\"EUR\":\"Euro\"}"));
        ExchangeRateClient client = clientFor(server);

        ApiCurrencySymbolsResponse response = client.getSymbols();

        assertTrue(response.isSuccess());
        assertEquals("US Dollar", response.getSymbols().get("USD"));
        assertEquals("Euro", response.getSymbols().get("EUR"));
    }

    @Test
    void getLatestRates_returnsParsedRates_onSuccess() throws IOException {
        HttpServer server = newServer();
        server.createContext("/latest.json", exchange ->
                respond(exchange, 200, "{\"base\":\"USD\",\"timestamp\":1699999999,\"rates\":{\"EUR\":0.92}}"));
        ExchangeRateClient client = clientFor(server);

        ApiCurrencyRatesResponse response = client.getLatestRates();

        assertTrue(response.isSuccess());
        assertEquals("USD", response.getBase());
        assertEquals(0, response.getRates().get("EUR").compareTo(new java.math.BigDecimal("0.92")));
    }

    @Test
    void getHistoricalRates_requestsIsoDatePath() throws IOException {
        HttpServer server = newServer();
        AtomicInteger requestedPath = new AtomicInteger(0);
        server.createContext("/historical/2024-01-15.json", exchange -> {
            requestedPath.incrementAndGet();
            respond(exchange, 200, "{\"base\":\"USD\",\"timestamp\":1705276800,\"rates\":{\"EUR\":0.9}}");
        });
        ExchangeRateClient client = clientFor(server);

        ApiCurrencyRatesResponse response = client.getHistoricalRates(LocalDate.of(2024, 1, 15));

        assertTrue(response.isSuccess());
        assertEquals(1, requestedPath.get());
    }

    @Test
    void get_doesNotRetry_on4xx() throws IOException {
        HttpServer server = newServer();
        AtomicInteger requestCount = new AtomicInteger(0);
        server.createContext("/latest.json", exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, 401, "{\"error\":true,\"message\":\"invalid_app_id\"}");
        });
        ExchangeRateClient client = clientFor(server);

        ApiCurrencyRatesResponse response = client.getLatestRates();

        assertFalse(response.isSuccess());
        assertEquals(1, requestCount.get());
    }

    @Test
    void get_retriesAndSucceeds_on5xxThenSuccess() throws IOException {
        HttpServer server = newServer();
        AtomicInteger requestCount = new AtomicInteger(0);
        server.createContext("/latest.json", exchange -> {
            if (requestCount.incrementAndGet() <= 1) {
                respond(exchange, 503, "temporarily unavailable");
            } else {
                respond(exchange, 200, "{\"base\":\"USD\",\"timestamp\":1699999999,\"rates\":{\"EUR\":0.92}}");
            }
        });
        ExchangeRateClient client = clientFor(server);

        ApiCurrencyRatesResponse response = client.getLatestRates();

        assertTrue(response.isSuccess());
        assertEquals(2, requestCount.get());
    }

    @Test
    void get_givesUpAfterRetries_onPersistent5xx() throws IOException {
        HttpServer server = newServer();
        AtomicInteger requestCount = new AtomicInteger(0);
        server.createContext("/latest.json", exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, 503, "temporarily unavailable");
        });
        ExchangeRateClient client = clientFor(server);

        ApiCurrencyRatesResponse response = client.getLatestRates();

        assertFalse(response.isSuccess());
        // Initial attempt + 3 retries.
        assertEquals(4, requestCount.get());
    }
}

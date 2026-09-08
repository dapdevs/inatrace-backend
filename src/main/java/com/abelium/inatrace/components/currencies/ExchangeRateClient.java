package com.abelium.inatrace.components.currencies;

import com.abelium.inatrace.components.currencies.api.ApiCurrencyRatesResponse;
import com.abelium.inatrace.components.currencies.api.ApiCurrencySymbolsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Thin client for the openexchangerates.org API. Isolates URL building, authentication and
 * HTTP-status-based error handling (the provider reports errors via HTTP status, not a body flag)
 * so that services depend on a stable contract instead of the provider's wire format.
 */
@Component
public class ExchangeRateClient {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateClient.class);

    // Only transient failures (network errors, 5xx) are retried; 4xx (e.g. invalid app_id) fail fast.
    private static final int MAX_RETRIES = 3;
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);

    @Value("${INAtrace.exchangerate.baseUrl:https://openexchangerates.org/api}")
    private String baseUrl;

    @Value("${INAtrace.exchangerate.appId}")
    private String appId;

    public ApiCurrencySymbolsResponse getSymbols() {
        Map<String, String> symbols = get(baseUrl + "/currencies.json?app_id=" + appId,
                new ParameterizedTypeReference<Map<String, String>>() { });
        ApiCurrencySymbolsResponse response = new ApiCurrencySymbolsResponse();
        response.setSuccess(symbols != null);
        response.setSymbols(symbols);
        return response;
    }

    public ApiCurrencyRatesResponse getLatestRates() {
        return getRates(baseUrl + "/latest.json?app_id=" + appId);
    }

    public ApiCurrencyRatesResponse getHistoricalRates(LocalDate date) {
        String isoDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        return getRates(baseUrl + "/historical/" + isoDate + ".json?app_id=" + appId);
    }

    private ApiCurrencyRatesResponse getRates(String url) {
        ApiCurrencyRatesResponse response = get(url, new ParameterizedTypeReference<ApiCurrencyRatesResponse>() { });
        if (response == null) {
            return new ApiCurrencyRatesResponse();
        }
        response.setSuccess(true);
        return response;
    }

    private <T> T get(String url, ParameterizedTypeReference<T> type) {
        return WebClient.create()
                .get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(type);
                    }
                    if (clientResponse.statusCode().is5xxServerError()) {
                        return clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new TransientExchangeRateException(clientResponse.statusCode(), body)));
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                logger.error("openexchangerates.org request failed with status {}: {}", clientResponse.statusCode(), body);
                                return Mono.<T>empty();
                            });
                })
                .retryWhen(Retry.backoff(MAX_RETRIES, MIN_BACKOFF)
                        .filter(e -> e instanceof TransientExchangeRateException || e instanceof WebClientRequestException)
                        .doBeforeRetry(signal -> logger.warn("Retrying openexchangerates.org request after transient error ({}/{}): {}",
                                signal.totalRetries() + 1, MAX_RETRIES, signal.failure().getMessage())))
                .onErrorResume(e -> {
                    logger.error("Failed to call openexchangerates.org: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();
    }

    private static final class TransientExchangeRateException extends RuntimeException {
        TransientExchangeRateException(HttpStatusCode status, String body) {
            super("status " + status + ": " + body);
        }
    }
}

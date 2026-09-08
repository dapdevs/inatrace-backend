package com.abelium.inatrace.components.currencies.api;

import java.util.Map;

/**
 * openexchangerates.org's /currencies.json endpoint returns a flat {code: name} JSON object
 * with no wrapper, so instances of this class are built manually by ExchangeRateClient
 * (based on the HTTP status) rather than deserialized directly from the response body.
 */
public class ApiCurrencySymbolsResponse {

    private boolean success;
    private Map<String, String> symbols;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Map<String, String> getSymbols() {
        return symbols;
    }

    public void setSymbols(Map<String, String> symbols) {
        this.symbols = symbols;
    }
}

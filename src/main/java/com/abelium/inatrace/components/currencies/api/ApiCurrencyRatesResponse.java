package com.abelium.inatrace.components.currencies.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * Maps the body of openexchangerates.org's /latest.json and /historical/{date}.json endpoints.
 * The provider has no "success" field in the body; it is set by the caller based on the HTTP status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiCurrencyRatesResponse {

    private boolean success;
    private String base;
    private long timestamp;
    private Map<String, BigDecimal> rates;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getBase() {
        return base;
    }

    public void setBase(String base) {
        this.base = base;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Date getDate() {
        return new Date(timestamp * 1000L);
    }

    public Map<String, BigDecimal> getRates() {
        return rates;
    }

    public void setRates(Map<String, BigDecimal> rates) {
        this.rates = rates;
    }
}

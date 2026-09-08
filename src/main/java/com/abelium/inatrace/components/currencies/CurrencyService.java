package com.abelium.inatrace.components.currencies;

import com.abelium.inatrace.components.codebook.currencies.CurrencyTypeService;
import com.abelium.inatrace.components.common.BaseService;
import com.abelium.inatrace.components.currencies.api.ApiCurrencyRatesResponse;
import com.abelium.inatrace.db.entities.codebook.CurrencyType;
import com.abelium.inatrace.db.entities.currencies.CurrencyPair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class CurrencyService extends BaseService {

    private static final String CURRENCY = "currency";
    private static final String BASE = "base";

    @Autowired
    private CurrencyTypeService currencyTypeService;

    @Autowired
    private ExchangeRateClient exchangeRateClient;

    @Value("${INAtrace.exchangerate.baseCurrency}")
    private String baseCurrency;

    public BigDecimal convertFromBase(String to, BigDecimal value) {
        return value.multiply(em.createNamedQuery("CurrencyPair.latestRate", BigDecimal.class).setParameter(BASE, baseCurrency).setParameter(CURRENCY, to).getResultList().get(0));
    }

    public BigDecimal convertToBase(String from, BigDecimal value) {
        return value.divide(em.createNamedQuery("CurrencyPair.latestRate", BigDecimal.class).setParameter(BASE, baseCurrency).setParameter(CURRENCY, from).getResultList().get(0), 6, RoundingMode.HALF_UP);
    }

    @Transactional
    public BigDecimal convertFromBaseAtDate(String to, BigDecimal value, Date date) {

        List<BigDecimal> rates = rateAtDateQuery(to, date).getResultList();
        BigDecimal rate;
        if (rates.isEmpty()) {
            fetchRates(date);
            rate = rateAtDateQuery(to, date).getSingleResult();
        } else {
            rate = rates.get(0);
        }
        return value.multiply(rate);
    }

    @Transactional
    public BigDecimal convertToBaseAtDate(String from, BigDecimal value, Date date) {

        List<BigDecimal> rates = rateAtDateQuery(from, date).getResultList();
        BigDecimal rate;
        if (rates.isEmpty()) {
            fetchRates(date);
            rate = rateAtDateQuery(from, date).getSingleResult();
        } else {
            rate = rates.get(0);
        }
        return value.divide(rate, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal convert(String from, String to, BigDecimal value) {

        if (from.equals(to)) {
            return value;
        } else if (baseCurrency.equals(from)) {
            return this.convertFromBase(to, value);
        } else if (baseCurrency.equals(to)) {
            return this.convertToBase(from, value);
        } else {
            return this.convertFromBase(to, this.convertToBase(from, value));
        }
    }

    @Transactional
    public BigDecimal convertAtDate(String from, String to, BigDecimal value, Date date) {

        if (from.equals(to)) {
            return value;
        } else if (baseCurrency.equals(from)) {
            return this.convertFromBaseAtDate(to, value, date);
        } else if (baseCurrency.equals(to)) {
            return this.convertToBaseAtDate(from, value, date);
        } else {
            return this.convertFromBaseAtDate(to, this.convertToBaseAtDate(from, value, date), date);
        }
    }

    public void fetchRates(Date date) {

        ApiCurrencyRatesResponse apiCurrencyRatesResponse = exchangeRateClient.getHistoricalRates(date.toInstant().atZone(ZoneId.of("GMT")).toLocalDate());
        if (apiCurrencyRatesResponse.isSuccess()) {
            Map<String, BigDecimal> rates = apiCurrencyRatesResponse.getRates();
            Date current = apiCurrencyRatesResponse.getDate();

            List<String> enabled = currencyTypeService.getEnabledCurrencyCodes();

            for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
                if (entry.getKey().equals(baseCurrency)) {
                    continue;
                }
                if (enabled.contains(entry.getKey()) && rateAtDateQuery(entry.getKey(), current).getResultList().isEmpty()) {
                    CurrencyPair currencyPair = new CurrencyPair();
                    CurrencyType from = currencyTypeService.getCurrencyTypeByCode(baseCurrency);
                    CurrencyType to = currencyTypeService.getCurrencyTypeByCode(entry.getKey());
                    currencyPair.setFrom(from);
                    currencyPair.setTo(to);
                    currencyPair.setDate(current);
                    currencyPair.setValue(entry.getValue());
                    em.persist(currencyPair);
                }
            }
        }
    }

    private TypedQuery<BigDecimal> rateAtDateQuery(String currency, Date date) {
        return em.createNamedQuery("CurrencyPair.rateAtDate", BigDecimal.class).setParameter(BASE, baseCurrency).setParameter(CURRENCY, currency).setParameter("date", date);
    }

}

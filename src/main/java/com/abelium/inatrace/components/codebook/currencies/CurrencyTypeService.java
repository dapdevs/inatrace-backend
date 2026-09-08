package com.abelium.inatrace.components.codebook.currencies;

import com.abelium.inatrace.api.ApiPaginatedResponse;
import com.abelium.inatrace.components.codebook.currencies.api.ApiCurrencyType;
import com.abelium.inatrace.components.common.BaseService;
import com.abelium.inatrace.components.currencies.ExchangeRateClient;
import com.abelium.inatrace.components.currencies.api.ApiCurrencyRatesResponse;
import com.abelium.inatrace.components.currencies.api.ApiCurrencySymbolsResponse;
import com.abelium.inatrace.components.currencies.api.ApiCurrencyTypeRequest;
import com.abelium.inatrace.db.entities.codebook.CurrencyType;
import com.abelium.inatrace.db.entities.currencies.CurrencyPair;
import com.abelium.inatrace.tools.PaginationTools;
import com.abelium.inatrace.tools.QueryTools;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.torpedoquery.jakarta.jpa.OnGoingLogicalCondition;
import org.torpedoquery.jakarta.jpa.Torpedo;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CurrencyTypeService extends BaseService {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyTypeService.class);
    private static final String CURRENCY = "currency";
    private static final String BASE = "base";

    @Autowired
    private ExchangeRateClient exchangeRateClient;

    @Value("${INAtrace.exchangerate.baseCurrency}")
    private String baseCurrency;

    public List<ApiCurrencyType> getCurrencyTypeList() {
        List<CurrencyType> currencyTypeList = em.createNamedQuery("CurrencyType.getAllCurrencyTypes", CurrencyType.class).getResultList();
        return CurrencyTypeMapper.toApiCurrencyTypeList(currencyTypeList);
    }

    public ApiPaginatedResponse<ApiCurrencyType> getCurrencyTypeList(Boolean enabled, ApiCurrencyTypeRequest request) {
        return new ApiPaginatedResponse<>(PaginationTools.createPaginatedResponse(em, request, () -> currencyTypeProxy(enabled, request), CurrencyTypeMapper::toApiCurrencyType));
    }

    public CurrencyType getCurrencyType(Long id) {
        return em.find(CurrencyType.class, id);
    }

    public CurrencyType getCurrencyTypeByCode(String code) {
        return em.createNamedQuery("CurrencyType.getCurrencyTypeByCode", CurrencyType.class).setParameter("code", code).getSingleResult();
    }

    @Transactional
    public void updateStatus(Long id, Boolean enabled) {
        CurrencyType currencyType = em.find(CurrencyType.class, id);
        currencyType.setEnabled(enabled);
        if (enabled) {
            updateCurrencies();
        }
    }

    @Transactional
    @Scheduled(cron = "0 1 0 * * *")
    @EventListener(ApplicationReadyEvent.class)
    public void updateCurrencies() {
        ApiCurrencySymbolsResponse apiCurrencySymbolsResponse = exchangeRateClient.getSymbols();
        if (!apiCurrencySymbolsResponse.isSuccess()) {
            logger.error("Failed to fetch currency symbols from openexchangerates.org");
        } else {
            Map<String, String> symbols = apiCurrencySymbolsResponse.getSymbols();
            for (Map.Entry<String, String> entry : symbols.entrySet()) {
                if (em.createNamedQuery("CurrencyType.getCurrencyTypeByCode").setParameter("code", entry.getKey()).getResultList().isEmpty()) {
                    CurrencyType currencyType = new CurrencyType();
                    currencyType.setCode(entry.getKey());
                    currencyType.setLabel(entry.getValue());
                    currencyType.setEnabled(Boolean.FALSE);
                    em.persist(currencyType);
                }
            }
        }

        ApiCurrencyRatesResponse apiCurrencyResponse = exchangeRateClient.getLatestRates();
        if (!apiCurrencyResponse.isSuccess()) {
            logger.error("Failed to fetch exchange rates from openexchangerates.org");
            return;
        }

        Map<String, BigDecimal> rates = apiCurrencyResponse.getRates();
        Date current = apiCurrencyResponse.getDate();

        List<String> enabled = getEnabledCurrencyCodes();

        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            // The provider omits the base currency's own rate; nothing to store for it against itself.
            if (entry.getKey().equals(baseCurrency)) {
                continue;
            }
            if (enabled.contains(entry.getKey()) && em.createNamedQuery("CurrencyPair.rateAtDate").setParameter(BASE, baseCurrency).setParameter(CURRENCY, entry.getKey()).setParameter("date", current).getResultList().isEmpty()) {
                CurrencyPair currencyPair = new CurrencyPair();
                CurrencyType from = getCurrencyTypeByCode(baseCurrency);
                CurrencyType to = getCurrencyTypeByCode(entry.getKey());
                currencyPair.setFrom(from);
                currencyPair.setTo(to);
                currencyPair.setDate(current);
                currencyPair.setValue(entry.getValue());
                em.persist(currencyPair);
            }
        }
    }

    public List<String> getEnabledCurrencyCodes() {
        return em.createNamedQuery("CurrencyType.getEnabledCurrencyTypes", CurrencyType.class).getResultList().stream().map(CurrencyType::getCode).collect(
                Collectors.toList());
    }

    private CurrencyType currencyTypeProxy(Boolean enabled, ApiCurrencyTypeRequest request) {
        CurrencyType currencyTypeProxy = Torpedo.from(CurrencyType.class);

        OnGoingLogicalCondition condition = Torpedo.condition();

        if (enabled != null) {
            condition = condition.and(currencyTypeProxy.getEnabled()).eq(enabled);
        }

        if (request.getQuery() != null) {
            OnGoingLogicalCondition codeLikeQuery = Torpedo.condition(currencyTypeProxy.getCode()).like().any(request.getQuery());
            OnGoingLogicalCondition labelLikeQuery = Torpedo.condition(currencyTypeProxy.getLabel()).like().any(request.getQuery());
            condition = condition.and(codeLikeQuery.or(labelLikeQuery));
        }

        Torpedo.where(condition);

        switch (request.sortBy) {
            case "code":
                QueryTools.orderBy(request.sort, currencyTypeProxy.getCode());
                break;
            case "label":
                QueryTools.orderBy(request.sort, currencyTypeProxy.getLabel());
                break;
        }

        return currencyTypeProxy;
    }
}

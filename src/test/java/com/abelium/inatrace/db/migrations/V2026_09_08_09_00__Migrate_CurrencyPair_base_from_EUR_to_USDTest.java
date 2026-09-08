package com.abelium.inatrace.db.migrations;

import com.abelium.inatrace.db.entities.codebook.CurrencyType;
import com.abelium.inatrace.db.entities.currencies.CurrencyPair;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2026_09_08_09_00__Migrate_CurrencyPair_base_from_EUR_to_USDTest {

    private final CurrencyType eur = currencyType("EUR");
    private final CurrencyType usd = currencyType("USD");
    private final CurrencyType rwf = currencyType("RWF");

    private static CurrencyType currencyType(String code) {
        CurrencyType currencyType = new CurrencyType();
        currencyType.setCode(code);
        return currencyType;
    }

    private static Date date(String isoDate) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(isoDate);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static CurrencyPair pair(CurrencyType from, CurrencyType to, Date date, String value) {
        CurrencyPair pair = new CurrencyPair();
        pair.setFrom(from);
        pair.setTo(to);
        pair.setDate(date);
        pair.setValue(new BigDecimal(value));
        return pair;
    }

    @Test
    void reanchor_rewritesPairs_forDateWithAnchor() {
        Date d = date("2024-01-15");
        // 1 EUR = 1.08 USD, 1 EUR = 1200 RWF
        CurrencyPair eurToUsd = pair(eur, usd, d, "1.08");
        CurrencyPair eurToRwf = pair(eur, rwf, d, "1200");
        List<CurrencyPair> pairs = new ArrayList<>(List.of(eurToUsd, eurToRwf));
        List<Date> orphanDates = new ArrayList<>();

        int migrated = V2026_09_08_09_00__Migrate_CurrencyPair_base_from_EUR_to_USD.reanchor(pairs, eur, usd, orphanDates);

        assertEquals(1, migrated);
        assertTrue(orphanDates.isEmpty());

        // The anchor row becomes USD -> EUR (1 / 1.08).
        assertSame(usd, eurToUsd.getFrom());
        assertSame(eur, eurToUsd.getTo());
        assertEquals(0, eurToUsd.getValue().compareTo(BigDecimal.ONE.divide(new BigDecimal("1.08"), 6, java.math.RoundingMode.HALF_UP)));

        // Every other row becomes USD -> X (value / 1.08).
        assertSame(usd, eurToRwf.getFrom());
        assertSame(rwf, eurToRwf.getTo());
        assertEquals(0, eurToRwf.getValue().compareTo(new BigDecimal("1200").divide(new BigDecimal("1.08"), 6, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void reanchor_reportsOrphanDate_whenNoAnchorRate() {
        Date orphanDate = date("2023-05-01");
        CurrencyPair eurToRwf = pair(eur, rwf, orphanDate, "1180");
        List<CurrencyPair> pairs = new ArrayList<>(List.of(eurToRwf));
        List<Date> orphanDates = new ArrayList<>();

        int migrated = V2026_09_08_09_00__Migrate_CurrencyPair_base_from_EUR_to_USD.reanchor(pairs, eur, usd, orphanDates);

        assertEquals(0, migrated);
        assertEquals(List.of(orphanDate), orphanDates);
        // Left untouched: still anchored on EUR.
        assertSame(eur, eurToRwf.getFrom());
        assertEquals(0, eurToRwf.getValue().compareTo(new BigDecimal("1180")));
    }

    @Test
    void reanchor_handlesMultipleDatesIndependently() {
        Date d1 = date("2024-01-15");
        Date d2 = date("2024-01-16");
        CurrencyPair d1Anchor = pair(eur, usd, d1, "1.08");
        CurrencyPair d1Rwf = pair(eur, rwf, d1, "1200");
        CurrencyPair d2Rwf = pair(eur, rwf, d2, "1210"); // no USD anchor for d2
        List<CurrencyPair> pairs = new ArrayList<>(List.of(d1Anchor, d1Rwf, d2Rwf));
        List<Date> orphanDates = new ArrayList<>();

        int migrated = V2026_09_08_09_00__Migrate_CurrencyPair_base_from_EUR_to_USD.reanchor(pairs, eur, usd, orphanDates);

        assertEquals(1, migrated);
        assertEquals(List.of(d2), orphanDates);
        assertSame(usd, d1Rwf.getFrom());
        assertSame(eur, d2Rwf.getFrom());
    }
}

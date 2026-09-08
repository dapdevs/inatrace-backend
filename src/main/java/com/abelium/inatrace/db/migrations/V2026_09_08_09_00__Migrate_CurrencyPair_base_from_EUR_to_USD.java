package com.abelium.inatrace.db.migrations;

import com.abelium.inatrace.components.flyway.JpaMigration;
import com.abelium.inatrace.db.entities.codebook.CurrencyType;
import com.abelium.inatrace.db.entities.currencies.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Re-anchors the historical {@link CurrencyPair} table from the retired EUR pivot to USD, the pivot
 * used by the new openexchangerates.org integration.
 *
 * For every date that already has an EUR-&gt;USD rate, every other EUR-&gt;X pair of that date is rewritten
 * in place as USD-&gt;X (dividing by the EUR-&gt;USD rate of that same date), and the EUR-&gt;USD row itself is
 * repurposed into the missing USD-&gt;EUR row (inverting its value). Dates that have no EUR-&gt;USD rate to use
 * as an anchor cannot be converted and are left untouched; they are logged as a single WARN summary so an
 * operator can decide whether to backfill or discard them.
 *
 * No environment this migration has run against so far has actual CurrencyPair data (the original
 * exchangeratesapi.io provider has been offline for years), so this is expected to be a no-op in practice.
 * It is still guarded and safe to run against a populated table for any deployment that does have data.
 */
public class V2026_09_08_09_00__Migrate_CurrencyPair_base_from_EUR_to_USD implements JpaMigration {

    private static final Logger logger = LoggerFactory.getLogger(V2026_09_08_09_00__Migrate_CurrencyPair_base_from_EUR_to_USD.class);

    private static final String OLD_BASE_CODE = "EUR";
    private static final String NEW_BASE_CODE = "USD";
    private static final int SCALE = 6;

    @Override
    public void migrate(EntityManager em, Environment environment) throws Exception {

        Optional<CurrencyType> oldBase = findByCode(em, OLD_BASE_CODE);
        Optional<CurrencyType> newBase = findByCode(em, NEW_BASE_CODE);

        if (oldBase.isEmpty() || newBase.isEmpty()) {
            logger.info("Skipping CurrencyPair base migration: '{}' and/or '{}' CurrencyType not present.", OLD_BASE_CODE, NEW_BASE_CODE);
            return;
        }

        List<CurrencyPair> oldBasePairs = em.createQuery(
                        "SELECT c FROM CurrencyPair c WHERE c.from = :oldBase", CurrencyPair.class)
                .setParameter("oldBase", oldBase.get())
                .getResultList();

        if (oldBasePairs.isEmpty()) {
            logger.info("Skipping CurrencyPair base migration: no CurrencyPair rows anchored on '{}'.", OLD_BASE_CODE);
            return;
        }

        List<Date> orphanDates = new ArrayList<>();
        int migratedDates = reanchor(oldBasePairs, oldBase.get(), newBase.get(), orphanDates);

        logger.info("CurrencyPair base migration: migrated {} date(s) from '{}' to '{}'.", migratedDates, OLD_BASE_CODE, NEW_BASE_CODE);
        if (!orphanDates.isEmpty()) {
            logger.warn("CurrencyPair base migration: {} date(s) had no '{}'->'{}' anchor rate and were left un-migrated: {}",
                    orphanDates.size(), OLD_BASE_CODE, NEW_BASE_CODE, orphanDates);
        }
    }

    /**
     * Pure in-memory re-anchoring of {@code oldBasePairs} (all sharing {@code oldBase} as their "from"),
     * grouped by date. Mutates the pairs in place and appends dates with no {@code oldBase}-&gt;{@code newBase}
     * anchor to {@code orphanDatesOut}. Returns the number of dates successfully migrated.
     */
    static int reanchor(List<CurrencyPair> oldBasePairs, CurrencyType oldBase, CurrencyType newBase, List<Date> orphanDatesOut) {

        int migratedDates = 0;

        var pairsByDate = oldBasePairs.stream().collect(Collectors.groupingBy(CurrencyPair::getDate));
        for (var entry : pairsByDate.entrySet()) {
            Date date = entry.getKey();
            List<CurrencyPair> pairsForDate = entry.getValue();

            CurrencyPair anchor = pairsForDate.stream()
                    .filter(pair -> newBase.equals(pair.getTo()))
                    .findFirst()
                    .orElse(null);

            if (anchor == null) {
                orphanDatesOut.add(date);
                continue;
            }

            BigDecimal usdPerOldBase = anchor.getValue();

            for (CurrencyPair pair : pairsForDate) {
                if (pair == anchor) {
                    continue;
                }
                pair.setFrom(newBase);
                pair.setValue(pair.getValue().divide(usdPerOldBase, SCALE, RoundingMode.HALF_UP));
            }

            // Repurpose the old-base->new-base anchor row into the missing new-base->old-base row instead of delete+insert.
            anchor.setFrom(newBase);
            anchor.setTo(oldBase);
            anchor.setValue(BigDecimal.ONE.divide(usdPerOldBase, SCALE, RoundingMode.HALF_UP));

            migratedDates++;
        }

        return migratedDates;
    }

    private Optional<CurrencyType> findByCode(EntityManager em, String code) {
        TypedQuery<CurrencyType> query = em.createNamedQuery("CurrencyType.getCurrencyTypeByCode", CurrencyType.class)
                .setParameter("code", code);
        return query.getResultList().stream().findFirst();
    }
}

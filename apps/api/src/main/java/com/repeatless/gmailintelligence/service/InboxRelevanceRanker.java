package com.repeatless.gmailintelligence.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Post-processes raw {@link GmailDataStore.InboxSearchHit} results from the DB
 * and produces a ranked list of {@link RankedHit} objects.
 *
 * Scoring model (all components are normalised to [0, 1]):
 *
 *  1. Keyword score   (weight 0.50) — fraction of query keywords found in subject+body
 *  2. Recency score   (weight 0.35) — exponential decay; emails from today score ~1.0,
 *                                      emails 30+ days old score ~0.0
 *  3. Category bonus  (weight 0.15) — 1.0 if the message category matches the intent,
 *                                      0.0 otherwise
 *
 * After scoring, results below {@link #MIN_SCORE} are discarded as irrelevant.
 * The remaining hits are returned sorted by score descending (highest first).
 */
@Component
public class InboxRelevanceRanker {

    /** Hits below this combined score are dropped as not relevant. */
    private static final double MIN_SCORE = 0.10;

    /** Recency half-life in days — score halves every HALF_LIFE_DAYS. */
    private static final double HALF_LIFE_DAYS = 7.0;

    // ── scoring weights (must sum to 1.0) ────────────────────────────────────
    private static final double W_KEYWORD  = 0.50;
    private static final double W_RECENCY  = 0.35;
    private static final double W_CATEGORY = 0.15;

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Scores and ranks a list of raw DB search hits.
     *
     * @param hits     raw hits from {@link GmailDataStore#searchInbox}
     * @param query    parsed query from {@link InboxQueryParser}
     * @param maxResults cap on results returned
     * @return ranked and filtered list, newest/most-relevant first
     */
    public List<RankedHit> rank(
            List<GmailDataStore.InboxSearchHit> hits,
            InboxQueryParser.InboxQuery query,
            int maxResults) {

        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        String expectedCategory = intentToCategory(query.intent());
        List<String> keywords   = lowerList(query.keywords());
        Instant now             = Instant.now();

        List<RankedHit> scored = new ArrayList<>(hits.size());
        for (GmailDataStore.InboxSearchHit hit : hits) {
            double kwScore  = keywordScore(hit, keywords);
            double recScore = recencyScore(hit.sentAt(), now);
            double catScore = categoryScore(hit.category(), expectedCategory);

            double total = W_KEYWORD * kwScore
                         + W_RECENCY * recScore
                         + W_CATEGORY * catScore;

            if (total >= MIN_SCORE) {
                scored.add(new RankedHit(hit, total, kwScore, recScore, catScore));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(RankedHit::score).reversed())
                .limit(maxResults)
                .toList();
    }

    // ─── individual scorers ───────────────────────────────────────────────────

    /**
     * Fraction of expanded keywords found in the message subject + body.
     * Returns 0.0 when no keywords are provided (every hit passes).
     */
    private double keywordScore(GmailDataStore.InboxSearchHit hit, List<String> keywords) {
        if (keywords.isEmpty()) return 1.0;

        String haystack = (
            nullSafe(hit.subject()) + " " + nullSafe(hit.bodyText()) + " " + nullSafe(hit.snippet())
        ).toLowerCase(Locale.ROOT);

        long matched = keywords.stream()
                .filter(kw -> haystack.contains(kw.toLowerCase(Locale.ROOT)))
                .count();

        return (double) matched / keywords.size();
    }

    /**
     * Exponential decay: score = 2^( -ageDays / HALF_LIFE_DAYS ).
     * Emails with no sentAt date receive 0.5 (neutral).
     */
    private double recencyScore(Instant sentAt, Instant now) {
        if (sentAt == null) return 0.5;
        double ageDays = ChronoUnit.HOURS.between(sentAt, now) / 24.0;
        if (ageDays < 0) ageDays = 0; // future-dated emails score 1.0
        return Math.pow(2.0, -ageDays / HALF_LIFE_DAYS);
    }

    /**
     * Returns 1.0 if the hit's stored category contains the expected category
     * string (case-insensitive), 0.0 otherwise.
     */
    private double categoryScore(String hitCategory, String expectedCategory) {
        if (expectedCategory == null || expectedCategory.isBlank()) return 0.5;
        if (hitCategory == null || hitCategory.isBlank()) return 0.0;
        return hitCategory.toUpperCase(Locale.ROOT).contains(expectedCategory) ? 1.0 : 0.0;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** Maps an InboxQueryParser intent to the category string stored in the DB. */
    private String intentToCategory(InboxQueryParser.Intent intent) {
        return switch (intent) {
            case JOB_SEARCH    -> "JOB";
            case FINANCE       -> "FINANCE";
            case NEWSLETTER    -> "NEWSLETTER";
            case NOTIFICATION  -> "NOTIFICATION";
            case WORK          -> "WORK";
            case GENERAL       -> null;
        };
    }

    private List<String> lowerList(List<String> list) {
        if (list == null) return List.of();
        return list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ─── value type ───────────────────────────────────────────────────────────

    /**
     * A scored wrapper around a raw {@link GmailDataStore.InboxSearchHit}.
     *
     * @param hit          the original DB hit
     * @param score        combined relevance score [0, 1]
     * @param keywordScore fraction of keywords matched
     * @param recencyScore recency decay value
     * @param categoryScore category match bonus
     */
    public record RankedHit(
            GmailDataStore.InboxSearchHit hit,
            double score,
            double keywordScore,
            double recencyScore,
            double categoryScore) {}
}

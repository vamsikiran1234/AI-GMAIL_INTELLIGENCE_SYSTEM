package com.repeatless.gmailintelligence.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Post-processes raw {@link GmailDataStore.InboxSearchHit} results from the DB
 * and produces a ranked list of {@link RankedHit} objects.
 *
 * --- Scoring model ---
 *
 * STEP 1 — Hard exclusion (before scoring):
 *   Classify the email into a subtype:
 *     OPPORTUNITY   — job posting, recruiter outreach, career opportunity
 *     STATUS        — application acknowledged / rejected / under review
 *     SECURITY      — OAuth notification, security alert, account notification
 *     UNRELATED     — clearly off-topic
 *   For JOB_SEARCH: STATUS and SECURITY subtypes are EXCLUDED (score = 0, dropped).
 *
 * STEP 2 — Positive scoring (all weights sum to 1.0):
 *   keyword score   (0.50) — fraction of scoringKeywords found in subject+body
 *   recency score   (0.35) — exponential decay, 7-day half-life
 *   category bonus  (0.15) — DB-stored category matches intent
 *
 * STEP 3 — Threshold:
 *   Combined score >= MIN_SCORE (0.30) to pass.
 *   Results sorted descending by score.
 */
@Component
public class InboxRelevanceRanker {

    /** Minimum combined score to include a hit in results. */
    private static final double MIN_SCORE = 0.30;

    /** Recency half-life in days — score halves every HALF_LIFE_DAYS. */
    private static final double HALF_LIFE_DAYS = 7.0;

    // ── scoring weights ───────────────────────────────────────────────────────
    private static final double W_KEYWORD  = 0.50;
    private static final double W_RECENCY  = 0.35;
    private static final double W_CATEGORY = 0.15;

    // ─── public API ───────────────────────────────────────────────────────────

    public List<RankedHit> rank(
            List<GmailDataStore.InboxSearchHit> hits,
            InboxQueryParser.InboxQuery query,
            int maxResults) {

        if (hits == null || hits.isEmpty()) return List.of();

        String expectedCategory = intentToCategory(query.intent());
        // Use scoringKeywords (tight/precise) for the scoring pass,
        // not the broad search keywords used for SQL recall.
        List<String> scoringKw = lowerList(query.scoringKeywords().isEmpty()
                ? query.keywords() : query.scoringKeywords());
        Instant now = Instant.now();

        List<RankedHit> scored = new ArrayList<>(hits.size());
        for (GmailDataStore.InboxSearchHit hit : hits) {

            // ── Step 1: classify subtype ──────────────────────────────────────
            EmailSubtype subtype = classifySubtype(hit);

            // ── Step 1b: hard-exclude irrelevant subtypes ─────────────────────
            if (query.isJobSearch()) {
                if (subtype == EmailSubtype.STATUS || subtype == EmailSubtype.SECURITY) {
                    continue; // hard exclude — do not score
                }
            }

            // ── Step 2: score ─────────────────────────────────────────────────
            double kwScore  = keywordScore(hit, scoringKw);
            double recScore = recencyScore(hit.sentAt(), now);
            double catScore = categoryScore(hit.category(), expectedCategory);

            double total = W_KEYWORD * kwScore
                         + W_RECENCY * recScore
                         + W_CATEGORY * catScore;

            // ── Step 3: threshold ─────────────────────────────────────────────
            if (total >= MIN_SCORE) {
                scored.add(new RankedHit(hit, subtype, total, kwScore, recScore, catScore));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(RankedHit::score).reversed())
                .limit(maxResults)
                .toList();
    }

    // ─── subtype classification ───────────────────────────────────────────────

    /**
     * Classifies an email into a semantic subtype based on subject + body signals.
     *
     * Checks negative signals FIRST — this ensures a rejection email that also
     * contains "developer" is still classified as STATUS, not OPPORTUNITY.
     */
    private EmailSubtype classifySubtype(GmailDataStore.InboxSearchHit hit) {
        String haystack = (
            nullSafe(hit.subject()) + " " + nullSafe(hit.snippet()) + " " +
            // Use only first 500 chars of body for classification to avoid false signals
            // buried in email footers/unsubscribe boilerplate
            truncateForClassification(nullSafe(hit.bodyText()))
        ).toLowerCase(Locale.ROOT);

        // Security/account signals — check before anything else
        if (containsAny(haystack,
                "oauth application", "third-party oauth", "authorized an oauth",
                "security alert", "sign-in attempt", "unusual sign",
                "suspicious activity", "account security", "verify your account",
                "confirm your email", "password reset", "2-factor",
                "two-factor", "authentication code", "login attempt",
                "new device", "unrecognized device")) {
            return EmailSubtype.SECURITY;
        }

        // Application status / rejection signals
        if (containsAny(haystack,
                "thank you for applying", "thank you for your application",
                "thank you for submitting your profile",
                "we have received your application",
                "your application has been received",
                "application has been submitted",
                "application is under review",
                "application under review",
                "we will be in touch",
                "will reach out if",
                "unfortunately", "we regret to inform",
                "not moving forward", "not proceeding",
                "will not be proceeding",
                "after careful consideration",
                "we have decided",
                "have decided not to",
                "at this time we",
                "does not meet our",
                "other candidates",
                "other applicants",
                "position has been filled",
                "wish you all the best",
                "we wish you the best",
                "future opportunities",
                "keep your resume on file",
                "application status",
                "application update",
                "update on your application",
                "update on position",
                "regarding your application",
                "your candidacy",
                "candidature")) {
            return EmailSubtype.STATUS;
        }

        // Job opportunity signals
        if (containsAny(haystack,
                "we are hiring", "we're hiring",
                "job opening", "job opportunity",
                "career opportunity", "exciting opportunity",
                "open position", "open role",
                "new position", "new role",
                "is looking for", "is seeking",
                "we are looking for", "we're looking for",
                "join our team", "join us",
                "you've been selected", "you have been selected",
                "shortlisted", "we found your profile",
                "your profile matches", "profile is a great fit",
                "great opportunity", "great role",
                "apply now", "apply here", "apply at",
                "job description", "jd:", "jd -",
                "responsibilities:", "requirements:",
                "qualifications:", "experience required",
                "ctc:", "lpa", "per annum",
                "work from home", "wfh", "hybrid", "onsite",
                "bangalore", "hyderabad", "chennai", "pune", "delhi",
                "remote", "location:",
                "recruiter", "talent acquisition", "hr team",
                "naukri", "linkedin", "indeed", "glassdoor",
                "careers@", "jobs@", "recruitment@", "hr@", "talent@")) {
            return EmailSubtype.OPPORTUNITY;
        }

        return EmailSubtype.UNRELATED;
    }

    // ─── scoring ──────────────────────────────────────────────────────────────

    /**
     * Fraction of scoringKeywords found in subject + body.
     * Uses the precise scoring keyword set, not the broad SQL search set.
     */
    private double keywordScore(GmailDataStore.InboxSearchHit hit, List<String> scoringKw) {
        if (scoringKw.isEmpty()) return 0.5; // neutral if no scoring keywords

        String haystack = (
            nullSafe(hit.subject()) + " " + nullSafe(hit.bodyText()) + " " + nullSafe(hit.snippet())
        ).toLowerCase(Locale.ROOT);

        long matched = scoringKw.stream()
                .filter(kw -> haystack.contains(kw.toLowerCase(Locale.ROOT)))
                .count();

        return (double) matched / scoringKw.size();
    }

    /** Exponential decay: score = 2^( -ageDays / HALF_LIFE_DAYS ). */
    private double recencyScore(Instant sentAt, Instant now) {
        if (sentAt == null) return 0.5;
        double ageDays = ChronoUnit.HOURS.between(sentAt, now) / 24.0;
        if (ageDays < 0) ageDays = 0;
        return Math.pow(2.0, -ageDays / HALF_LIFE_DAYS);
    }

    /** Returns 1.0 if hit's stored category matches the expected intent category. */
    private double categoryScore(String hitCategory, String expectedCategory) {
        if (expectedCategory == null || expectedCategory.isBlank()) return 0.5;
        if (hitCategory == null || hitCategory.isBlank()) return 0.0;
        return hitCategory.toUpperCase(Locale.ROOT).contains(expectedCategory) ? 1.0 : 0.0;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

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

    /**
     * Use only the first 500 characters of body text for subtype classification.
     * Email footers / unsubscribe text / legal boilerplate contains many false signals.
     */
    private String truncateForClassification(String body) {
        if (body == null || body.length() <= 500) return body == null ? "" : body;
        return body.substring(0, 500);
    }

    private List<String> lowerList(List<String> list) {
        if (list == null) return List.of();
        return list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) return true;
        }
        return false;
    }

    // ─── value types ──────────────────────────────────────────────────────────

    /**
     * Semantic subtype of an email, used for hard-exclusion decisions.
     *
     * OPPORTUNITY  — a new job posting, recruiter outreach, or career opening
     * STATUS       — application received / rejected / under review
     * SECURITY     — OAuth / account / security notification
     * UNRELATED    — nothing clearly job-related
     */
    public enum EmailSubtype {
        OPPORTUNITY, STATUS, SECURITY, UNRELATED
    }

    public record RankedHit(
            GmailDataStore.InboxSearchHit hit,
            EmailSubtype subtype,
            double score,
            double keywordScore,
            double recencyScore,
            double categoryScore) {}
}

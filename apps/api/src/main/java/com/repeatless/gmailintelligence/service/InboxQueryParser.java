package com.repeatless.gmailintelligence.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Converts a natural-language inbox question into a structured {@link InboxQuery}.
 *
 * Responsibilities:
 *  1. Detect the user's intent (job search, finance, newsletter, general, etc.)
 *  2. Extract raw keywords from the question
 *  3. Expand those keywords with role-synonyms and domain terms
 *     - searchKeywords  : broad set used for the SQL ILIKE query (recall-oriented)
 *     - scoringKeywords : tight set used by InboxRelevanceRanker (precision-oriented)
 *  4. Identify a date expression token so DateRangeResolver can convert it
 *  5. Identify a sender / company name when explicitly mentioned
 *
 * Key design decision — STATUS-SIGNAL WORDS ARE NOT SEARCH KEYWORDS:
 *   Words like "apply", "application", "interview", "offer" appear in both
 *   job-opportunity emails AND rejection/status/acknowledgement emails.
 *   Including them as search keywords causes irrelevant emails to match the
 *   DB query. They are intentionally excluded from searchKeywords and handled
 *   instead by InboxRelevanceRanker's negative-signal detection.
 */
@Component
public class InboxQueryParser {

    // ─── public API ───────────────────────────────────────────────────────────

    public InboxQuery parse(String question) {
        if (question == null || question.isBlank()) {
            return InboxQuery.general(question, List.of(), List.of());
        }
        String lower = question.toLowerCase(Locale.ROOT);

        Intent intent          = detectIntent(lower);
        String dateToken       = extractDateToken(lower);
        String sender          = extractSender(lower);
        List<String> searchKw  = buildSearchKeywords(lower, intent);
        List<String> scoringKw = buildScoringKeywords(lower, intent);

        return new InboxQuery(intent, searchKw, scoringKw, dateToken, sender, question);
    }

    // ─── intent detection ─────────────────────────────────────────────────────

    private Intent detectIntent(String text) {
        if (containsAny(text,
                "job", "jobs", "hiring", "hire", "hired",
                "recruitment", "recruiter", "opening", "openings",
                "position", "positions", "vacancy", "vacancies",
                "role", "roles", "career", "careers",
                "internship", "intern",
                "application", "apply", "applied",
                "interview", "offer letter", "offer",
                "jd", "job description",
                "full stack", "fullstack", "frontend", "backend",
                "software engineer", "software developer",
                "developer", "sde", "swe",
                "react", "node", "mern", "java developer", "python developer",
                "data engineer", "ml engineer", "devops")) {
            return Intent.JOB_SEARCH;
        }
        if (containsAny(text,
                "invoice", "receipt", "payment", "transaction",
                "bank", "refund", "subscription", "billing", "charge",
                "salary", "payroll", "expense")) {
            return Intent.FINANCE;
        }
        if (containsAny(text,
                "newsletter", "digest", "roundup", "weekly", "unsubscribe")) {
            return Intent.NEWSLETTER;
        }
        if (containsAny(text,
                "otp", "verification", "alert", "notification",
                "security", "login", "sign-in", "2fa", "confirm")) {
            return Intent.NOTIFICATION;
        }
        if (containsAny(text,
                "meeting", "project", "deadline", "client",
                "proposal", "report", "team")) {
            return Intent.WORK;
        }
        return Intent.GENERAL;
    }

    // ─── date token extraction ────────────────────────────────────────────────

    /**
     * Pulls the first recognisable date/time expression from the question text.
     *
     * Priority tiers (highest first):
     *   1. Explicit N-day windows       "last 3 days", "last 7 days", ...
     *   2. Named relative windows       "this week", "last week", "this month", ...
     *   3. Named calendar days          "yesterday", "today"
     *   4. Recency shorthand            "latest", "recent", "recently", "newest"
     *   5. Month + year                 "august 2026", "aug 2026"
     *   6. Bare month name              "august", "july"
     *   7. Bare 4-digit year            "2026"
     */
    private String extractDateToken(String text) {

        // Tier 1 — explicit N-day windows
        String[] nDayPatterns = {
            "last 30 days", "last 14 days", "last 7 days",
            "last 5 days",  "last 3 days",  "last 2 days", "last 1 day"
        };
        for (String p : nDayPatterns) {
            if (text.contains(p)) return p;
        }
        java.util.regex.Matcher nDayMatcher =
            java.util.regex.Pattern.compile("last\\s+\\d+\\s+days?").matcher(text);
        if (nDayMatcher.find()) return nDayMatcher.group();

        // Tier 2 — named relative windows
        String[] relativePatterns = {
            "this week", "last week",
            "this month", "last month",
            "this year", "last year"
        };
        for (String p : relativePatterns) {
            if (text.contains(p)) return p;
        }

        // Tier 3 — named calendar days
        if (text.contains("yesterday")) return "yesterday";
        if (text.contains("today"))     return "today";

        // Tier 4 — recency shorthand
        if (text.contains("latest"))   return "latest";
        if (text.contains("recent"))   return "recent";
        if (text.contains("recently")) return "recently";
        if (text.contains("newest"))   return "newest";

        // Tier 5 — "month year"
        java.util.regex.Matcher monthYearMatcher =
            java.util.regex.Pattern.compile(
                "(january|february|march|april|may|june|july|august|september|october|november|december"
                + "|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)"
                + "\\s+(\\d{4})"
            ).matcher(text);
        if (monthYearMatcher.find()) return monthYearMatcher.group();

        // Tier 6 — bare month name
        java.util.regex.Matcher monthOnlyMatcher =
            java.util.regex.Pattern.compile(
                "\\b(january|february|march|april|may|june|july|august"
                + "|september|october|november|december"
                + "|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\b"
            ).matcher(text);
        if (monthOnlyMatcher.find()) return monthOnlyMatcher.group();

        // Tier 7 — bare 4-digit year
        java.util.regex.Matcher yearMatcher =
            java.util.regex.Pattern.compile("\\b(20\\d{2})\\b").matcher(text);
        if (yearMatcher.find()) return yearMatcher.group();

        return null;
    }

    // ─── sender / company extraction ─────────────────────────────────────────

    private String extractSender(String text) {
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile(
                "(?:from|by|sent by|at)\\s+([a-z0-9 &._-]{2,30}?)(?:\\s|$|about|for|with|regarding)"
            ).matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (!containsAny(candidate, "me", "my", "the", "an", "a", "gmail", "inbox")) {
                return candidate;
            }
        }
        return null;
    }

    // ─── keyword building ─────────────────────────────────────────────────────

    /**
     * SEARCH keywords — broad set for the SQL ILIKE query.
     * These are used for recall: we want to pull in as many potentially relevant
     * emails as possible and let the ranker do precision filtering.
     *
     * EXCLUDED intentionally: "apply", "application", "interview", "offer" —
     * these are status-signal words that match rejection/acknowledgement emails.
     */
    private List<String> buildSearchKeywords(String text, Intent intent) {
        Set<String> keywords = new LinkedHashSet<>();

        // Add explicit non-stopword tokens from the question
        for (String token : text.split("[^a-z0-9.+#]+")) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)
                    && !STATUS_SIGNAL_WORDS.contains(token)) {
                keywords.add(token);
            }
        }

        switch (intent) {
            case JOB_SEARCH -> {
                keywords.addAll(JOB_SEARCH_TERMS);
                if (containsAny(text, "full stack", "fullstack")) {
                    keywords.addAll(FULL_STACK_TERMS);
                }
                if (containsAny(text, "react")) {
                    keywords.addAll(List.of("react", "reactjs", "frontend", "javascript", "typescript"));
                }
                if (containsAny(text, "node", "node.js")) {
                    keywords.addAll(List.of("node", "nodejs", "backend", "express", "javascript"));
                }
                if (containsAny(text, "java")) {
                    keywords.addAll(List.of("java", "spring", "springboot", "backend"));
                }
                if (containsAny(text, "python")) {
                    keywords.addAll(List.of("python", "django", "flask", "fastapi"));
                }
                if (containsAny(text, "data", "ml", "machine learning", "ai")) {
                    keywords.addAll(List.of("data engineer", "machine learning", "ml", "data science"));
                }
                if (containsAny(text, "intern", "internship")) {
                    keywords.addAll(List.of("intern", "internship", "trainee", "graduate"));
                }
                if (containsAny(text, "devops", "cloud", "aws", "gcp", "azure")) {
                    keywords.addAll(List.of("devops", "cloud", "aws", "gcp", "azure", "kubernetes", "docker"));
                }
            }
            case FINANCE ->
                keywords.addAll(List.of("invoice", "receipt", "payment", "transaction", "billing"));
            case NEWSLETTER ->
                keywords.addAll(List.of("newsletter", "digest", "roundup", "weekly", "unsubscribe"));
            case NOTIFICATION ->
                keywords.addAll(List.of("verification", "alert", "notification", "security", "confirm"));
            case WORK ->
                keywords.addAll(List.of("meeting", "project", "deadline", "client", "proposal"));
            case GENERAL -> { /* no expansion */ }
        }

        return new ArrayList<>(keywords);
    }

    /**
     * SCORING keywords — tight set used by InboxRelevanceRanker for precision scoring.
     * These are the core signals that a matching email should contain.
     * Fewer, more specific terms — high-confidence relevance signals only.
     */
    private List<String> buildScoringKeywords(String text, Intent intent) {
        Set<String> keywords = new LinkedHashSet<>();

        switch (intent) {
            case JOB_SEARCH -> {
                // Core opportunity signals
                keywords.addAll(List.of("hiring", "job opening", "job opportunity", "position",
                        "vacancy", "career", "recruiter", "we are hiring", "opportunity"));
                // Role keywords from the question
                if (containsAny(text, "full stack", "fullstack")) {
                    keywords.addAll(List.of("full stack", "full-stack", "fullstack",
                            "software engineer", "software developer", "web developer", "mern"));
                }
                if (containsAny(text, "react")) {
                    keywords.addAll(List.of("react", "frontend", "javascript"));
                }
                if (containsAny(text, "node", "node.js")) {
                    keywords.addAll(List.of("node", "backend", "javascript"));
                }
                if (containsAny(text, "java")) {
                    keywords.addAll(List.of("java developer", "java engineer", "spring"));
                }
                if (containsAny(text, "python")) {
                    keywords.addAll(List.of("python developer", "python engineer"));
                }
                if (containsAny(text, "intern", "internship")) {
                    keywords.addAll(List.of("intern", "internship", "graduate"));
                }
                if (containsAny(text, "devops")) {
                    keywords.addAll(List.of("devops", "cloud engineer", "sre"));
                }
                // If no specific role, use generic developer/engineer terms
                if (keywords.stream().noneMatch(k -> k.contains("engineer") || k.contains("developer"))) {
                    keywords.addAll(List.of("developer", "engineer", "software"));
                }
            }
            case FINANCE ->
                keywords.addAll(List.of("invoice", "payment", "receipt", "transaction"));
            case NEWSLETTER ->
                keywords.addAll(List.of("newsletter", "digest", "weekly"));
            case NOTIFICATION ->
                keywords.addAll(List.of("alert", "verification", "notification"));
            case WORK ->
                keywords.addAll(List.of("meeting", "project", "deadline"));
            case GENERAL -> { /* no scoring expansion — score on raw question tokens */ }
        }

        return new ArrayList<>(keywords);
    }

    // ─── constants ────────────────────────────────────────────────────────────

    /**
     * Words excluded from search keywords because they appear in BOTH job opportunity
     * emails AND application-status/rejection emails, causing false positives.
     */
    static final Set<String> STATUS_SIGNAL_WORDS = Set.of(
        "apply", "applied", "application",
        "interview", "interviewed",
        "offer", "offered",
        "status", "update", "regarding"
    );

    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "are", "was", "were", "has", "have", "had",
        "from", "with", "this", "that", "they", "them", "their",
        "what", "when", "where", "who", "how", "why",
        "show", "find", "get", "give", "tell", "list",
        "can", "did", "does", "any", "all", "some",
        "email", "emails", "mail", "inbox",
        "latest", "recent", "today", "yesterday", "last", "week",
        "month", "year", "days", "new", "old"
    );

    /**
     * Broad search terms for SQL recall — no status-signal words.
     */
    private static final List<String> JOB_SEARCH_TERMS = List.of(
        "hiring", "recruitment", "job", "jobs", "opening", "position",
        "vacancy", "career", "opportunity", "recruiter",
        "developer", "engineer", "software"
    );

    private static final List<String> FULL_STACK_TERMS = List.of(
        "full stack", "full-stack", "fullstack",
        "software engineer", "software developer",
        "frontend", "backend", "web developer",
        "mern", "mean", "react", "node", "javascript", "typescript",
        "sde", "swe"
    );

    // ─── helpers ──────────────────────────────────────────────────────────────

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) return true;
        }
        return false;
    }

    // ─── types ────────────────────────────────────────────────────────────────

    public enum Intent {
        JOB_SEARCH, FINANCE, NEWSLETTER, NOTIFICATION, WORK, GENERAL
    }

    /**
     * Structured representation of a parsed inbox search request.
     *
     * @param intent         what the user is looking for
     * @param keywords       broad search keywords for SQL ILIKE (recall-oriented)
     * @param scoringKeywords tight keywords for relevance scoring (precision-oriented)
     * @param dateToken      raw date expression extracted from the question (may be null)
     * @param sender         company / sender filter (may be null)
     * @param raw            the original question text
     */
    public record InboxQuery(
        Intent intent,
        List<String> keywords,
        List<String> scoringKeywords,
        String dateToken,
        String sender,
        String raw
    ) {
        static InboxQuery general(String raw, List<String> keywords, List<String> scoringKeywords) {
            return new InboxQuery(Intent.GENERAL, keywords, scoringKeywords, null, null, raw);
        }

        public boolean hasDateFilter()  { return dateToken != null; }
        public boolean isJobSearch()    { return intent == Intent.JOB_SEARCH; }
        public boolean isFinance()      { return intent == Intent.FINANCE; }
        public boolean isNewsletter()   { return intent == Intent.NEWSLETTER; }
    }
}

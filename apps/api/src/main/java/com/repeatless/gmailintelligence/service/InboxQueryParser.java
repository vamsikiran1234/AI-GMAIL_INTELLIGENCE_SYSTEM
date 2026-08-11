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
 *  2. Extract raw keywords mentioned in the question
 *  3. Expand those keywords with role-synonyms and domain terms
 *  4. Identify a date expression token so DateRangeResolver can convert it
 *  5. Identify a sender / company name when explicitly mentioned
 */
@Component
public class InboxQueryParser {

    // ─── public API ───────────────────────────────────────────────────────────

    public InboxQuery parse(String question) {
        if (question == null || question.isBlank()) {
            return InboxQuery.general(question, List.of());
        }
        String lower = question.toLowerCase(Locale.ROOT);

        Intent intent = detectIntent(lower);
        String dateToken = extractDateToken(lower);
        String sender   = extractSender(lower);
        List<String> keywords = expandKeywords(lower, intent);

        return new InboxQuery(intent, keywords, dateToken, sender, question);
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
     * Pulls the first recognisable date/time expression out of the question.
     * The raw token is handed to {@link DateRangeResolver} for conversion.
     */
    private String extractDateToken(String text) {
        // Ordered from most-specific to least-specific so we match the longest phrase first
        String[] patterns = {
            "last 3 days", "last 7 days", "last 30 days",
            "this week", "last week",
            "this month", "last month",
            "this year", "last year",
            "yesterday", "today",
            "latest", "recent", "recently", "newest",
            "january","february","march","april","may","june",
            "july","august","september","october","november","december"
        };
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return pattern;
            }
        }
        // Year like "2025" or "2026"
        java.util.regex.Matcher yearMatcher =
            java.util.regex.Pattern.compile("\\b(202[0-9])\\b").matcher(text);
        if (yearMatcher.find()) {
            return yearMatcher.group(1);
        }
        return null; // no date expression found — caller decides default
    }

    // ─── sender / company extraction ─────────────────────────────────────────

    /**
     * Looks for common sender-qualification phrases like "from Accenture" or
     * "by Google" and returns the company/sender token.
     */
    private String extractSender(String text) {
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile(
                "(?:from|by|sent by|at)\\s+([a-z0-9 &._-]{2,30}?)(?:\\s|$|about|for|with|regarding)"
            ).matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            // reject generic words that aren't company names
            if (!containsAny(candidate, "me", "my", "the", "an", "a", "gmail", "inbox")) {
                return candidate;
            }
        }
        return null;
    }

    // ─── keyword expansion ────────────────────────────────────────────────────

    /**
     * Takes the raw question text and the detected intent, returns a de-duplicated
     * ordered list of keywords to use in the DB keyword search.
     */
    private List<String> expandKeywords(String text, Intent intent) {
        Set<String> keywords = new LinkedHashSet<>();

        // Always add explicit words from the question (tokens ≥ 3 chars, not stopwords)
        for (String token : text.split("[^a-z0-9.+#]+")) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                keywords.add(token);
            }
        }

        // Add domain synonyms based on detected intent
        switch (intent) {
            case JOB_SEARCH -> {
                keywords.addAll(JOB_CORE_TERMS);
                // Role-specific expansions
                if (containsAny(text, "full stack", "fullstack")) {
                    keywords.addAll(FULL_STACK_TERMS);
                }
                if (containsAny(text, "react")) {
                    keywords.addAll(List.of("react", "reactjs", "react.js", "frontend", "javascript", "typescript"));
                }
                if (containsAny(text, "node", "node.js")) {
                    keywords.addAll(List.of("node", "nodejs", "node.js", "backend", "express", "javascript"));
                }
                if (containsAny(text, "java")) {
                    keywords.addAll(List.of("java", "spring", "springboot", "backend", "jvm"));
                }
                if (containsAny(text, "python")) {
                    keywords.addAll(List.of("python", "django", "flask", "fastapi", "data"));
                }
                if (containsAny(text, "data engineer", "data science", "ml", "machine learning", "ai")) {
                    keywords.addAll(List.of("data engineer", "machine learning", "ml engineer", "ai", "data science", "analytics"));
                }
                if (containsAny(text, "intern", "internship")) {
                    keywords.addAll(List.of("intern", "internship", "trainee", "graduate"));
                }
                if (containsAny(text, "devops", "cloud", "aws", "gcp", "azure")) {
                    keywords.addAll(List.of("devops", "cloud", "aws", "gcp", "azure", "kubernetes", "docker"));
                }
            }
            case FINANCE ->
                keywords.addAll(List.of("invoice", "receipt", "payment", "transaction", "billing", "subscription"));
            case NEWSLETTER ->
                keywords.addAll(List.of("newsletter", "digest", "roundup", "weekly", "unsubscribe"));
            case NOTIFICATION ->
                keywords.addAll(List.of("otp", "verification", "alert", "notification", "security", "confirm"));
            case WORK ->
                keywords.addAll(List.of("meeting", "project", "deadline", "client", "proposal"));
            case GENERAL -> { /* no expansion */ }
        }

        return new ArrayList<>(keywords);
    }

    // ─── constants ────────────────────────────────────────────────────────────

    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "are", "was", "were", "has", "have", "had",
        "from", "with", "this", "that", "they", "them", "their",
        "what", "when", "where", "who", "how", "why",
        "show", "find", "get", "give", "tell", "list",
        "can", "did", "does", "any", "all", "some",
        "email", "emails", "mail", "inbox", "latest",
        "recent", "today", "yesterday", "last", "this", "week",
        "month", "year", "days", "new", "old"
    );

    private static final List<String> JOB_CORE_TERMS = List.of(
        "hiring", "recruitment", "job", "jobs", "opening", "position",
        "vacancy", "career", "opportunity", "offer", "interview",
        "apply", "application", "jd", "job description",
        "developer", "engineer", "software"
    );

    private static final List<String> FULL_STACK_TERMS = List.of(
        "full stack", "full-stack", "fullstack",
        "software engineer", "software developer",
        "frontend", "backend", "web developer",
        "mern", "mean", "react", "node", "javascript", "typescript",
        "sde", "swe", "engineering"
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
     * @param intent    what the user is looking for
     * @param keywords  expanded keyword list for DB search
     * @param dateToken raw date expression extracted from the question (may be null)
     * @param sender    company / sender filter extracted from question (may be null)
     * @param raw       the original question text
     */
    public record InboxQuery(
        Intent intent,
        List<String> keywords,
        String dateToken,
        String sender,
        String raw
    ) {
        static InboxQuery general(String raw, List<String> keywords) {
            return new InboxQuery(Intent.GENERAL, keywords, null, null, raw);
        }

        /** True when the query has a date constraint that should filter results. */
        public boolean hasDateFilter() { return dateToken != null; }

        /** True when the query should restrict to a specific category. */
        public boolean isJobSearch()   { return intent == Intent.JOB_SEARCH; }
        public boolean isFinance()     { return intent == Intent.FINANCE; }
        public boolean isNewsletter()  { return intent == Intent.NEWSLETTER; }
    }
}

package com.repeatless.gmailintelligence.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Converts a natural-language date token (extracted by {@link InboxQueryParser})
 * into a concrete {@link DateRange} of two {@link Instant} values.
 *
 * All calculations are relative to the runtime clock — no dates are hardcoded.
 *
 * Supported expressions:
 *   latest / recent / recently / newest  → last 3 days
 *   today                                → today 00:00 → now
 *   yesterday                            → yesterday 00:00 → yesterday 23:59:59
 *   last 3 days / last N days            → now - N days → now
 *   last 7 days / last week              → now - 7 days → now
 *   this week                            → Monday 00:00 → now
 *   last month                           → first day of previous month → last day
 *   this month                           → first day of current month → now
 *   this year                            → Jan 1 current year → now
 *   last year                            → Jan 1 previous year → Dec 31 previous year
 *   january … december (current year)    → full calendar month
 *   january 2026 / aug 2026              → full named month of that year
 *   2026 (year only)                     → full calendar year
 *   (null / unrecognised)                → last 30 days (safe default)
 */
@Component
public class DateRangeResolver {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Default window used when no date expression is recognised. */
    private static final int DEFAULT_DAYS = 7;

    /** Window used for "latest / recent / newest". */
    private static final int LATEST_DAYS = 3;

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Resolves a raw date token to a {@link DateRange}.
     *
     * @param token the string extracted by {@link InboxQueryParser#extractDateToken},
     *              may be {@code null}
     * @return a non-null DateRange with concrete from/to Instants
     */
    public DateRange resolve(String token) {
        if (token == null || token.isBlank()) {
            return lastNDays(DEFAULT_DAYS);
        }

        String t = token.toLowerCase(Locale.ROOT).trim();

        // ── relative recency ──────────────────────────────────────────────────
        if (containsAny(t, "latest", "recent", "recently", "newest")) {
            return lastNDays(LATEST_DAYS);
        }
        if (t.equals("today")) {
            return today();
        }
        if (t.equals("yesterday")) {
            return yesterday();
        }

        // ── "last N days" ─────────────────────────────────────────────────────
        Matcher lastNDaysMatcher = Pattern.compile("last\\s+(\\d+)\\s+days?").matcher(t);
        if (lastNDaysMatcher.find()) {
            return lastNDays(Integer.parseInt(lastNDaysMatcher.group(1)));
        }

        // ── week ──────────────────────────────────────────────────────────────
        if (t.equals("this week")) {
            return thisWeek();
        }
        if (t.equals("last week") || t.equals("last 7 days")) {
            return lastNDays(7);
        }

        // ── month ─────────────────────────────────────────────────────────────
        if (t.equals("this month")) {
            return thisMonth();
        }
        if (t.equals("last month")) {
            return lastMonth();
        }

        // ── year ──────────────────────────────────────────────────────────────
        if (t.equals("this year")) {
            return thisYear();
        }
        if (t.equals("last year")) {
            return lastYear();
        }

        // ── "month year" e.g. "august 2026", "aug 2026" ──────────────────────
        Matcher monthYearMatcher =
            Pattern.compile("(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                          + "jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{4})")
                   .matcher(t);
        if (monthYearMatcher.find()) {
            Month month = parseMonth(monthYearMatcher.group(1));
            int year = Integer.parseInt(monthYearMatcher.group(2));
            return fullMonth(year, month);
        }

        // ── bare month name (assumes current year) e.g. "august", "july" ─────
        Matcher monthOnlyMatcher =
            Pattern.compile("^(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                          + "jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)$")
                   .matcher(t);
        if (monthOnlyMatcher.find()) {
            Month month = parseMonth(monthOnlyMatcher.group(1));
            int year = LocalDate.now(ZONE).getYear();
            return fullMonth(year, month);
        }

        // ── bare 4-digit year e.g. "2026" ────────────────────────────────────
        Matcher yearMatcher = Pattern.compile("^(\\d{4})$").matcher(t);
        if (yearMatcher.find()) {
            int year = Integer.parseInt(yearMatcher.group(1));
            return fullYear(year);
        }

        // ── fallback: unrecognised token → last 30 days ───────────────────────
        return lastNDays(DEFAULT_DAYS);
    }

    // ─── range builders ───────────────────────────────────────────────────────

    private DateRange today() {
        ZonedDateTime startOfDay = LocalDate.now(ZONE).atStartOfDay(ZONE);
        return new DateRange(startOfDay.toInstant(), Instant.now());
    }

    private DateRange yesterday() {
        LocalDate yd = LocalDate.now(ZONE).minusDays(1);
        Instant from = yd.atStartOfDay(ZONE).toInstant();
        Instant to   = yd.atTime(23, 59, 59).atZone(ZONE).toInstant();
        return new DateRange(from, to);
    }

    private DateRange lastNDays(int n) {
        Instant now  = Instant.now();
        Instant from = ZonedDateTime.now(ZONE).minusDays(n).toInstant();
        return new DateRange(from, now);
    }

    private DateRange thisWeek() {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new DateRange(monday.atStartOfDay(ZONE).toInstant(), Instant.now());
    }

    private DateRange thisMonth() {
        LocalDate first = LocalDate.now(ZONE).withDayOfMonth(1);
        return new DateRange(first.atStartOfDay(ZONE).toInstant(), Instant.now());
    }

    private DateRange lastMonth() {
        YearMonth prev  = YearMonth.now(ZONE).minusMonths(1);
        Instant   from  = prev.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant   to    = prev.atEndOfMonth().atTime(23, 59, 59).atZone(ZONE).toInstant();
        return new DateRange(from, to);
    }

    private DateRange thisYear() {
        LocalDate first = LocalDate.now(ZONE).withDayOfYear(1);
        return new DateRange(first.atStartOfDay(ZONE).toInstant(), Instant.now());
    }

    private DateRange lastYear() {
        int prev       = LocalDate.now(ZONE).getYear() - 1;
        Instant from   = LocalDate.of(prev, 1,  1).atStartOfDay(ZONE).toInstant();
        Instant to     = LocalDate.of(prev, 12, 31).atTime(23, 59, 59).atZone(ZONE).toInstant();
        return new DateRange(from, to);
    }

    private DateRange fullMonth(int year, Month month) {
        YearMonth ym   = YearMonth.of(year, month);
        Instant   from = ym.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant   to   = ym.atEndOfMonth().atTime(23, 59, 59).atZone(ZONE).toInstant();
        return new DateRange(from, to);
    }

    private DateRange fullYear(int year) {
        Instant from = LocalDate.of(year, 1,  1).atStartOfDay(ZONE).toInstant();
        Instant to   = LocalDate.of(year, 12, 31).atTime(23, 59, 59).atZone(ZONE).toInstant();
        return new DateRange(from, to);
    }

    // ─── month name parser ────────────────────────────────────────────────────

    private Month parseMonth(String abbr) {
        return switch (abbr.substring(0, 3).toLowerCase(Locale.ROOT)) {
            case "jan" -> Month.JANUARY;
            case "feb" -> Month.FEBRUARY;
            case "mar" -> Month.MARCH;
            case "apr" -> Month.APRIL;
            case "may" -> Month.MAY;
            case "jun" -> Month.JUNE;
            case "jul" -> Month.JULY;
            case "aug" -> Month.AUGUST;
            case "sep" -> Month.SEPTEMBER;
            case "oct" -> Month.OCTOBER;
            case "nov" -> Month.NOVEMBER;
            case "dec" -> Month.DECEMBER;
            default    -> Month.JANUARY;
        };
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) return true;
        }
        return false;
    }

    // ─── value type ──────────────────────────────────────────────────────────

    /**
     * A concrete date range with inclusive {@code from} and {@code to} Instants.
     */
    public record DateRange(Instant from, Instant to) {
        /** Human-readable label used in AI prompts. */
        public String label() {
            java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                    .withZone(ZoneId.systemDefault());
            return fmt.format(from) + " → " + fmt.format(to);
        }
    }
}

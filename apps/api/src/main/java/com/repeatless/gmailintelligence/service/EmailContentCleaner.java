package com.repeatless.gmailintelligence.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Converts raw email body content (plain text or HTML) into clean, readable text
 * suitable for AI evidence bundles and user-facing citation snippets.
 *
 * Design goals:
 *  - No external HTML-parsing library required (pure Java regex approach).
 *  - Preserves meaningful content: headings, paragraphs, list items, links.
 *  - Removes non-content elements: style, script, head, meta, tracking pixels.
 *  - Decodes common HTML entities.
 *  - Normalises whitespace so the output is compact and readable.
 *  - Idempotent — safe to call on already-plain text.
 */
@Component
public class EmailContentCleaner {

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Returns the best available clean plain text from a message.
     *
     * Priority:
     *  1. If {@code bodyText} is non-empty and does NOT look like HTML → use it directly
     *     after whitespace normalisation.
     *  2. If {@code bodyText} looks like HTML (starts with {@code <}) → convert it.
     *  3. If {@code bodyText} is empty/null but {@code bodyHtml} is available → convert HTML.
     *  4. Fall back to empty string.
     *
     * @param bodyText raw text/plain MIME part (may be empty or HTML)
     * @param bodyHtml raw text/html MIME part (may be null/empty)
     * @return clean plain text, never null
     */
    public String extractCleanText(String bodyText, String bodyHtml) {
        // Case 1: genuine plain text
        if (bodyText != null && !bodyText.isBlank() && !looksLikeHtml(bodyText)) {
            return normaliseWhitespace(bodyText);
        }
        // Case 2: text/plain part actually contains HTML
        if (bodyText != null && !bodyText.isBlank() && looksLikeHtml(bodyText)) {
            return htmlToText(bodyText);
        }
        // Case 3: use the HTML body
        if (bodyHtml != null && !bodyHtml.isBlank()) {
            return htmlToText(bodyHtml);
        }
        return "";
    }

    /**
     * Converts an HTML string to clean readable plain text.
     *
     * Pipeline:
     *  1. Remove entire tag blocks that carry no readable content
     *     (style, script, head, noscript, svg, template).
     *  2. Convert block-level elements to newlines so paragraph structure is preserved.
     *  3. Extract link text (keep href for apply/careers links).
     *  4. Strip all remaining tags.
     *  5. Decode HTML entities.
     *  6. Normalise whitespace.
     *
     * @param html raw HTML string
     * @return clean plain text
     */
    public String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";

        String result = html;

        // ── Step 1: remove entire non-content tag blocks ──────────────────────
        result = removeTagBlock(result, "style");
        result = removeTagBlock(result, "script");
        result = removeTagBlock(result, "head");
        result = removeTagBlock(result, "noscript");
        result = removeTagBlock(result, "svg");
        result = removeTagBlock(result, "template");

        // Remove hidden elements (display:none, visibility:hidden)
        result = result.replaceAll("(?is)<[^>]+(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)[^>]*>.*?</[^>]+>", " ");

        // ── Step 2: block elements → newlines ─────────────────────────────────
        // Headings get double newline for visual separation
        result = result.replaceAll("(?i)<h[1-6][^>]*>", "\n\n");
        result = result.replaceAll("(?i)</h[1-6]>", "\n");
        // Paragraphs and divs
        result = result.replaceAll("(?i)<(?:p|div|section|article|header|footer|main)[^>]*>", "\n");
        result = result.replaceAll("(?i)</(?:p|div|section|article|header|footer|main)>", "\n");
        // List items
        result = result.replaceAll("(?i)<li[^>]*>", "\n• ");
        result = result.replaceAll("(?i)</li>", "");
        // Line breaks
        result = result.replaceAll("(?i)<br\\s*/?>", "\n");
        result = result.replaceAll("(?i)<tr[^>]*>", "\n");
        // Horizontal rules
        result = result.replaceAll("(?i)<hr\\s*/?>", "\n---\n");

        // ── Step 3: links — keep meaningful link text ─────────────────────────
        // For "apply now" / "careers" links, keep the text only (no raw URL dump)
        result = result.replaceAll("(?is)<a[^>]*>([^<]{1,80})</a>", "$1");
        // Remove empty or icon-only anchor tags
        result = result.replaceAll("(?is)<a[^>]*>\\s*</a>", "");

        // ── Step 4: strip all remaining tags ──────────────────────────────────
        result = result.replaceAll("<[^>]+>", "");

        // ── Step 5: decode HTML entities ──────────────────────────────────────
        result = decodeEntities(result);

        // ── Step 6: normalise whitespace ──────────────────────────────────────
        return normaliseWhitespace(result);
    }

    /**
     * Produces a short, clean snippet (max {@code maxLength} chars) suitable for
     * citation cards. Strips HTML, decodes entities, collapses whitespace.
     *
     * @param raw       raw text — may be HTML, plain text, or a mix
     * @param maxLength maximum output length
     * @return clean snippet, never null
     */
    public String toSnippet(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) return "";
        String clean = looksLikeHtml(raw) ? htmlToText(raw) : normaliseWhitespace(raw);
        // A snippet is single-line — collapse newlines to spaces
        clean = clean.replace('\n', ' ').replaceAll("\\s{2,}", " ").strip();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength - 1) + "…";
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    /**
     * Returns true if the string appears to be HTML rather than plain text.
     * Checks for leading/common HTML markers.
     */
    private boolean looksLikeHtml(String text) {
        if (text == null) return false;
        String trimmed = text.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("<html")
                || trimmed.startsWith("<!doctype")
                || trimmed.startsWith("<div")
                || trimmed.startsWith("<table")
                || trimmed.startsWith("<body")
                // Plain text that's >20% tag characters is probably HTML
                || (text.length() > 100 && countChar(text, '<') > text.length() / 5);
    }

    /**
     * Removes a complete HTML tag block, including its content.
     * E.g. removeTagBlock(html, "style") removes {@code <style>...</style>}.
     */
    private String removeTagBlock(String html, String tagName) {
        return html.replaceAll("(?is)<" + tagName + "[^>]*>.*?</" + tagName + ">", " ");
    }

    /**
     * Decodes the most common HTML entities to their plain-text equivalents.
     */
    String decodeEntities(String text) {
        if (text == null) return "";
        // Named entities first
        text = text
            .replace("&amp;",   "&")
            .replace("&lt;",    "<")
            .replace("&gt;",    ">")
            .replace("&quot;",  "\"")
            .replace("&#39;",   "'")
            .replace("&apos;",  "'")
            .replace("&nbsp;",  " ")
            .replace("&ndash;", "-")
            .replace("&mdash;", "-")
            .replace("&laquo;", "\"")
            .replace("&raquo;", "\"")
            .replace("&bull;",  "•")
            .replace("&middot;","·")
            .replace("&copy;",  "(c)")
            .replace("&reg;",   "(r)")
            .replace("&trade;", "(tm)")
            .replace("&hellip;","...")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"");
        // Numeric decimal entities  e.g. &#44; → ','
        Matcher decimalMatcher = Pattern.compile("&#(\\d+);").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (decimalMatcher.find()) {
            int code = Integer.parseInt(decimalMatcher.group(1));
            decimalMatcher.appendReplacement(sb,
                    Matcher.quoteReplacement(code < 0x10000
                            ? String.valueOf((char) code)
                            : new String(Character.toChars(code))));
        }
        decimalMatcher.appendTail(sb);
        text = sb.toString();
        // Numeric hex entities  e.g. &#x2F; → '/'
        Matcher hexMatcher = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(text);
        StringBuffer sb2 = new StringBuffer();
        while (hexMatcher.find()) {
            int code = Integer.parseInt(hexMatcher.group(1), 16);
            hexMatcher.appendReplacement(sb2,
                    Matcher.quoteReplacement(code < 0x10000
                            ? String.valueOf((char) code)
                            : new String(Character.toChars(code))));
        }
        hexMatcher.appendTail(sb2);
        return sb2.toString();
    }

    /**
     * Collapses multiple blank lines to a single blank line,
     * trims each line, and removes leading/trailing whitespace.
     */
    private String normaliseWhitespace(String text) {
        if (text == null) return "";
        // Replace all \r with nothing
        text = text.replace("\r", "");
        // Collapse runs of spaces/tabs within a line to a single space
        text = text.replaceAll("[ \t]+", " ");
        // Trim each line
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder(text.length());
        int consecutiveBlanks = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                consecutiveBlanks++;
                if (consecutiveBlanks <= 1) sb.append('\n');
            } else {
                consecutiveBlanks = 0;
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}

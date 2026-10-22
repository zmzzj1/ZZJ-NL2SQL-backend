package com.queryforge.domain.report;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 报告 HTML 的安全净化：只允许白名单标签与属性，转义用户/模型内容，
 * 强制移除 <script>/<style>/事件属性/外部资源/危险链接。默认不允许脚本、外链、外图。
 */
public final class ReportHtmlSanitizer {

    private ReportHtmlSanitizer() {
    }

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "h1", "h2", "h3", "h4", "p", "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td",
            "strong", "em", "code", "pre", "span", "div", "br", "hr");

    private static final Set<String> ALLOWED_ATTRS = Set.of("class");

    private static final Pattern TAG_PATTERN =
            Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)([^>]*)>", Pattern.DOTALL);

    /** 净化任意不可信 HTML；不含白名单标签的内容一律按纯文本转义。 */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(html.length());
        int last = 0;
        var matcher = TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            result.append(escape(html.substring(last, matcher.start())));
            boolean closing = matcher.group().startsWith("</");
            String tag = matcher.group(1).toLowerCase();
            String attrs = matcher.group(2);
            if (ALLOWED_TAGS.contains(tag)) {
                if (closing) {
                    result.append("</").append(tag).append('>');
                } else {
                    result.append('<').append(tag).append(sanitizeAttrs(attrs)).append('>');
                }
            } else {
                // 危险/未知标签整体丢弃，内容继续保留（已在上方转义）。
            }
            last = matcher.end();
        }
        result.append(escape(html.substring(last)));
        return result.toString();
    }

    /** 只保留白名单属性值（严格转义），丢弃事件处理器与 src/href/style。 */
    private static String sanitizeAttrs(String attrs) {
        StringBuilder result = new StringBuilder();
        for (String token : attrs.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            int eq = token.indexOf('=');
            String name = (eq < 0 ? token : token.substring(0, eq)).toLowerCase();
            if (!ALLOWED_ATTRS.contains(name)) {
                continue;
            }
            String value = eq < 0 ? "" : token.substring(eq + 1).replaceAll("^['\"]|['\"]$", "");
            result.append(' ').append(name).append("=\"").append(escape(value)).append('"');
        }
        return result.toString();
    }

    private static String escape(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

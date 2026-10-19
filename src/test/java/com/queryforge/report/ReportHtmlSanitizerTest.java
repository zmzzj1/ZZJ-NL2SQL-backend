package com.queryforge.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报告 HTML 净化")
class ReportHtmlSanitizerTest {

    @Test
    @DisplayName("移除 script 标签")
    void sanitize_shouldRemoveScript() {
        String html = "<h1>标题</h1><script>alert('x')</script><p>内容</p>";
        String result = ReportHtmlSanitizer.sanitize(html);
        // 关键：script 标签被移除，脚本不会执行；标签间文本作为已转义纯文本保留是无害的。
        assertThat(result).doesNotContain("<script").doesNotContain("</script>");
        assertThat(result).contains("<h1>").contains("<p>");
    }

    @Test
    @DisplayName("移除事件属性与外链")
    void sanitize_shouldRemoveEventHandlersAndLinks() {
        String html = "<a href=\"javascript:alert(1)\">点我</a><img src=\"http://evil/x.png\" onerror=\"alert(1)\">";
        String result = ReportHtmlSanitizer.sanitize(html);
        assertThat(result).doesNotContain("javascript:").doesNotContain("onerror").doesNotContain("http://evil");
    }

    @Test
    @DisplayName("转义 HTML 特殊字符")
    void sanitize_shouldEscapeSpecialChars() {
        // 尖括号与 & 会被转义，防止注入
        assertThat(ReportHtmlSanitizer.sanitize("<p>a & b</p>"))
                .contains("a &amp; b");
        assertThat(ReportHtmlSanitizer.sanitize("<p>1 < 2</p>"))
                .contains("1 &lt; 2");
    }

    @Test
    @DisplayName("保留白名单标签")
    void sanitize_shouldKeepAllowedTags() {
        String html = "<h1>标题</h1><table><tr><td>1</td></tr></table>";
        String result = ReportHtmlSanitizer.sanitize(html);
        assertThat(result).contains("<h1>").contains("<table>").contains("<td>1</td>");
    }

    @Test
    @DisplayName("空输入返回空字符串")
    void sanitize_shouldReturnEmptyForBlank() {
        assertThat(ReportHtmlSanitizer.sanitize(null)).isEmpty();
        assertThat(ReportHtmlSanitizer.sanitize("   ")).isEmpty();
    }
}

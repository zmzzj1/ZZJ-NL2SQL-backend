package com.queryforge.agent.context;

/**
 * Token 估算器。
 *
 * <p>明确声明：本估算基于字符数近似（中英混排 ~4 字符/token），不是精确 tokenizer 输出。
 * 指南 §7.2 / P1126 要求“未知/估算不伪造准确供应商值”——本类只产生估算值，调用方须如实标注为估算。
 */
public final class TokenEstimator {

    private final int charsPerToken;

    public TokenEstimator(int charsPerToken) {
        this.charsPerToken = charsPerToken <= 0 ? 4 : charsPerToken;
    }

    public static TokenEstimator defaultEstimator() {
        return new TokenEstimator(4);
    }

    /** 文本 token 估算（向上取整，至少 1）。 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / (double) charsPerToken);
    }

    /** 多个片段的总估算。 */
    public int estimate(Iterable<ContextSegment> segments) {
        int total = 0;
        for (ContextSegment segment : segments) {
            total += estimate(segment.text());
        }
        return total;
    }
}

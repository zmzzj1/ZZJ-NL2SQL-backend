package com.zzj.common.admin;

import java.io.Serializable;

/**
 * P13-M3A：用户分页查询条件。
 *
 * <p>分页上限在契约层强制：调用方与 Provider 共用同一约束，避免任一侧被超大 size 拖垮。
 * keyword 只做去空白，不参与任何 SQL 拼接（Provider 侧一律用参数绑定）。
 */
public final class AdminUserQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 单页最大条数：契约层硬上限，超过即收敛。 */
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;
    /** 关键词最大长度：防止异常输入被带到数据库查询。 */
    public static final int MAX_KEYWORD_LENGTH = 64;

    private String keyword;
    private int page = 1;
    private int size = DEFAULT_SIZE;

    public AdminUserQuery() {
    }

    public AdminUserQuery(String keyword, int page, int size) {
        setKeyword(keyword);
        setPage(page);
        setSize(size);
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        this.keyword = trimmed.length() > MAX_KEYWORD_LENGTH
                ? trimmed.substring(0, MAX_KEYWORD_LENGTH)
                : trimmed;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page < 1 ? 1 : page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }
}

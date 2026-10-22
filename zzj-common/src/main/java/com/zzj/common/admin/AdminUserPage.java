package com.zzj.common.admin;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * P13-M3A：用户分页结果。
 *
 * <p>{@code total} 为符合条件的总行数（不是本页条数），用于前端分页；
 * {@code page}/{@code size} 回显实际生效的查询参数（已被契约层收敛过）。
 */
public final class AdminUserPage implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<AdminUserSummary> items = new ArrayList<>();
    private long total;
    private int page;
    private int size;

    public AdminUserPage() {
    }

    public AdminUserPage(List<AdminUserSummary> items, long total, int page, int size) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<AdminUserSummary> getItems() {
        return items;
    }

    public void setItems(List<AdminUserSummary> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}

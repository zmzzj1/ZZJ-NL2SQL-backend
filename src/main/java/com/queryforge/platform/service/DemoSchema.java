package com.queryforge.platform.service;

/**
 * 演示库的公开字段说明。前端可在 schemaContext 中传入同等内容；服务端仍有自己的兜底说明。
 */
final class DemoSchema {

    public static final String TEXT = """
            demo_orders(id BIGINT, customer_id BIGINT, amount DECIMAL(12,2), status VARCHAR(16), created_at DATETIME)
            demo_customers(id BIGINT, name VARCHAR(64), city VARCHAR(64), created_at DATETIME)
            demo_order_items(id BIGINT, order_id BIGINT, sku VARCHAR(32), quantity INT, price DECIMAL(10,2))
            """.strip();

    private DemoSchema() {
    }
}

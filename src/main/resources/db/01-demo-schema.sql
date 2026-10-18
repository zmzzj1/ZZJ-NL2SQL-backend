-- P0 演示库结构：仅用于本地只读查询验证，不存放任何真实业务数据。
CREATE DATABASE IF NOT EXISTS sql_demo
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE sql_demo;

DROP TABLE IF EXISTS demo_order_items;
DROP TABLE IF EXISTS demo_orders;
DROP TABLE IF EXISTS demo_customers;

CREATE TABLE demo_customers (
  id          BIGINT       NOT NULL PRIMARY KEY,
  name        VARCHAR(64)  NOT NULL,
  city        VARCHAR(64)  NOT NULL,
  created_at  DATETIME     NOT NULL
) ENGINE = InnoDB;

CREATE TABLE demo_orders (
  id           BIGINT        NOT NULL PRIMARY KEY,
  customer_id  BIGINT        NOT NULL,
  amount       DECIMAL(12,2) NOT NULL,
  status       VARCHAR(16)   NOT NULL,
  created_at   DATETIME      NOT NULL,
  CONSTRAINT fk_demo_orders_customer
    FOREIGN KEY (customer_id) REFERENCES demo_customers (id)
) ENGINE = InnoDB;

CREATE TABLE demo_order_items (
  id        BIGINT        NOT NULL PRIMARY KEY,
  order_id  BIGINT        NOT NULL,
  sku       VARCHAR(32)   NOT NULL,
  quantity  INT           NOT NULL,
  price     DECIMAL(10,2) NOT NULL,
  CONSTRAINT fk_demo_items_order
    FOREIGN KEY (order_id) REFERENCES demo_orders (id)
) ENGINE = InnoDB;

INSERT INTO demo_customers (id, name, city, created_at) VALUES
  (1, '星河书店',   '杭州', '2026-01-12 09:30:00'),
  (2, '南风咖啡',   '成都', '2026-02-03 14:05:00'),
  (3, '远山户外',   '青岛', '2026-03-18 10:40:00');

-- 恰好 3 条订单：契约示例 SELECT COUNT(*) 返回 3 与本初始化脚本一致。
INSERT INTO demo_orders (id, customer_id, amount, status, created_at) VALUES
  (1, 1, 120.00, 'paid',     '2026-08-05 10:15:00'),
  (2, 2,  80.50, 'paid',     '2026-09-02 16:20:00'),
  (3, 3, 199.99, 'refunded', '2026-09-21 11:05:00');

INSERT INTO demo_order_items (id, order_id, sku, quantity, price) VALUES
  (1, 1, 'BOOK-SQL-01', 2, 45.00),
  (2, 1, 'BOOK-DATA-02', 1, 30.00),
  (3, 2, 'COFFEE-BEAN-1', 1, 80.50),
  (4, 3, 'TENT-2P', 1, 199.99);

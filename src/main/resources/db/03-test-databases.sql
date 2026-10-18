-- C7/C8 的双库隔离测试数据；两个库表结构不同，便于验证 Schema 切换。
CREATE DATABASE IF NOT EXISTS sql_demo_a CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS sql_demo_b CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE sql_demo_a;
DROP TABLE IF EXISTS shop_order_items;
DROP TABLE IF EXISTS shop_orders;
DROP TABLE IF EXISTS shop_customers;
CREATE TABLE shop_customers (
  id BIGINT NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  city VARCHAR(64) NOT NULL
) ENGINE = InnoDB;
CREATE TABLE shop_orders (
  id BIGINT NOT NULL PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL
) ENGINE = InnoDB;
CREATE TABLE shop_order_items (
  id BIGINT NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  sku VARCHAR(32) NOT NULL,
  quantity INT NOT NULL,
  price DECIMAL(10,2) NOT NULL
) ENGINE = InnoDB;
INSERT INTO shop_customers (id, name, city) VALUES
  (1, '星河书店', '杭州'),
  (2, '南风咖啡', '成都');
INSERT INTO shop_orders (id, customer_id, amount, status, created_at) VALUES
  (101, 1, 120.00, 'paid', '2026-08-05 10:15:00'),
  (102, 2, 80.50, 'paid', '2026-09-02 16:20:00'),
  (103, 1, 199.99, 'refunded', '2026-09-21 11:05:00');
INSERT INTO shop_order_items (id, order_id, sku, quantity, price) VALUES
  (1, 101, 'BOOK-SQL-01', 2, 45.00),
  (2, 102, 'COFFEE-BEAN-1', 1, 80.50);

USE sql_demo_b;
DROP TABLE IF EXISTS blog_comments;
DROP TABLE IF EXISTS blog_posts;
DROP TABLE IF EXISTS blog_authors;
CREATE TABLE blog_authors (
  id BIGINT NOT NULL PRIMARY KEY,
  pen_name VARCHAR(64) NOT NULL
) ENGINE = InnoDB;
CREATE TABLE blog_posts (
  id BIGINT NOT NULL PRIMARY KEY,
  author_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  views INT NOT NULL,
  published_at DATETIME NOT NULL
) ENGINE = InnoDB;
CREATE TABLE blog_comments (
  id BIGINT NOT NULL PRIMARY KEY,
  post_id BIGINT NOT NULL,
  body VARCHAR(500) NOT NULL
) ENGINE = InnoDB;
INSERT INTO blog_authors (id, pen_name) VALUES (1, '林深'), (2, '周野');
INSERT INTO blog_posts (id, author_id, title, views, published_at) VALUES
  (201, 1, 'SQL 窗口函数入门', 1520, '2026-07-01 09:00:00'),
  (202, 2, '数据建模的三种取舍', 980, '2026-08-12 14:30:00'),
  (203, 1, '从查询计划看索引', 2310, '2026-09-18 08:45:00');
INSERT INTO blog_comments (id, post_id, body) VALUES
  (1, 201, '讲得清楚'),
  (2, 203, '期待续篇');

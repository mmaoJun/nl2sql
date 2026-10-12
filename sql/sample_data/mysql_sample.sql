-- NL2SQL 示例测试数据库
-- 数据库: nlp2sql_sample

CREATE DATABASE IF NOT EXISTS nlp2sql_sample DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE nlp2sql_sample;

-- 部门表
CREATE TABLE departments (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '部门名称',
    location VARCHAR(100) COMMENT '部门位置',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '部门信息';

-- 用户表
CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '姓名',
    email VARCHAR(100) COMMENT '邮箱',
    age INT COMMENT '年龄',
    gender VARCHAR(10) COMMENT '性别',
    department_id INT COMMENT '部门ID',
    salary DECIMAL(10, 2) COMMENT '薪资',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_department_id (department_id),
    INDEX idx_name (name)
) COMMENT '用户信息';

-- 商品表
CREATE TABLE products (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    category VARCHAR(50) COMMENT '分类',
    price DECIMAL(10, 2) NOT NULL COMMENT '价格',
    stock INT DEFAULT 0 COMMENT '库存',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_category (category)
) COMMENT '商品信息';

-- 订单表
CREATE TABLE orders (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL COMMENT '用户ID',
    amount DECIMAL(12, 2) NOT NULL COMMENT '订单金额',
    status VARCHAR(20) DEFAULT '待支付' COMMENT '状态: 待支付/已支付/已发货/已完成/已取消',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) COMMENT '订单信息';

-- 订单明细表
CREATE TABLE order_items (
    id INT PRIMARY KEY AUTO_INCREMENT,
    order_id INT NOT NULL COMMENT '订单ID',
    product_id INT NOT NULL COMMENT '商品ID',
    quantity INT NOT NULL COMMENT '数量',
    unit_price DECIMAL(10, 2) NOT NULL COMMENT '单价',
    INDEX idx_order_id (order_id),
    INDEX idx_product_id (product_id)
) COMMENT '订单明细';

-- ========== 插入测试数据 ==========

-- 部门数据
INSERT INTO departments (name, location) VALUES
('技术部', 'A栋3楼'),
('市场部', 'B栋2楼'),
('财务部', 'A栋5楼'),
('人事部', 'A栋4楼'),
('运营部', 'B栋3楼');

-- 用户数据
INSERT INTO users (name, email, age, gender, department_id, salary) VALUES
('张三', 'zhangsan@example.com', 28, '男', 1, 15000.00),
('李四', 'lisi@example.com', 32, '女', 2, 18000.00),
('王五', 'wangwu@example.com', 25, '男', 1, 12000.00),
('赵六', 'zhaoliu@example.com', 35, '女', 3, 20000.00),
('孙七', 'sunqi@example.com', 29, '男', 4, 16000.00),
('周八', 'zhouba@example.com', 27, '女', 1, 14000.00),
('吴九', 'wujiu@example.com', 31, '男', 2, 17000.00),
('郑十', 'zhengshi@example.com', 26, '女', 5, 13000.00),
('张伟', 'zhangwei@example.com', 33, '男', 3, 19000.00),
('张敏', 'zhangmin@example.com', 24, '女', 1, 11000.00),
('李明', 'liming@example.com', 30, '男', 2, 16500.00),
('王芳', 'wangfang@example.com', 28, '女', 4, 15500.00),
('刘强', 'liuqiang@example.com', 36, '男', 5, 22000.00),
('陈静', 'chenjing@example.com', 27, '女', 1, 13500.00),
('杨洋', 'yangyang@example.com', 29, '男', 3, 17500.00);

-- 商品数据
INSERT INTO products (name, category, price, stock) VALUES
('iPhone 15', '手机', 5999.00, 100),
('MacBook Pro', '笔记本', 14999.00, 50),
('AirPods Pro', '耳机', 1999.00, 200),
('iPad Air', '平板', 4799.00, 80),
('Apple Watch', '手表', 2999.00, 120),
('小米14', '手机', 3999.00, 150),
('华为Mate60', '手机', 6999.00, 90),
('ThinkPad X1', '笔记本', 9999.00, 40),
('Sony WH-1000XM5', '耳机', 2499.00, 60),
('Samsung Galaxy S24', '手机', 5499.00, 110);

-- 订单数据
INSERT INTO orders (user_id, amount, status, created_at) VALUES
(1, 5999.00, '已完成', '2024-01-15 10:30:00'),
(2, 14999.00, '已完成', '2024-01-20 14:20:00'),
(3, 1999.00, '已支付', '2024-02-01 09:15:00'),
(1, 4799.00, '已发货', '2024-02-10 16:45:00'),
(4, 2999.00, '已完成', '2024-02-15 11:30:00'),
(5, 3999.00, '待支付', '2024-03-01 08:00:00'),
(6, 6999.00, '已完成', '2024-03-05 13:20:00'),
(7, 9999.00, '已支付', '2024-03-10 15:40:00'),
(8, 2499.00, '已完成', '2024-03-15 10:10:00'),
(9, 5499.00, '已取消', '2024-03-20 17:30:00'),
(10, 5999.00, '已完成', '2024-04-01 09:00:00'),
(11, 14999.00, '已支付', '2024-04-05 14:15:00'),
(12, 1999.00, '已完成', '2024-04-10 11:25:00'),
(13, 4799.00, '已发货', '2024-04-15 16:50:00'),
(14, 2999.00, '待支付', '2024-04-20 08:30:00'),
(1, 3999.00, '已完成', '2024-05-01 10:00:00'),
(2, 6999.00, '已完成', '2024-05-10 13:45:00'),
(3, 9999.00, '已支付', '2024-05-15 15:20:00'),
(4, 5499.00, '已完成', '2024-05-20 09:30:00'),
(5, 2499.00, '已完成', '2024-06-01 14:00:00');

-- 订单明细数据
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
(1, 1, 1, 5999.00),
(2, 2, 1, 14999.00),
(3, 3, 1, 1999.00),
(4, 4, 1, 4799.00),
(5, 5, 1, 2999.00),
(6, 6, 1, 3999.00),
(7, 7, 1, 6999.00),
(8, 8, 1, 9999.00),
(9, 9, 1, 2499.00),
(10, 10, 1, 5499.00),
(11, 1, 1, 5999.00),
(12, 2, 1, 14999.00),
(13, 3, 1, 1999.00),
(14, 4, 1, 4799.00),
(15, 5, 1, 2999.00),
(16, 6, 1, 3999.00),
(17, 7, 1, 6999.00),
(18, 8, 1, 9999.00),
(19, 10, 1, 5499.00),
(20, 9, 1, 2499.00);

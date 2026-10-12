-- =============================================================
-- NL2SQL 测试数据生成脚本
-- =============================================================
--
-- 用途：一键生成包含 25 张表、上万条记录的测试数据库，
--       用于验证 NL2SQL 的 SchemaCompressor 全部压缩逻辑。
--
-- 使用方式：
--   mysql -u root -p < mysql_sample_extended.sql
--
-- 幂等性：脚本会先 DROP 再 CREATE，可重复执行。
--
-- 覆盖的测试场景：
--   L0 表筛选  — 25 张表（>15 张阈值），关键词匹配只保留相关表
--   L1/L2 降级 — 每张表 8-19 列，800 token 预算下列多时退化为只留列名
--   噪声表干扰 — 10 张系统/运维表与业务查询无关，验证 L0 能正确排除
--
-- 数据库：nlp2sql_test（独立库，不影响 nlp2sql_sample）
-- =============================================================

DROP DATABASE IF EXISTS nlp2sql_test;
CREATE DATABASE nlp2sql_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE nlp2sql_test;

-- =============================================================
-- 第一组：核心业务表（5 张，15-19 列）
-- =============================================================

CREATE TABLE departments (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '部门名称',
    code VARCHAR(20) NOT NULL COMMENT '部门编码',
    location VARCHAR(100) COMMENT '部门位置',
    manager_id INT COMMENT '部门负责人ID',
    parent_id INT COMMENT '上级部门ID',
    level INT DEFAULT 1 COMMENT '组织层级',
    budget DECIMAL(12,2) COMMENT '年度预算',
    headcount INT COMMENT '编制人数',
    phone VARCHAR(20) COMMENT '部门电话',
    email VARCHAR(100) COMMENT '部门邮箱',
    description TEXT COMMENT '部门描述',
    status TINYINT DEFAULT 1 COMMENT '状态: 1启用 0停用',
    sort_order INT DEFAULT 0 COMMENT '排序号',
    created_by INT COMMENT '创建人',
    updated_by INT COMMENT '更新人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '部门信息';

CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '姓名',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    age INT COMMENT '年龄',
    gender VARCHAR(10) COMMENT '性别',
    department_id INT COMMENT '部门ID',
    title VARCHAR(50) COMMENT '职位',
    salary DECIMAL(10,2) COMMENT '薪资',
    hire_date DATE COMMENT '入职日期',
    address VARCHAR(200) COMMENT '住址',
    emergency_contact VARCHAR(50) COMMENT '紧急联系人',
    emergency_phone VARCHAR(20) COMMENT '紧急联系电话',
    id_card VARCHAR(18) COMMENT '身份证号',
    education VARCHAR(20) COMMENT '学历',
    status TINYINT DEFAULT 1 COMMENT '状态: 1在职 0离职',
    avatar_url VARCHAR(200) COMMENT '头像地址',
    last_login_at DATETIME COMMENT '最后登录时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '用户信息';

CREATE TABLE products (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    sku VARCHAR(50) COMMENT 'SKU编码',
    category VARCHAR(50) COMMENT '分类',
    sub_category VARCHAR(50) COMMENT '子分类',
    brand VARCHAR(50) COMMENT '品牌',
    price DECIMAL(10,2) NOT NULL COMMENT '价格',
    cost_price DECIMAL(10,2) COMMENT '成本价',
    stock INT DEFAULT 0 COMMENT '库存',
    weight DECIMAL(8,2) COMMENT '重量(kg)',
    supplier_id INT COMMENT '供应商ID',
    warranty_months INT COMMENT '保修期(月)',
    description TEXT COMMENT '商品描述',
    image_url VARCHAR(200) COMMENT '主图地址',
    status TINYINT DEFAULT 1 COMMENT '状态: 1上架 0下架',
    sales_count INT DEFAULT 0 COMMENT '销量',
    rating DECIMAL(2,1) COMMENT '评分',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '商品信息';

CREATE TABLE orders (
    id INT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL COMMENT '订单编号',
    user_id INT NOT NULL COMMENT '用户ID',
    amount DECIMAL(12,2) NOT NULL COMMENT '订单金额',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额',
    shipping_fee DECIMAL(10,2) DEFAULT 0 COMMENT '运费',
    status VARCHAR(20) DEFAULT '待支付' COMMENT '状态: 待支付/已支付/已发货/已完成/已取消',
    payment_method VARCHAR(20) COMMENT '支付方式: 微信/支付宝/银行卡',
    payment_at DATETIME COMMENT '支付时间',
    shipping_address VARCHAR(300) COMMENT '收货地址',
    shipping_company VARCHAR(50) COMMENT '物流公司',
    tracking_no VARCHAR(50) COMMENT '物流单号',
    shipped_at DATETIME COMMENT '发货时间',
    completed_at DATETIME COMMENT '完成时间',
    cancelled_at DATETIME COMMENT '取消时间',
    cancel_reason VARCHAR(200) COMMENT '取消原因',
    remark VARCHAR(500) COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '订单信息';

CREATE TABLE order_items (
    id INT PRIMARY KEY AUTO_INCREMENT,
    order_id INT NOT NULL COMMENT '订单ID',
    product_id INT NOT NULL COMMENT '商品ID',
    product_name VARCHAR(100) COMMENT '商品名称(快照)',
    sku VARCHAR(50) COMMENT 'SKU编码(快照)',
    quantity INT NOT NULL COMMENT '数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '单价',
    discount_rate DECIMAL(3,2) DEFAULT 1.00 COMMENT '折扣率',
    subtotal DECIMAL(10,2) COMMENT '小计金额',
    tax_rate DECIMAL(4,2) DEFAULT 0 COMMENT '税率',
    tax_amount DECIMAL(10,2) DEFAULT 0 COMMENT '税额',
    refund_quantity INT DEFAULT 0 COMMENT '退货数量',
    refund_amount DECIMAL(10,2) DEFAULT 0 COMMENT '退货金额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '订单明细';

-- =============================================================
-- 第二组：支撑业务表（10 张，每张 15-22 列）
-- =============================================================

CREATE TABLE suppliers (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '供应商名称',
    code VARCHAR(30) COMMENT '供应商编码',
    contact_person VARCHAR(50) COMMENT '联系人',
    contact_phone VARCHAR(20) COMMENT '联系电话',
    contact_email VARCHAR(100) COMMENT '联系邮箱',
    address VARCHAR(300) COMMENT '地址',
    city VARCHAR(50) COMMENT '城市',
    province VARCHAR(50) COMMENT '省份',
    country VARCHAR(50) DEFAULT '中国' COMMENT '国家',
    bank_name VARCHAR(100) COMMENT '开户银行',
    bank_account VARCHAR(50) COMMENT '银行账号',
    tax_number VARCHAR(30) COMMENT '税号',
    rating INT DEFAULT 5 COMMENT '评级(1-5)',
    cooperation_years INT COMMENT '合作年限',
    total_orders INT DEFAULT 0 COMMENT '累计订单数',
    total_amount DECIMAL(14,2) DEFAULT 0 COMMENT '累计金额',
    status TINYINT DEFAULT 1 COMMENT '状态: 1合作中 0已终止',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '供应商信息';

CREATE TABLE categories (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '分类名称',
    parent_id INT DEFAULT 0 COMMENT '父分类ID',
    level INT DEFAULT 1 COMMENT '层级',
    sort_order INT DEFAULT 0 COMMENT '排序号',
    icon_url VARCHAR(200) COMMENT '图标地址',
    image_url VARCHAR(200) COMMENT '分类图',
    description TEXT COMMENT '分类描述',
    product_count INT DEFAULT 0 COMMENT '商品数量',
    is_visible TINYINT DEFAULT 1 COMMENT '是否显示',
    seo_title VARCHAR(100) COMMENT 'SEO标题',
    seo_keywords VARCHAR(200) COMMENT 'SEO关键词',
    seo_description VARCHAR(300) COMMENT 'SEO描述',
    created_by INT COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '商品分类';

CREATE TABLE inventory_logs (
    id INT PRIMARY KEY AUTO_INCREMENT,
    product_id INT NOT NULL COMMENT '商品ID',
    warehouse_id INT COMMENT '仓库ID',
    type VARCHAR(20) NOT NULL COMMENT '类型: 入库/出库/盘点/调整',
    quantity INT NOT NULL COMMENT '数量(正入负出)',
    before_stock INT COMMENT '变更前库存',
    after_stock INT COMMENT '变更后库存',
    reference_type VARCHAR(30) COMMENT '关联类型: 订单/采购/退货',
    reference_id INT COMMENT '关联单据ID',
    operator_id INT COMMENT '操作人ID',
    operator_name VARCHAR(50) COMMENT '操作人姓名',
    reason VARCHAR(200) COMMENT '变动原因',
    batch_no VARCHAR(50) COMMENT '批次号',
    production_date DATE COMMENT '生产日期',
    expiry_date DATE COMMENT '过期日期',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '库存变动日志';

CREATE TABLE customer_service_tickets (
    id INT PRIMARY KEY AUTO_INCREMENT,
    ticket_no VARCHAR(32) NOT NULL COMMENT '工单编号',
    user_id INT COMMENT '用户ID',
    order_id INT COMMENT '关联订单ID',
    type VARCHAR(30) NOT NULL COMMENT '类型: 投诉/咨询/售后/建议',
    subject VARCHAR(200) COMMENT '主题',
    content TEXT COMMENT '内容',
    priority TINYINT DEFAULT 2 COMMENT '优先级: 1紧急 2普通 3低',
    status VARCHAR(20) DEFAULT '待处理' COMMENT '状态: 待处理/处理中/已解决/已关闭',
    assigned_to INT COMMENT '分配给',
    assigned_at DATETIME COMMENT '分配时间',
    first_response_at DATETIME COMMENT '首次响应时间',
    resolved_at DATETIME COMMENT '解决时间',
    closed_at DATETIME COMMENT '关闭时间',
    satisfaction_score INT COMMENT '满意度评分(1-5)',
    user_feedback TEXT COMMENT '用户反馈',
    internal_note TEXT COMMENT '内部备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '客服工单';

CREATE TABLE promotions (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '活动名称',
    type VARCHAR(30) COMMENT '类型: 满减/折扣/秒杀/拼团',
    description TEXT COMMENT '活动描述',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    discount_rate DECIMAL(3,2) COMMENT '折扣率',
    min_amount DECIMAL(10,2) COMMENT '最低消费金额',
    max_discount DECIMAL(10,2) COMMENT '最大优惠金额',
    total_budget DECIMAL(12,2) COMMENT '活动预算',
    used_budget DECIMAL(12,2) DEFAULT 0 COMMENT '已用预算',
    participant_count INT DEFAULT 0 COMMENT '参与人数',
    order_count INT DEFAULT 0 COMMENT '订单数',
    banner_url VARCHAR(200) COMMENT '活动Banner',
    rules TEXT COMMENT '活动规则',
    status TINYINT DEFAULT 0 COMMENT '状态: 0未开始 1进行中 2已结束',
    created_by INT COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '促销活动';

CREATE TABLE coupons (
    id INT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL COMMENT '优惠码',
    name VARCHAR(100) COMMENT '优惠券名称',
    type VARCHAR(20) NOT NULL COMMENT '类型: 满减/折扣/免邮',
    face_value DECIMAL(10,2) COMMENT '面值',
    discount_rate DECIMAL(3,2) COMMENT '折扣率',
    min_amount DECIMAL(10,2) COMMENT '使用门槛',
    max_discount DECIMAL(10,2) COMMENT '最大优惠',
    total_count INT COMMENT '发行总量',
    used_count INT DEFAULT 0 COMMENT '已使用量',
    per_user_limit INT DEFAULT 1 COMMENT '每人限领',
    valid_days INT COMMENT '有效期(天)',
    start_time DATETIME COMMENT '生效时间',
    end_time DATETIME COMMENT '过期时间',
    applicable_categories VARCHAR(500) COMMENT '适用分类',
    applicable_products VARCHAR(1000) COMMENT '适用商品',
    status TINYINT DEFAULT 1 COMMENT '状态: 1有效 0无效',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '优惠券';

CREATE TABLE user_coupons (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL COMMENT '用户ID',
    coupon_id INT NOT NULL COMMENT '优惠券ID',
    coupon_code VARCHAR(32) COMMENT '优惠码',
    status VARCHAR(20) DEFAULT '未使用' COMMENT '状态: 未使用/已使用/已过期',
    used_order_id INT COMMENT '使用的订单ID',
    used_at DATETIME COMMENT '使用时间',
    received_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    expired_at DATETIME COMMENT '过期时间'
) COMMENT '用户优惠券';

CREATE TABLE shopping_carts (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL COMMENT '用户ID',
    product_id INT NOT NULL COMMENT '商品ID',
    sku VARCHAR(50) COMMENT 'SKU',
    quantity INT DEFAULT 1 COMMENT '数量',
    selected TINYINT DEFAULT 1 COMMENT '是否选中',
    added_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '购物车';

CREATE TABLE addresses (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL COMMENT '用户ID',
    receiver_name VARCHAR(50) NOT NULL COMMENT '收件人',
    receiver_phone VARCHAR(20) NOT NULL COMMENT '收件电话',
    province VARCHAR(50) COMMENT '省',
    city VARCHAR(50) COMMENT '市',
    district VARCHAR(50) COMMENT '区',
    detail_address VARCHAR(300) COMMENT '详细地址',
    postal_code VARCHAR(10) COMMENT '邮编',
    is_default TINYINT DEFAULT 0 COMMENT '是否默认',
    tag VARCHAR(20) COMMENT '标签: 家/公司/学校',
    longitude DECIMAL(10,6) COMMENT '经度',
    latitude DECIMAL(10,6) COMMENT '纬度',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '收货地址';

CREATE TABLE product_reviews (
    id INT PRIMARY KEY AUTO_INCREMENT,
    product_id INT NOT NULL COMMENT '商品ID',
    user_id INT NOT NULL COMMENT '用户ID',
    order_id INT COMMENT '关联订单ID',
    rating TINYINT NOT NULL COMMENT '评分(1-5)',
    title VARCHAR(100) COMMENT '评价标题',
    content TEXT COMMENT '评价内容',
    images VARCHAR(1000) COMMENT '评价图片(逗号分隔)',
    is_anonymous TINYINT DEFAULT 0 COMMENT '是否匿名',
    likes_count INT DEFAULT 0 COMMENT '点赞数',
    reply_content TEXT COMMENT '商家回复',
    reply_at DATETIME COMMENT '回复时间',
    status TINYINT DEFAULT 1 COMMENT '状态: 1显示 0隐藏',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '商品评价';

-- =============================================================
-- 第三组：系统/运维表（10 张，"噪声表"，每张 15-25 列）
-- 这些表与常见电商查询无关，用于验证 L0 关键词筛选能正确排除
-- =============================================================

CREATE TABLE system_config (
    id INT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    config_type VARCHAR(20) DEFAULT 'string' COMMENT '类型: string/number/boolean/json',
    group_name VARCHAR(50) COMMENT '分组',
    description VARCHAR(300) COMMENT '描述',
    is_encrypted TINYINT DEFAULT 0 COMMENT '是否加密',
    is_system TINYINT DEFAULT 0 COMMENT '是否系统配置(不可删)',
    scope VARCHAR(20) DEFAULT 'global' COMMENT '范围: global/tenant/user',
    tenant_id INT COMMENT '租户ID',
    version INT DEFAULT 1 COMMENT '版本号',
    created_by INT COMMENT '创建人',
    updated_by INT COMMENT '更新人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '系统配置';

CREATE TABLE operation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) COMMENT '链路追踪ID',
    user_id INT COMMENT '操作人ID',
    username VARCHAR(50) COMMENT '操作人',
    module VARCHAR(50) COMMENT '模块',
    action VARCHAR(50) COMMENT '操作',
    method VARCHAR(200) COMMENT '请求方法',
    request_url VARCHAR(500) COMMENT '请求URL',
    request_method VARCHAR(10) COMMENT 'HTTP方法',
    request_params TEXT COMMENT '请求参数',
    response_body TEXT COMMENT '响应内容',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '浏览器UA',
    duration_ms INT COMMENT '耗时(毫秒)',
    status TINYINT COMMENT '状态: 1成功 0失败',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) COMMENT '操作日志';

CREATE TABLE scheduled_tasks (
    id INT PRIMARY KEY AUTO_INCREMENT,
    task_name VARCHAR(100) NOT NULL COMMENT '任务名称',
    task_group VARCHAR(50) COMMENT '任务分组',
    bean_name VARCHAR(100) COMMENT 'Bean名称',
    method_name VARCHAR(100) COMMENT '方法名',
    method_params VARCHAR(500) COMMENT '方法参数',
    cron_expression VARCHAR(50) COMMENT 'Cron表达式',
    misfire_policy TINYINT DEFAULT 1 COMMENT '错过策略: 1立即执行 2执行一次 3放弃',
    concurrent TINYINT DEFAULT 0 COMMENT '是否允许并发',
    status TINYINT DEFAULT 0 COMMENT '状态: 0正常 1暂停',
    last_execute_time DATETIME COMMENT '上次执行时间',
    last_result VARCHAR(500) COMMENT '上次执行结果',
    next_execute_time DATETIME COMMENT '下次执行时间',
    execute_count INT DEFAULT 0 COMMENT '执行次数',
    error_count INT DEFAULT 0 COMMENT '错误次数',
    description VARCHAR(300) COMMENT '描述',
    created_by INT COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '定时任务';

CREATE TABLE api_access_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    api_name VARCHAR(100) COMMENT '接口名称',
    api_path VARCHAR(300) COMMENT '接口路径',
    caller_app VARCHAR(50) COMMENT '调用方应用',
    caller_ip VARCHAR(50) COMMENT '调用方IP',
    request_method VARCHAR(10) COMMENT '请求方法',
    request_headers TEXT COMMENT '请求头',
    request_body TEXT COMMENT '请求体',
    response_status INT COMMENT '响应状态码',
    response_headers TEXT COMMENT '响应头',
    response_body TEXT COMMENT '响应体',
    duration_ms INT COMMENT '耗时(毫秒)',
    bytes_sent INT COMMENT '发送字节数',
    bytes_received INT COMMENT '接收字节数',
    error_type VARCHAR(50) COMMENT '错误类型',
    error_detail TEXT COMMENT '错误详情',
    trace_id VARCHAR(64) COMMENT '链路ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_api_path (api_path(100)),
    INDEX idx_created_at (created_at)
) COMMENT 'API访问日志';

CREATE TABLE notification_queue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    type VARCHAR(20) NOT NULL COMMENT '类型: email/sms/push/wechat',
    recipient VARCHAR(200) NOT NULL COMMENT '接收者',
    template_id VARCHAR(50) COMMENT '模板ID',
    template_params TEXT COMMENT '模板参数(JSON)',
    subject VARCHAR(200) COMMENT '主题',
    content TEXT COMMENT '内容',
    priority TINYINT DEFAULT 5 COMMENT '优先级(1最高)',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retries INT DEFAULT 3 COMMENT '最大重试',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '状态: pending/sending/sent/failed',
    sent_at DATETIME COMMENT '发送时间',
    error_message TEXT COMMENT '错误信息',
    channel_config_id INT COMMENT '通道配置ID',
    business_type VARCHAR(50) COMMENT '业务类型',
    business_id INT COMMENT '业务ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '消息通知队列';

CREATE TABLE file_uploads (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(200) NOT NULL COMMENT '文件名',
    original_name VARCHAR(200) COMMENT '原始文件名',
    file_path VARCHAR(500) COMMENT '存储路径',
    file_url VARCHAR(500) COMMENT '访问URL',
    file_size BIGINT COMMENT '文件大小(字节)',
    file_type VARCHAR(100) COMMENT 'MIME类型',
    file_extension VARCHAR(10) COMMENT '扩展名',
    storage_type VARCHAR(20) DEFAULT 'local' COMMENT '存储类型: local/oss/minio',
    bucket_name VARCHAR(100) COMMENT '存储桶',
    md5_hash VARCHAR(32) COMMENT 'MD5校验',
    uploader_id INT COMMENT '上传人ID',
    uploader_name VARCHAR(50) COMMENT '上传人',
    business_type VARCHAR(50) COMMENT '业务类型',
    business_id INT COMMENT '业务ID',
    is_public TINYINT DEFAULT 0 COMMENT '是否公开',
    download_count INT DEFAULT 0 COMMENT '下载次数',
    status TINYINT DEFAULT 1 COMMENT '状态: 1正常 0已删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '文件上传记录';

CREATE TABLE data_import_tasks (
    id INT PRIMARY KEY AUTO_INCREMENT,
    task_name VARCHAR(100) NOT NULL COMMENT '任务名称',
    import_type VARCHAR(50) COMMENT '导入类型',
    target_table VARCHAR(100) COMMENT '目标表',
    file_path VARCHAR(500) COMMENT '文件路径',
    file_name VARCHAR(200) COMMENT '文件名',
    total_rows INT COMMENT '总行数',
    success_rows INT DEFAULT 0 COMMENT '成功行数',
    failed_rows INT DEFAULT 0 COMMENT '失败行数',
    skip_rows INT DEFAULT 0 COMMENT '跳过行数',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '状态: pending/running/success/failed',
    error_log TEXT COMMENT '错误日志',
    operator_id INT COMMENT '操作人ID',
    operator_name VARCHAR(50) COMMENT '操作人',
    started_at DATETIME COMMENT '开始时间',
    finished_at DATETIME COMMENT '结束时间',
    duration_seconds INT COMMENT '耗时(秒)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT '数据导入任务';

CREATE TABLE cache_statistics (
    id INT PRIMARY KEY AUTO_INCREMENT,
    cache_name VARCHAR(100) NOT NULL COMMENT '缓存名称',
    cache_key VARCHAR(300) COMMENT '缓存键',
    hit_count BIGINT DEFAULT 0 COMMENT '命中次数',
    miss_count BIGINT DEFAULT 0 COMMENT '未命中次数',
    eviction_count BIGINT DEFAULT 0 COMMENT '淘汰次数',
    entry_count INT DEFAULT 0 COMMENT '条目数',
    memory_bytes BIGINT DEFAULT 0 COMMENT '占用内存',
    avg_get_time_ms DECIMAL(8,2) COMMENT '平均获取时间',
    avg_put_time_ms DECIMAL(8,2) COMMENT '平均写入时间',
    last_hit_at DATETIME COMMENT '最后命中时间',
    last_miss_at DATETIME COMMENT '最后未命中时间',
    ttl_seconds INT COMMENT '过期时间',
    refresh_policy VARCHAR(20) COMMENT '刷新策略',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '缓存统计';

CREATE TABLE permission_roles (
    id INT PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    description VARCHAR(300) COMMENT '描述',
    data_scope TINYINT DEFAULT 1 COMMENT '数据范围: 1全部 2本部门 3本人',
    sort_order INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '状态: 1启用 0停用',
    is_system TINYINT DEFAULT 0 COMMENT '是否内置角色',
    created_by INT COMMENT '创建人',
    updated_by INT COMMENT '更新人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '角色权限';

CREATE TABLE permission_menus (
    id INT PRIMARY KEY AUTO_INCREMENT,
    menu_name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    parent_id INT DEFAULT 0 COMMENT '父菜单ID',
    menu_type TINYINT COMMENT '类型: 1目录 2菜单 3按钮',
    path VARCHAR(200) COMMENT '路由地址',
    component VARCHAR(200) COMMENT '组件路径',
    permission_code VARCHAR(100) COMMENT '权限标识',
    icon VARCHAR(100) COMMENT '图标',
    sort_order INT DEFAULT 0 COMMENT '排序',
    is_visible TINYINT DEFAULT 1 COMMENT '是否显示',
    is_cached TINYINT DEFAULT 0 COMMENT '是否缓存',
    redirect_url VARCHAR(200) COMMENT '重定向地址',
    created_by INT COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '菜单权限';

-- =============================================================
-- 数据填充
-- =============================================================

-- 部门数据 (50 条)
INSERT INTO departments (name, code, location, manager_id, parent_id, level, budget, headcount, phone, email, description, status, sort_order) VALUES
('技术部', 'TECH', 'A栋3楼', 1, 0, 1, 5000000, 50, '010-10001', 'tech@company.com', '负责产品研发与技术架构', 1, 1),
('前端组', 'TECH-FE', 'A栋3楼', 6, 1, 2, 1500000, 15, '010-10002', 'fe@company.com', '前端开发与UI实现', 1, 1),
('后端组', 'TECH-BE', 'A栋3楼', 7, 1, 2, 2000000, 20, '010-10003', 'be@company.com', '后端服务与API开发', 1, 2),
('测试组', 'TECH-QA', 'A栋3楼', 8, 1, 2, 800000, 10, '010-10004', 'qa@company.com', '质量保证与自动化测试', 1, 3),
('运维组', 'TECH-OPS', 'A栋3楼', 9, 1, 2, 1200000, 5, '010-10005', 'ops@company.com', '基础设施与运维保障', 1, 4),
('市场部', 'MKT', 'B栋2楼', 2, 0, 1, 3000000, 30, '010-20001', 'mkt@company.com', '市场推广与品牌建设', 1, 2),
('品牌组', 'MKT-BRD', 'B栋2楼', 10, 6, 2, 1000000, 8, '010-20002', 'brand@company.com', '品牌策划与视觉设计', 1, 1),
('推广组', 'MKT-PRO', 'B栋2楼', 11, 6, 2, 1500000, 12, '010-20003', 'promo@company.com', '渠道推广与投放', 1, 2),
('内容组', 'MKT-CNT', 'B栋2楼', 12, 6, 2, 500000, 10, '010-20004', 'content@company.com', '内容运营与新媒体', 1, 3),
('财务部', 'FIN', 'A栋5楼', 3, 0, 1, 2000000, 15, '010-30001', 'fin@company.com', '财务管理与审计', 1, 3),
('会计组', 'FIN-ACC', 'A栋5楼', 13, 10, 2, 800000, 8, '010-30002', 'acc@company.com', '日常会计核算', 1, 1),
('税务组', 'FIN-TAX', 'A栋5楼', 14, 10, 2, 500000, 4, '010-30003', 'tax@company.com', '税务申报与筹划', 1, 2),
('审计组', 'FIN-AUD', 'A栋5楼', 15, 10, 2, 700000, 3, '010-30004', 'audit@company.com', '内部审计与合规', 1, 3),
('人事部', 'HR', 'A栋4楼', 4, 0, 1, 1500000, 12, '010-40001', 'hr@company.com', '人力资源与行政管理', 1, 4),
('招聘组', 'HR-REC', 'A栋4楼', 16, 14, 2, 500000, 4, '010-40002', 'recruit@company.com', '人才招聘与面试', 1, 1),
('培训组', 'HR-TRN', 'A栋4楼', 17, 14, 2, 400000, 3, '010-40003', 'train@company.com', '员工培训与发展', 1, 2),
('薪酬组', 'HR-SAL', 'A栋4楼', 18, 14, 2, 300000, 3, '010-40004', 'salary@company.com', '薪酬福利管理', 1, 3),
('行政组', 'HR-ADM', 'A栋4楼', 19, 14, 2, 300000, 2, '010-40005', 'admin@company.com', '行政后勤管理', 1, 4),
('运营部', 'OPS', 'B栋3楼', 5, 0, 1, 2500000, 25, '010-50001', 'ops@company.com', '业务运营与数据分析', 1, 5),
('用户运营组', 'OPS-USR', 'B栋3楼', 20, 19, 2, 800000, 8, '010-50002', 'userops@company.com', '用户增长与留存', 1, 1),
('活动运营组', 'OPS-ACT', 'B栋3楼', 21, 19, 2, 1000000, 7, '010-50003', 'actops@company.com', '活动策划与执行', 1, 2),
('数据组', 'OPS-DAT', 'B栋3楼', 22, 19, 2, 700000, 5, '010-50004', 'data@company.com', '数据分析与报表', 1, 3),
('客服组', 'OPS-CS', 'B栋3楼', 23, 19, 2, 500000, 5, '010-50005', 'cs@company.com', '客户服务与投诉处理', 1, 4),
('产品部', 'PD', 'A栋6楼', 24, 0, 1, 2000000, 15, '010-60001', 'pd@company.com', '产品规划与设计', 1, 6),
('产品一组', 'PD-G1', 'A栋6楼', 25, 24, 2, 800000, 5, '010-60002', 'pd1@company.com', '电商产品线', 1, 1),
('产品二组', 'PD-G2', 'A栋6楼', 26, 24, 2, 800000, 5, '010-60003', 'pd2@company.com', 'SaaS产品线', 1, 2),
('设计组', 'PD-DES', 'A栋6楼', 27, 24, 2, 400000, 5, '010-60004', 'design@company.com', 'UI/UX设计', 1, 3),
('总经办', 'CEO', 'A栋8楼', 28, 0, 1, 10000000, 5, '010-80001', 'ceo@company.com', '公司战略与决策', 1, 7),
('法务部', 'LEG', 'A栋7楼', 29, 0, 1, 1000000, 8, '010-70001', 'legal@company.com', '法律事务与合同管理', 1, 8),
('安全部', 'SEC', 'A栋2楼', 30, 0, 1, 1500000, 10, '010-20006', 'sec@company.com', '信息安全与合规审计', 1, 9),
('采购部', 'PUR', 'B栋1楼', 31, 0, 1, 8000000, 12, '010-10006', 'pur@company.com', '供应商管理与采购', 1, 10),
('仓储部', 'WH', 'C栋1楼', 32, 0, 1, 3000000, 20, '010-10007', 'wh@company.com', '仓储管理与物流配送', 1, 11),
('质检部', 'QC', 'C栋2楼', 33, 0, 1, 1200000, 8, '010-10008', 'qc@company.com', '质量检测与品控', 1, 12),
('研发中心', 'RD', 'A栋1楼', 34, 0, 1, 10000000, 40, '010-10009', 'rd@company.com', '前沿技术研究与创新', 1, 13),
('AI实验室', 'RD-AI', 'A栋1楼', 35, 34, 2, 3000000, 10, '010-10010', 'ai@company.com', '人工智能算法研究', 1, 1),
('大数据组', 'RD-BD', 'A栋1楼', 36, 34, 2, 2000000, 8, '010-10011', 'bigdata@company.com', '大数据平台建设', 1, 2),
('架构组', 'RD-ARC', 'A栋1楼', 37, 34, 2, 1500000, 5, '010-10012', 'arch@company.com', '系统架构设计', 1, 3),
('国际业务部', 'INTL', 'B栋5楼', 38, 0, 1, 5000000, 20, '010-50006', 'intl@company.com', '海外市场拓展', 1, 14),
('北美区', 'INTL-NA', 'B栋5楼', 39, 38, 2, 2000000, 8, '010-50007', 'na@company.com', '北美市场运营', 1, 1),
('欧洲区', 'INTL-EU', 'B栋5楼', 40, 38, 2, 1500000, 6, '010-50008', 'eu@company.com', '欧洲市场运营', 1, 2),
('亚太区', 'INTL-AP', 'B栋5楼', 41, 38, 2, 1500000, 6, '010-50009', 'ap@company.com', '亚太市场运营', 1, 3),
('战略投资部', 'SI', 'A栋8楼', 42, 0, 1, 50000000, 5, '010-80002', 'si@company.com', '战略投资与并购', 1, 15),
('公共关系部', 'PR', 'B栋4楼', 43, 0, 1, 2000000, 10, '010-40006', 'pr@company.com', '公共关系与媒体沟通', 1, 16),
('合规部', 'CMP', 'A栋7楼', 44, 0, 1, 1500000, 6, '010-70002', 'compliance@company.com', '合规管理与风险控制', 1, 17),
('内审部', 'IA', 'A栋7楼', 45, 0, 1, 1000000, 4, '010-70003', 'ia@company.com', '内部审计与流程优化', 1, 18),
('IT支持部', 'IT', 'A栋2楼', 46, 0, 1, 2000000, 15, '010-20007', 'it@company.com', 'IT基础设施与技术支持', 1, 19),
('培训中心', 'TC', 'B栋6楼', 47, 0, 1, 1500000, 8, '010-60005', 'tc@company.com', '企业培训与知识管理', 1, 20),
('企业文化部', 'CORP', 'B栋4楼', 48, 0, 1, 800000, 5, '010-40007', 'corp@company.com', '企业文化建设与员工关怀', 1, 21),
('项目管理部', 'PMO', 'A栋6楼', 49, 0, 1, 1200000, 6, '010-60006', 'pmo@company.com', '项目管理与流程规范', 1, 22),
('质量管理部', 'QM', 'C栋3楼', 50, 0, 1, 1000000, 5, '010-10013', 'qm@company.com', '全面质量管理', 1, 23);

-- 用户数据 (200 条，用存储过程生成)
DELIMITER //
CREATE PROCEDURE generate_users()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_name VARCHAR(50);
    DECLARE v_dept INT;
    DECLARE v_age INT;
    DECLARE v_salary DECIMAL(10,2);
    DECLARE v_title VARCHAR(50);
    DECLARE v_gender VARCHAR(10);
    DECLARE titles_1 VARCHAR(50) DEFAULT '工程师';
    DECLARE titles_2 VARCHAR(50) DEFAULT '高级工程师';
    DECLARE titles_3 VARCHAR(50) DEFAULT '资深工程师';
    DECLARE titles_4 VARCHAR(50) DEFAULT '技术专家';
    DECLARE titles_5 VARCHAR(50) DEFAULT '架构师';
    DECLARE titles_6 VARCHAR(50) DEFAULT '经理';
    DECLARE titles_7 VARCHAR(50) DEFAULT '总监';
    DECLARE titles_8 VARCHAR(50) DEFAULT '主管';
    DECLARE titles_9 VARCHAR(50) DEFAULT '专员';
    DECLARE titles_10 VARCHAR(50) DEFAULT '助理';
    DECLARE surnames VARCHAR(200) DEFAULT '张李王赵刘陈杨黄周吴徐孙马朱胡郭何高林罗梁宋郑谢韩唐冯于董萧程曹袁邓许傅沈曾彭吕苏卢蒋蔡贾丁魏薛叶阎余潘杜戴夏钟汪田任姜范方石姚谭廖邹熊金陆郝孔白崔康毛邱秦江史顾侯邵孟龙万段雷钱汤尹黎易常武乔贺赖龚文';
    
    WHILE i <= 200 DO
        SET v_name = CONCAT(
            SUBSTRING(surnames, (i * 3) % 100 + 1, 1),
            CASE WHEN i % 3 = 0 THEN SUBSTRING(surnames, (i * 7) % 100 + 1, 1) ELSE '' END,
            CASE WHEN i % 2 = 0 THEN SUBSTRING(surnames, (i * 11) % 100 + 1, 1) ELSE SUBSTRING(surnames, (i * 13) % 100 + 1, 1) END
        );
        SET v_dept = (i % 50) + 1;
        SET v_age = 22 + (i % 18);
        SET v_salary = 8000 + (i % 200) * 100;
        SET v_gender = IF(i % 2 = 0, '男', '女');
        SET v_title = CASE i % 10
            WHEN 0 THEN titles_1 WHEN 1 THEN titles_2 WHEN 2 THEN titles_3
            WHEN 3 THEN titles_4 WHEN 4 THEN titles_5 WHEN 5 THEN titles_6
            WHEN 6 THEN titles_7 WHEN 7 THEN titles_8 WHEN 8 THEN titles_9
            ELSE titles_10 END;
        
        INSERT INTO users (name, email, phone, age, gender, department_id, title, salary, hire_date, address, status)
        VALUES (
            v_name,
            CONCAT(LOWER(v_name), i, '@company.com'),
            CONCAT('1', 30 + (i % 70), LPAD(i % 100000000, 8, '0')),
            v_age, v_gender, v_dept, v_title, v_salary,
            DATE_ADD('2020-01-01', INTERVAL (i * 3) DAY),
            CONCAT('北京市朝阳区建国路', i, '号'),
            1
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_users();
DROP PROCEDURE generate_users;

-- 供应商数据 (30 条)
INSERT INTO suppliers (name, code, contact_person, contact_phone, contact_email, address, city, province, bank_name, bank_account, tax_number, rating, cooperation_years, total_orders, total_amount, status) VALUES
('深圳华强电子', 'SUP-001', '王建国', '0755-88880001', 'wang@huaqiang.com', '华强北路100号', '深圳', '广东', '工商银行', '6222001234567890', '91440300MA5XXXXX', 5, 5, 1200, 5600000.00, 1),
('上海芯创科技', 'SUP-002', '李明华', '021-66660001', 'li@xinchuang.com', '浦东新区张江路50号', '上海', '上海', '建设银行', '6227001234567891', '91310000MA1XXXXX', 4, 3, 800, 3200000.00, 1),
('北京中科微电子', 'SUP-003', '张伟', '010-55550001', 'zhang@zkwe.com', '中关村大街1号', '北京', '北京', '中国银行', '6226001234567892', '91110000MA0XXXXX', 5, 7, 2000, 12000000.00, 1),
('杭州云智软件', 'SUP-004', '陈思远', '0571-88880002', 'chen@yunzhi.com', '西湖区文三路200号', '杭州', '浙江', '农业银行', '6228001234567893', '91330000MA2XXXXX', 4, 2, 500, 1800000.00, 1),
('广州恒通物流', 'SUP-005', '刘志强', '020-33330001', 'liu@hengtong.com', '天河区珠江新城', '广州', '广东', '招商银行', '6225001234567894', '91440000MA3XXXXX', 3, 4, 3000, 800000.00, 1),
('成都天府包装', 'SUP-006', '赵敏', '028-77770001', 'zhao@tianfu.com', '高新区天府大道', '成都', '四川', '交通银行', '6222601234567895', '91510000MA4XXXXX', 4, 6, 1500, 2200000.00, 1),
('武汉光谷光电', 'SUP-007', '孙浩然', '027-66660002', 'sun@optics.com', '东湖高新区', '武汉', '湖北', '浦发银行', '6225201234567896', '91420000MA5XXXXX', 5, 3, 900, 4500000.00, 1),
('南京紫金新材料', 'SUP-008', '周婷', '025-55550002', 'zhou@zijin.com', '江宁区陵街道', '南京', '江苏', '民生银行', '6226201234567897', '91320000MA6XXXXX', 4, 1, 200, 900000.00, 1),
('西安丝路贸易', 'SUP-009', '吴磊', '029-44440001', 'wu@silkroad.com', '雁塔区高新路', '西安', '陕西', '兴业银行', '6229001234567898', '91610000MA7XXXXX', 3, 2, 600, 1500000.00, 1),
('重庆山城机械', 'SUP-010', '郑秀英', '023-33330002', 'zheng@shancheng.com', '渝北区空港', '重庆', '重庆', '光大银行', '6226701234567899', '91500000MA8XXXXX', 4, 5, 1100, 6700000.00, 1),
('天津渤海化工', 'SUP-011', '冯国强', '022-22220001', 'feng@bohai.com', '滨海新区', '天津', '天津', '华夏银行', '6226301234567900', '91120000MA9XXXXX', 3, 3, 700, 3100000.00, 1),
('苏州工业园区', 'SUP-012', '蒋丽', '0512-66660003', 'jiang@suzhou.com', '工业园区星湖街', '苏州', '江苏', '中信银行', '6226801234567901', '91320500MAXXXXXX', 5, 4, 1800, 9200000.00, 1),
('青岛海尔供应链', 'SUP-013', '韩冬', '0532-88880003', 'han@haier.com', '山区海尔路', '青岛', '山东', '平安银行', '6222801234567902', '91370200MAYXXXXX', 5, 8, 5000, 25000000.00, 1),
('长沙湘电集团', 'SUP-014', '唐晓峰', '0731-88880004', 'tang@xiangdian.com', '岳麓区麓谷', '长沙', '湖南', '北京银行', '6221001234567903', '91430000MAZXXXXX', 4, 2, 400, 2800000.00, 1),
('厦门鹭岛食品', 'SUP-015', '许文静', '0592-55550003', 'xu@ludao.com', '思明区鹭江道', '厦门', '福建', '宁波银行', '6223201234567904', '91350200MB0XXXXX', 3, 1, 300, 600000.00, 1),
('合肥科大智能', 'SUP-016', '邓超', '0551-66660004', 'deng@kdsmart.com', '高新区创新大道', '合肥', '安徽', '杭州银行', '6223301234567905', '91340000MB1XXXXX', 4, 3, 850, 4100000.00, 1),
('济南齐鲁制药', 'SUP-017', '傅明', '0531-88880005', 'fu@qilu.com', '高新区舜华路', '济南', '山东', '上海银行', '6223401234567906', '91370000MB2XXXXX', 5, 6, 2200, 15000000.00, 1),
('郑州中原电子', 'SUP-018', '沈佳', '0371-66660005', 'shen@zydz.com', '郑东新区', '郑州', '河南', '江苏银行', '6223501234567907', '91410000MB3XXXXX', 3, 2, 550, 1900000.00, 1),
('大连星海软件', 'SUP-019', '卢志远', '0411-88880006', 'lu@xhsoft.com', '高新区黄浦路', '大连', '辽宁', '渤海银行', '6223601234567908', '91210200MB4XXXXX', 4, 4, 1000, 5500000.00, 1),
('昆明春城花卉', 'SUP-020', '蔡芳', '0871-66660006', 'cai@chuncheng.com', '呈贡区斗南', '昆明', '云南', '恒丰银行', '6223701234567909', '91530000MB5XXXXX', 3, 1, 150, 300000.00, 1),
('贵阳大数据中心', 'SUP-021', '贾鹏飞', '0851-88880007', 'jia@gzbigdata.com', '观山湖区', '贵阳', '贵州', '浙商银行', '6223801234567910', '91520000MB6XXXXX', 4, 2, 400, 2100000.00, 1),
('兰州黄河能源', 'SUP-022', '丁伟', '0931-88880008', 'ding@hhenergy.com', '城关区东岗路', '兰州', '甘肃', '广发银行', '6223901234567911', '91620000MB7XXXXX', 3, 3, 600, 7800000.00, 1),
('呼和浩特草原乳业', 'SUP-023', '魏雪', '0471-66660007', 'wei@grassland.com', '赛罕区大学东街', '呼和浩特', '内蒙古', '邮储银行', '6221801234567912', '91150000MB8XXXXX', 4, 5, 1300, 4200000.00, 1),
('乌鲁木齐天山矿业', 'SUP-024', '薛刚', '0991-88880009', 'xue@tianshan.com', '新市区北京路', '乌鲁木齐', '新疆', '进出口银行', '6221901234567913', '91650000MB9XXXXX', 3, 2, 200, 18000000.00, 1),
('拉萨高原生物', 'SUP-025', '叶琳', '0891-66660008', 'ye@plateau.com', '城关区民族路', '拉萨', '西藏', '农业发展银行', '6221701234567914', '91540000MC0XXXXX', 4, 1, 80, 500000.00, 1),
('银川塞外农业', 'SUP-026', '余海', '0951-66660009', 'yu@saiwai.com', '金凤区正源北街', '银川', '宁夏', '国家开发银行', '6221601234567915', '91640000MC1XXXXX', 3, 2, 350, 1200000.00, 1),
('西宁青藏高原', 'SUP-027', '潘丽', '0971-66660010', 'pan@qingzang.com', '城中区西大街', '西宁', '青海', '进出口银行', '6221501234567916', '91630000MC2XXXXX', 4, 3, 250, 900000.00, 1),
('海口椰岛贸易', 'SUP-028', '董建华', '0898-66660011', 'dong@yedao.com', '龙华区国贸路', '海口', '海南', '渣打银行', '6221401234567917', '91460000MC3XXXXX', 3, 1, 180, 700000.00, 1),
('南宁绿城环保', 'SUP-029', '程晓东', '0771-66660012', 'cheng@lcheng.com', '青秀区民族大道', '南宁', '广西', '汇丰银行', '6221301234567918', '91450000MC4XXXXX', 4, 2, 420, 1600000.00, 1),
('石家庄冀中能源', 'SUP-030', '曹文', '0311-88880010', 'cao@jizhong.com', '长安区中山东路', '石家庄', '河北', '东亚银行', '6221201234567919', '91130000MC5XXXXX', 3, 4, 900, 11000000.00, 1);

-- 商品分类 (30 条，含多级)
INSERT INTO categories (name, parent_id, level, sort_order, description, product_count, is_visible) VALUES
('电子产品', 0, 1, 1, '各类电子产品', 0, 1),
('手机', 1, 2, 1, '智能手机', 0, 1),
('笔记本', 1, 2, 2, '笔记本电脑', 0, 1),
('平板', 1, 2, 3, '平板电脑', 0, 1),
('耳机', 1, 2, 4, '耳机耳塞', 0, 1),
('手表', 1, 2, 5, '智能手表', 0, 1),
('相机', 1, 2, 6, '数码相机', 0, 1),
('家电', 0, 1, 2, '家用电器', 0, 1),
('电视', 8, 2, 1, '智能电视', 0, 1),
('冰箱', 8, 2, 2, '电冰箱', 0, 1),
('洗衣机', 8, 2, 3, '洗衣机', 0, 1),
('空调', 8, 2, 4, '空调设备', 0, 1),
('服装', 0, 1, 3, '服装鞋帽', 0, 1),
('男装', 13, 2, 1, '男士服装', 0, 1),
('女装', 13, 2, 2, '女士服装', 0, 1),
('童装', 13, 2, 3, '儿童服装', 0, 1),
('运动', 0, 1, 4, '运动户外', 0, 1),
('健身器材', 17, 2, 1, '健身器械', 0, 1),
('户外装备', 17, 2, 2, '户外用品', 0, 1),
('球类', 17, 2, 3, '球类运动', 0, 1),
('图书', 0, 1, 5, '图书音像', 0, 1),
('科技', 21, 2, 1, '科技类图书', 0, 1),
('文学', 21, 2, 2, '文学小说', 0, 1),
('教育', 21, 2, 3, '教育教材', 0, 1),
('食品', 0, 1, 6, '食品饮料', 0, 1),
('零食', 25, 2, 1, '休闲零食', 0, 1),
('饮料', 25, 2, 2, '饮品茶水', 0, 1),
('生鲜', 25, 2, 3, '生鲜果蔬', 0, 1),
('家居', 0, 1, 7, '家居家装', 0, 1),
('家具', 29, 2, 1, '家具家饰', 0, 1);

-- 商品数据 (100 条)
DELIMITER //
CREATE PROCEDURE generate_products()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_name VARCHAR(100);
    DECLARE v_cat VARCHAR(50);
    DECLARE v_sub VARCHAR(50);
    DECLARE v_brand VARCHAR(50);
    DECLARE v_price DECIMAL(10,2);
    DECLARE v_stock INT;
    DECLARE v_supplier INT;
    
    WHILE i <= 100 DO
        SET v_cat = CASE (i % 7)
            WHEN 0 THEN '手机' WHEN 1 THEN '笔记本' WHEN 2 THEN '平板'
            WHEN 3 THEN '耳机' WHEN 4 THEN '手表' WHEN 5 THEN '电视'
            ELSE '冰箱' END;
        SET v_sub = v_cat;
        SET v_brand = CASE (i % 8)
            WHEN 0 THEN 'Apple' WHEN 1 THEN '华为' WHEN 2 THEN '小米'
            WHEN 3 THEN '三星' WHEN 4 THEN '索尼' WHEN 5 THEN '联想'
            WHEN 6 THEN '戴尔' ELSE 'OPPO' END;
        SET v_name = CONCAT(v_brand, ' ', v_cat, ' Pro ', i);
        SET v_price = 999 + (i * 137) % 14000;
        SET v_stock = 10 + (i * 7) % 500;
        SET v_supplier = (i % 30) + 1;
        
        INSERT INTO products (name, sku, category, sub_category, brand, price, cost_price, stock, weight, supplier_id, warranty_months, description, status, sales_count, rating)
        VALUES (
            v_name,
            CONCAT('SKU-', LPAD(i, 6, '0')),
            v_cat, v_sub, v_brand, v_price, v_price * 0.6, v_stock,
            0.1 + (i % 50) * 0.1, v_supplier, 12,
            CONCAT(v_brand, v_cat, '高性能版本，第', i, '代产品'),
            1, (i * 3) % 5000, 3.5 + (i % 15) * 0.1
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_products();
DROP PROCEDURE generate_products;

-- 订单数据 (500 条)
DELIMITER //
CREATE PROCEDURE generate_orders()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_user INT;
    DECLARE v_amount DECIMAL(12,2);
    DECLARE v_status VARCHAR(20);
    DECLARE v_pay_method VARCHAR(20);
    DECLARE v_status_list VARCHAR(100) DEFAULT '待支付,已支付,已发货,已完成,已取消';
    DECLARE v_pay_list VARCHAR(100) DEFAULT '微信,支付宝,银行卡';
    
    WHILE i <= 500 DO
        SET v_user = (i % 200) + 1;
        SET v_amount = 100 + (i * 237) % 50000;
        SET v_status = CASE (i % 5)
            WHEN 0 THEN '待支付' WHEN 1 THEN '已支付' WHEN 2 THEN '已发货'
            WHEN 3 THEN '已完成' ELSE '已取消' END;
        SET v_pay_method = CASE (i % 3)
            WHEN 0 THEN '微信' WHEN 1 THEN '支付宝' ELSE '银行卡' END;
        
        INSERT INTO orders (order_no, user_id, amount, discount_amount, shipping_fee, status, payment_method, payment_at, shipping_address, shipping_company, tracking_no, shipped_at, completed_at, remark)
        VALUES (
            CONCAT('ORD', DATE_FORMAT('2024-01-01' + INTERVAL i DAY, '%Y%m%d'), LPAD(i, 6, '0')),
            v_user, v_amount, v_amount * 0.05, 10 + (i % 20),
            v_status, v_pay_method,
            IF(i % 5 != 0 AND i % 5 != 4, '2024-01-01' + INTERVAL i DAY + INTERVAL 1 HOUR, NULL),
            CONCAT('北京市朝阳区建国路', (i % 200) + 1, '号'),
            CASE (i % 3) WHEN 0 THEN '顺丰' WHEN 1 THEN '京东物流' ELSE '中通' END,
            CONCAT('SF', LPAD(i * 11 % 10000000000, 10, '0')),
            IF(i % 5 = 2 OR i % 5 = 3, '2024-01-01' + INTERVAL i DAY + INTERVAL 2 DAY, NULL),
            IF(i % 5 = 3, '2024-01-01' + INTERVAL i DAY + INTERVAL 5 DAY, NULL),
            CONCAT('测试订单备注', i)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_orders();
DROP PROCEDURE generate_orders;

-- 订单明细 (1500 条)
DELIMITER //
CREATE PROCEDURE generate_order_items()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_order INT;
    DECLARE v_product INT;
    DECLARE v_qty INT;
    DECLARE v_price DECIMAL(10,2);
    
    WHILE i <= 1500 DO
        SET v_order = (i % 500) + 1;
        SET v_product = (i % 100) + 1;
        SET v_qty = 1 + (i % 5);
        SET v_price = 999 + (v_product * 137) % 14000;
        
        INSERT INTO order_items (order_id, product_id, product_name, sku, quantity, unit_price, discount_rate, subtotal, tax_rate, tax_amount)
        VALUES (
            v_order, v_product,
            CONCAT('商品', v_product),
            CONCAT('SKU-', LPAD(v_product, 6, '0')),
            v_qty, v_price, 0.95,
            v_qty * v_price * 0.95,
            0.13, v_qty * v_price * 0.13
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_order_items();
DROP PROCEDURE generate_order_items;

-- 库存变动日志 (500 条)
DELIMITER //
CREATE PROCEDURE generate_inventory_logs()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_type VARCHAR(20);
    
    WHILE i <= 500 DO
        SET v_type = CASE (i % 4)
            WHEN 0 THEN '入库' WHEN 1 THEN '出库' WHEN 2 THEN '盘点' ELSE '调整' END;
        
        INSERT INTO inventory_logs (product_id, warehouse_id, type, quantity, before_stock, after_stock, reference_type, reference_id, operator_id, operator_name, reason, batch_no)
        VALUES (
            (i % 100) + 1, (i % 5) + 1, v_type,
            CASE WHEN v_type = '入库' THEN 10 + (i % 50) WHEN v_type = '出库' THEN -(1 + (i % 5)) ELSE 0 END,
            100 + (i * 3) % 400,
            100 + (i * 3) % 400 + CASE WHEN v_type = '入库' THEN 10 + (i % 50) WHEN v_type = '出库' THEN -(1 + (i % 5)) ELSE 0 END,
            CASE (i % 3) WHEN 0 THEN '订单' WHEN 1 THEN '采购' ELSE '退货' END,
            (i % 500) + 1, (i % 200) + 1, CONCAT('操作员', (i % 200) + 1),
            CONCAT('自动', v_type, '操作'),
            CONCAT('BATCH-', LPAD(i, 8, '0'))
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_inventory_logs();
DROP PROCEDURE generate_inventory_logs;

-- 客服工单 (200 条)
DELIMITER //
CREATE PROCEDURE generate_tickets()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_type VARCHAR(30);
    DECLARE v_status VARCHAR(20);
    
    WHILE i <= 200 DO
        SET v_type = CASE (i % 4) WHEN 0 THEN '投诉' WHEN 1 THEN '咨询' WHEN 2 THEN '售后' ELSE '建议' END;
        SET v_status = CASE (i % 4) WHEN 0 THEN '待处理' WHEN 1 THEN '处理中' WHEN 2 THEN '已解决' ELSE '已关闭' END;
        
        INSERT INTO customer_service_tickets (ticket_no, user_id, order_id, type, subject, content, priority, status, assigned_to, satisfaction_score, user_feedback)
        VALUES (
            CONCAT('TKT', LPAD(i, 8, '0')),
            (i % 200) + 1, IF(i % 2 = 0, (i % 500) + 1, NULL),
            v_type,
            CONCAT(v_type, '工单-', i),
            CONCAT('这是第', i, '条客服工单的内容描述，用户反馈了相关问题。'),
            1 + (i % 3), v_status,
            IF(i % 3 != 0, (i % 50) + 1, NULL),
            IF(i % 4 = 2, 3 + (i % 3), NULL),
            IF(i % 4 = 2, CONCAT('用户满意度反馈第', i, '条'), NULL)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_tickets();
DROP PROCEDURE generate_tickets;

-- 促销活动 (30 条)
INSERT INTO promotions (name, type, description, start_time, end_time, discount_rate, min_amount, max_discount, total_budget, used_budget, participant_count, order_count, rules, status) VALUES
('元旦大促', '满减', '元旦全场满减活动', '2024-01-01 00:00:00', '2024-01-03 23:59:59', 0.85, 200, 500, 1000000, 856000, 12000, 8500, '满200减15%', 2),
('春节特惠', '折扣', '春节全场折扣', '2024-02-10 00:00:00', '2024-02-17 23:59:59', 0.80, 100, 1000, 2000000, 1800000, 25000, 18000, '全场8折', 2),
('情人节专场', '满减', '情人节专属优惠', '2024-02-14 00:00:00', '2024-02-14 23:59:59', 0.90, 300, 200, 500000, 420000, 5000, 3200, '满300减10%', 2),
('38女神节', '折扣', '女性用户专享', '2024-03-08 00:00:00', '2024-03-10 23:59:59', 0.75, 0, 500, 800000, 750000, 15000, 12000, '女性用户75折', 2),
('清明踏青', '满减', '清明户外用品优惠', '2024-04-04 00:00:00', '2024-04-06 23:59:59', 0.88, 150, 300, 300000, 280000, 3000, 2500, '满150减12%', 2),
('五一黄金周', '秒杀', '五一限时秒杀', '2024-05-01 00:00:00', '2024-05-05 23:59:59', 0.50, 0, 2000, 5000000, 4800000, 50000, 35000, '限量秒杀5折', 2),
('618年中大促', '满减', '年中最大促销', '2024-06-18 00:00:00', '2024-06-20 23:59:59', 0.70, 500, 2000, 10000000, 9500000, 100000, 80000, '满500减30%', 2),
('暑期狂欢', '折扣', '暑期学生优惠', '2024-07-01 00:00:00', '2024-08-31 23:59:59', 0.85, 100, 800, 3000000, 2100000, 30000, 22000, '学生认证85折', 2),
('开学季', '满减', '开学文具优惠', '2024-09-01 00:00:00', '2024-09-10 23:59:59', 0.90, 50, 100, 500000, 450000, 8000, 6500, '满50减10%', 2),
('中秋团圆', '折扣', '中秋礼盒折扣', '2024-09-17 00:00:00', '2024-09-17 23:59:59', 0.80, 200, 500, 1000000, 890000, 12000, 9000, '礼盒8折', 2),
('国庆盛典', '秒杀', '国庆限时抢购', '2024-10-01 00:00:00', '2024-10-07 23:59:59', 0.60, 0, 3000, 8000000, 0, 0, 0, '国庆6折秒杀', 0),
('双11狂欢', '满减', '年度最大促销', '2024-11-11 00:00:00', '2024-11-11 23:59:59', 0.50, 1000, 5000, 20000000, 0, 0, 0, '满1000减50%', 0),
('双12返场', '折扣', '双11返场优惠', '2024-12-12 00:00:00', '2024-12-12 23:59:59', 0.65, 500, 3000, 10000000, 0, 0, 0, '全场65折', 0),
('圣诞特惠', '拼团', '圣诞拼团活动', '2024-12-25 00:00:00', '2024-12-25 23:59:59', 0.70, 300, 1000, 2000000, 0, 0, 0, '3人拼团7折', 0),
('跨年晚会', '满减', '跨年倒计时优惠', '2024-12-31 00:00:00', '2025-01-01 23:59:59', 0.75, 200, 800, 3000000, 0, 0, 0, '满200减25%', 0);

-- 优惠券 (50 条)
DELIMITER //
CREATE PROCEDURE generate_coupons()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_type VARCHAR(20);
    DECLARE v_name VARCHAR(100);
    
    WHILE i <= 50 DO
        SET v_type = CASE (i % 3) WHEN 0 THEN '满减' WHEN 1 THEN '折扣' ELSE '免邮' END;
        SET v_name = CASE (i % 5)
            WHEN 0 THEN '新人专享券' WHEN 1 THEN '会员日券' WHEN 2 THEN '生日券'
            WHEN 3 THEN '节日券' ELSE '通用券' END;
        
        INSERT INTO coupons (code, name, type, face_value, discount_rate, min_amount, max_discount, total_count, used_count, per_user_limit, valid_days, applicable_categories, status)
        VALUES (
            CONCAT('CPN', LPAD(i, 6, '0')),
            CONCAT(v_name, '-', i),
            v_type,
            IF(v_type = '满减', 10 + (i % 20) * 5, NULL),
            IF(v_type = '折扣', 0.7 + (i % 3) * 0.1, NULL),
            50 + (i % 10) * 50,
            IF(v_type = '满减', 50 + (i % 10) * 20, NULL),
            1000 + (i % 5) * 500,
            (i * 7) % 800,
            1 + (i % 3),
            7 + (i % 23),
            CASE (i % 4) WHEN 0 THEN '电子产品' WHEN 1 THEN '服装' WHEN 2 THEN '食品' ELSE NULL END,
            1
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_coupons();
DROP PROCEDURE generate_coupons;

-- 用户优惠券 (500 条)
DELIMITER //
CREATE PROCEDURE generate_user_coupons()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_status VARCHAR(20);
    
    WHILE i <= 500 DO
        SET v_status = CASE (i % 3) WHEN 0 THEN '未使用' WHEN 1 THEN '已使用' ELSE '已过期' END;
        
        INSERT INTO user_coupons (user_id, coupon_id, coupon_code, status, used_order_id, received_at, expired_at)
        VALUES (
            (i % 200) + 1, (i % 50) + 1,
            CONCAT('CPN', LPAD((i % 50) + 1, 6, '0')),
            v_status,
            IF(v_status = '已使用', (i % 500) + 1, NULL),
            '2024-01-01' + INTERVAL (i % 365) DAY,
            '2024-01-01' + INTERVAL (i % 365) + 30 DAY
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_user_coupons();
DROP PROCEDURE generate_user_coupons;

-- 收货地址 (400 条)
DELIMITER //
CREATE PROCEDURE generate_addresses()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_provinces VARCHAR(500) DEFAULT '北京市,上海市,广东省,浙江省,江苏省,四川省,湖北省,湖南省,山东省,河南省';
    
    WHILE i <= 400 DO
        INSERT INTO addresses (user_id, receiver_name, receiver_phone, province, city, district, detail_address, postal_code, is_default, tag)
        VALUES (
            (i % 200) + 1,
            CONCAT('收件人', i),
            CONCAT('1', 30 + (i % 70), LPAD(i % 100000000, 8, '0')),
            CASE (i % 10) WHEN 0 THEN '北京市' WHEN 1 THEN '上海市' WHEN 2 THEN '广东省' WHEN 3 THEN '浙江省' WHEN 4 THEN '江苏省' WHEN 5 THEN '四川省' WHEN 6 THEN '湖北省' WHEN 7 THEN '湖南省' WHEN 8 THEN '山东省' ELSE '河南省' END,
            CASE (i % 10) WHEN 0 THEN '北京市' WHEN 1 THEN '上海市' WHEN 2 THEN '广州市' WHEN 3 THEN '杭州市' WHEN 4 THEN '南京市' WHEN 5 THEN '成都市' WHEN 6 THEN '武汉市' WHEN 7 THEN '长沙市' WHEN 8 THEN '济南市' ELSE '郑州市' END,
            CASE (i % 10) WHEN 0 THEN '朝阳区' WHEN 1 THEN '浦东新区' WHEN 2 THEN '天河区' WHEN 3 THEN '西湖区' WHEN 4 THEN '玄武区' WHEN 5 THEN '锦江区' WHEN 6 THEN '武昌区' WHEN 7 THEN '岳麓区' WHEN 8 THEN '历下区' ELSE '金水区' END,
            CONCAT('建国路', i, '号', (i % 30) + 1, '室'),
            CONCAT('10000', i % 10),
            IF(i % 5 = 0, 1, 0),
            CASE (i % 3) WHEN 0 THEN '家' WHEN 1 THEN '公司' ELSE '学校' END
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_addresses();
DROP PROCEDURE generate_addresses;

-- 商品评价 (300 条)
DELIMITER //
CREATE PROCEDURE generate_reviews()
BEGIN
    DECLARE i INT DEFAULT 1;
    
    WHILE i <= 300 DO
        INSERT INTO product_reviews (product_id, user_id, order_id, rating, title, content, is_anonymous, likes_count, reply_content, status)
        VALUES (
            (i % 100) + 1, (i % 200) + 1, (i % 500) + 1,
            3 + (i % 3),
            CASE (i % 5) WHEN 0 THEN '非常好用' WHEN 1 THEN '性价比很高' WHEN 2 THEN '质量不错' WHEN 3 THEN '一般般' ELSE '不太满意' END,
            CONCAT('这是第', i, '条商品评价，整体使用感受还不错，推荐购买。'),
            i % 4, (i * 3) % 100,
            IF(i % 3 = 0, CONCAT('感谢评价，我们会继续努力！第', i, '条回复'), NULL),
            1
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_reviews();
DROP PROCEDURE generate_reviews;

-- 系统配置 (50 条)
INSERT INTO system_config (config_key, config_value, config_type, group_name, description, is_encrypted, is_system, scope) VALUES
('site.name', 'NL2SQL智能查询系统', 'string', 'basic', '站点名称', 0, 1, 'global'),
('site.logo', '/assets/logo.png', 'string', 'basic', '站点Logo', 0, 1, 'global'),
('site.icp', '京ICP备XXXXXXXX号', 'string', 'basic', 'ICP备案号', 0, 1, 'global'),
('mail.host', 'smtp.company.com', 'string', 'mail', '邮件服务器', 0, 0, 'global'),
('mail.port', '465', 'number', 'mail', '邮件端口', 0, 0, 'global'),
('mail.username', 'noreply@company.com', 'string', 'mail', '发件人邮箱', 0, 0, 'global'),
('mail.password', 'encrypted_password_hash', 'string', 'mail', '发件人密码', 1, 0, 'global'),
('sms.provider', 'aliyun', 'string', 'sms', '短信服务商', 0, 0, 'global'),
('sms.access_key', 'encrypted_ak', 'string', 'sms', '短信AK', 1, 0, 'global'),
('sms.secret_key', 'encrypted_sk', 'string', 'sms', '短信SK', 1, 0, 'global'),
('upload.max_size', '10485760', 'number', 'upload', '最大上传大小(字节)', 0, 0, 'global'),
('upload.allowed_types', 'jpg,png,gif,pdf,doc,xlsx', 'string', 'upload', '允许的文件类型', 0, 0, 'global'),
('upload.storage_type', 'local', 'string', 'upload', '存储类型', 0, 0, 'global'),
('cache.default_ttl', '3600', 'number', 'cache', '默认缓存时间(秒)', 0, 0, 'global'),
('cache.max_memory', '536870912', 'number', 'cache', '最大缓存内存(字节)', 0, 0, 'global'),
('security.login_max_fail', '5', 'number', 'security', '最大登录失败次数', 0, 1, 'global'),
('security.login_lock_minutes', '30', 'number', 'security', '登录锁定时间(分钟)', 0, 1, 'global'),
('security.password_min_length', '8', 'number', 'security', '密码最小长度', 0, 1, 'global'),
('security.session_timeout', '7200', 'number', 'security', '会话超时(秒)', 0, 1, 'global'),
('security.cors_origins', 'http://localhost:5174', 'string', 'security', 'CORS允许来源', 0, 0, 'global'),
('payment.alipay.app_id', '2021001234567890', 'string', 'payment', '支付宝AppID', 0, 0, 'global'),
('payment.alipay.private_key', 'encrypted_alipay_pk', 'string', 'payment', '支付宝私钥', 1, 0, 'global'),
('payment.wechat.mch_id', '1234567890', 'string', 'payment', '微信商户号', 0, 0, 'global'),
('payment.wechat.api_key', 'encrypted_wechat_key', 'string', 'payment', '微信API密钥', 1, 0, 'global'),
('log.level', 'INFO', 'string', 'log', '日志级别', 0, 0, 'global'),
('log.retention_days', '90', 'number', 'log', '日志保留天数', 0, 0, 'global'),
('log.export_enabled', 'true', 'boolean', 'log', '是否启用日志导出', 0, 0, 'global'),
('notification.email_enabled', 'true', 'boolean', 'notification', '邮件通知开关', 0, 0, 'global'),
('notification.sms_enabled', 'true', 'boolean', 'notification', '短信通知开关', 0, 0, 'global'),
('notification.push_enabled', 'false', 'boolean', 'notification', '推送通知开关', 0, 0, 'global'),
('order.auto_cancel_minutes', '30', 'number', 'order', '订单自动取消时间(分钟)', 0, 0, 'global'),
('order.auto_confirm_days', '7', 'number', 'order', '订单自动确认天数', 0, 0, 'global'),
('order.max_refund_days', '15', 'number', 'order', '最大退款天数', 0, 0, 'global'),
('product.default_rating', '5.0', 'number', 'product', '商品默认评分', 0, 0, 'global'),
('product.review_audit', 'true', 'boolean', 'product', '评价是否需要审核', 0, 0, 'global'),
('user.default_avatar', '/assets/default-avatar.png', 'string', 'user', '默认头像', 0, 0, 'global'),
('user.register_enabled', 'true', 'boolean', 'user', '是否开放注册', 0, 1, 'global'),
('user.email_verify', 'false', 'boolean', 'user', '注册是否需要邮箱验证', 0, 0, 'global'),
('seo.enabled', 'true', 'boolean', 'seo', 'SEO开关', 0, 0, 'global'),
('seo.baidu_verify', 'baidu_verify_code_xxxxxx', 'string', 'seo', '百度验证', 0, 0, 'global'),
('backup.enabled', 'true', 'boolean', 'backup', '自动备份开关', 0, 0, 'global'),
('backup.cron', '0 2 * * *', 'string', 'backup', '备份Cron', 0, 0, 'global'),
('backup.retention', '30', 'number', 'backup', '备份保留天数', 0, 0, 'global'),
('backup.path', '/data/backups', 'string', 'backup', '备份路径', 0, 0, 'global'),
('monitor.enabled', 'true', 'boolean', 'monitor', '监控开关', 0, 0, 'global'),
('monitor.alert_email', 'admin@company.com', 'string', 'monitor', '告警邮箱', 0, 0, 'global'),
('monitor.cpu_threshold', '80', 'number', 'monitor', 'CPU告警阈值', 0, 0, 'global'),
('monitor.memory_threshold', '85', 'number', 'monitor', '内存告警阈值', 0, 0, 'global'),
('monitor.disk_threshold', '90', 'number', 'monitor', '磁盘告警阈值', 0, 0, 'global'),
('feature.new_checkout', 'true', 'boolean', 'feature', '新结账流程开关', 0, 0, 'global');

-- 操作日志 (1000 条)
DELIMITER //
CREATE PROCEDURE generate_operation_logs()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_module VARCHAR(50);
    DECLARE v_action VARCHAR(50);
    
    WHILE i <= 1000 DO
        SET v_module = CASE (i % 6) WHEN 0 THEN '用户管理' WHEN 1 THEN '订单管理' WHEN 2 THEN '商品管理' WHEN 3 THEN '系统设置' WHEN 4 THEN '权限管理' ELSE '数据导出' END;
        SET v_action = CASE (i % 5) WHEN 0 THEN '创建' WHEN 1 THEN '修改' WHEN 2 THEN '删除' WHEN 3 THEN '查询' ELSE '导出' END;
        
        INSERT INTO operation_logs (trace_id, user_id, username, module, action, method, request_url, request_method, request_params, response_body, ip_address, user_agent, duration_ms, status, error_message)
        VALUES (
            CONCAT('trace-', LPAD(i, 12, '0')),
            (i % 200) + 1, CONCAT('user', (i % 200) + 1),
            v_module, v_action,
            CONCAT('com.company.controller.', CASE (i % 6) WHEN 0 THEN 'User' WHEN 1 THEN 'Order' WHEN 2 THEN 'Product' WHEN 3 THEN 'System' WHEN 4 THEN 'Permission' ELSE 'Export' END, 'Controller.', v_action),
            CONCAT('/api/v1/', CASE (i % 6) WHEN 0 THEN 'users' WHEN 1 THEN 'orders' WHEN 2 THEN 'products' WHEN 3 THEN 'config' WHEN 4 THEN 'roles' ELSE 'export' END, IF(v_action IN ('创建','修改','删除'), '/', CONCAT('/', i % 100))),
            CASE (i % 5) WHEN 0 THEN 'POST' WHEN 1 THEN 'PUT' WHEN 2 THEN 'DELETE' ELSE 'GET' END,
            CONCAT('{"id":', i % 100, ',"name":"test', i, '"}'),
            CONCAT('{"code":200,"message":"success","data":{"id":', i % 100, '}}'),
            CONCAT('192.168.1.', (i % 254) + 1),
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            10 + (i % 500),
            IF(i % 20 = 0, 0, 1),
            IF(i % 20 = 0, CONCAT('操作失败: 数据不存在 id=', i % 100), NULL)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_operation_logs();
DROP PROCEDURE generate_operation_logs;

-- 定时任务 (20 条)
INSERT INTO scheduled_tasks (task_name, task_group, bean_name, method_name, method_params, cron_expression, misfire_policy, concurrent, status, execute_count, error_count, description) VALUES
('订单自动取消', 'order', 'orderTask', 'autoCancel', '{"timeout":30}', '0 */5 * * * ?', 1, 0, 1, 8640, 12, '超时未支付订单自动取消'),
('订单自动确认', 'order', 'orderTask', 'autoConfirm', '{"days":7}', '0 0 2 * * ?', 1, 0, 1, 365, 0, '发货7天后自动确认收货'),
('库存预警检查', 'inventory', 'inventoryTask', 'checkWarning', '{"threshold":10}', '0 0 8 * * ?', 2, 0, 1, 365, 3, '库存低于阈值时发送预警'),
('缓存清理', 'system', 'cacheTask', 'cleanExpired', '{}', '0 0 3 * * ?', 1, 0, 1, 365, 0, '清理过期缓存数据'),
('日志归档', 'system', 'logTask', 'archive', '{"retentionDays":90}', '0 30 1 * * ?', 1, 0, 1, 365, 1, '归档90天前的日志'),
('数据库备份', 'system', 'backupTask', 'execute', '{"path":"/data/backups"}', '0 0 2 * * 0', 3, 0, 1, 52, 0, '每周日凌晨2点备份数据库'),
('优惠券过期处理', 'promotion', 'couponTask', 'expireCheck', '{}', '0 0 0 * * ?', 1, 0, 1, 365, 0, '每日零点标记过期优惠券'),
('促销活动状态更新', 'promotion', 'promotionTask', 'updateStatus', '{}', '0 */10 * * * ?', 1, 0, 1, 52560, 5, '每10分钟更新活动状态'),
('用户积分结算', 'user', 'pointsTask', 'settle', '{"cycle":"monthly"}', '0 0 1 1 * ?', 1, 0, 1, 12, 0, '每月1日结算用户积分'),
('会员等级评估', 'user', 'memberTask', 'evaluateLevel', '{}', '0 0 3 1 * ?', 1, 0, 1, 12, 0, '每月1日评估会员等级'),
('数据同步', 'data', 'syncTask', 'fullSync', '{"target":"warehouse"}', '0 0 4 * * ?', 2, 0, 0, 0, 0, '每日4点全量数据同步'),
('报表生成', 'report', 'reportTask', 'generateDaily', '{"type":"sales"}', '0 0 6 * * ?', 1, 0, 1, 365, 2, '每日6点生成销售报表'),
('邮件发送', 'notification', 'emailTask', 'sendBatch', '{"batchSize":100}', '0 */15 * * * ?', 1, 0, 1, 35040, 120, '每15分钟批量发送邮件'),
('短信发送', 'notification', 'smsTask', 'sendBatch', '{"batchSize":50}', '0 */15 * * * ?', 1, 0, 1, 35040, 80, '每15分钟批量发送短信'),
('文件清理', 'system', 'fileTask', 'cleanTemp', '{"maxAge":7}', '0 0 5 * * ?', 1, 0, 1, 365, 0, '清理7天前的临时文件'),
('SEO sitemap更新', 'marketing', 'seoTask', 'updateSitemap', '{}', '0 0 4 * * 0', 1, 0, 1, 52, 1, '每周更新sitemap'),
('安全扫描', 'security', 'securityTask', 'scan', '{"type":"full"}', '0 0 2 * * 1', 3, 0, 1, 52, 0, '每周一凌晨安全扫描'),
('性能监控', 'monitor', 'monitorTask', 'collectMetrics', '{}', '0 */5 * * * ?', 1, 1, 1, 105120, 0, '每5分钟采集性能指标'),
('索引优化', 'database', 'dbTask', 'optimizeIndexes', '{}', '0 0 3 * * 0', 3, 0, 1, 52, 0, '每周日优化数据库索引'),
('数据清洗', 'data', 'cleanTask', 'removeDuplicates', '{"table":"users"}', '0 0 4 1 * ?', 1, 0, 0, 0, 0, '每月1日清洗重复数据');

-- API访问日志 (2000 条)
DELIMITER //
CREATE PROCEDURE generate_api_logs()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_api VARCHAR(100);
    DECLARE v_path VARCHAR(100);
    
    WHILE i <= 2000 DO
        SET v_api = CASE (i % 8)
            WHEN 0 THEN '用户列表' WHEN 1 THEN '订单详情' WHEN 2 THEN '商品搜索'
            WHEN 3 THEN '登录认证' WHEN 4 THEN '数据导出' WHEN 5 THEN '配置查询'
            WHEN 6 THEN '权限校验' ELSE '消息推送' END;
        SET v_path = CASE (i % 8)
            WHEN 0 THEN '/api/v1/users' WHEN 1 THEN '/api/v1/orders/{id}' WHEN 2 THEN '/api/v1/products/search'
            WHEN 3 THEN '/api/v1/auth/login' WHEN 4 THEN '/api/v1/export' WHEN 5 THEN '/api/v1/config'
            WHEN 6 THEN '/api/v1/permissions/check' ELSE '/api/v1/notifications/push' END;
        
        INSERT INTO api_access_logs (api_name, api_path, caller_app, caller_ip, request_method, request_headers, request_body, response_status, response_headers, response_body, duration_ms, bytes_sent, bytes_received, error_type, error_detail, trace_id)
        VALUES (
            v_api, v_path,
            CASE (i % 3) WHEN 0 THEN 'web-app' WHEN 1 THEN 'mobile-app' ELSE 'admin-panel' END,
            CONCAT('10.0.', (i % 255), '.', (i % 255)),
            CASE (i % 4) WHEN 0 THEN 'GET' WHEN 1 THEN 'POST' WHEN 2 THEN 'PUT' ELSE 'DELETE' END,
            '{"Content-Type":"application/json","Authorization":"Bearer xxx"}',
            CASE (i % 4) WHEN 0 THEN NULL WHEN 1 THEN CONCAT('{"query":"test', i, '"}') WHEN 2 THEN CONCAT('{"id":', i % 100, '}') ELSE NULL END,
            CASE WHEN i % 15 = 0 THEN 500 WHEN i % 10 = 0 THEN 404 WHEN i % 5 = 0 THEN 400 ELSE 200 END,
            '{"Content-Type":"application/json"}',
            CASE WHEN i % 15 = 0 THEN '{"code":500,"message":"server error"}' WHEN i % 10 = 0 THEN '{"code":404,"message":"not found"}' ELSE CONCAT('{"code":200,"data":{"id":', i % 100, '}}') END,
            5 + (i % 200),
            100 + (i % 5000),
            50 + (i % 1000),
            CASE WHEN i % 15 = 0 THEN 'ServerError' WHEN i % 10 = 0 THEN 'NotFound' WHEN i % 5 = 0 THEN 'BadRequest' ELSE NULL END,
            CASE WHEN i % 15 = 0 THEN CONCAT('Internal error at line ', i) ELSE NULL END,
            CONCAT('api-trace-', LPAD(i, 10, '0'))
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_api_logs();
DROP PROCEDURE generate_api_logs;

-- 消息通知队列 (500 条)
DELIMITER //
CREATE PROCEDURE generate_notifications()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_type VARCHAR(20);
    DECLARE v_status VARCHAR(20);
    
    WHILE i <= 500 DO
        SET v_type = CASE (i % 4) WHEN 0 THEN 'email' WHEN 1 THEN 'sms' WHEN 2 THEN 'push' ELSE 'wechat' END;
        SET v_status = CASE (i % 5) WHEN 0 THEN 'pending' WHEN 1 THEN 'sending' WHEN 2 THEN 'sent' WHEN 3 THEN 'failed' ELSE 'sent' END;
        
        INSERT INTO notification_queue (type, recipient, template_id, template_params, subject, content, priority, retry_count, max_retries, status, business_type, business_id)
        VALUES (
            v_type,
            CASE v_type WHEN 'email' THEN CONCAT('user', i, '@company.com') WHEN 'sms' THEN CONCAT('138', LPAD(i % 100000000, 8, '0')) WHEN 'push' THEN CONCAT('device_token_', i) ELSE CONCAT('wx_openid_', i) END,
            CONCAT('TPL-', LPAD((i % 20) + 1, 4, '0')),
            CONCAT('{"orderId":', i % 500, ',"amount":', 100 + (i % 10000), '}'),
            CASE (i % 5) WHEN 0 THEN '订单通知' WHEN 1 THEN '促销提醒' WHEN 2 THEN '系统通知' WHEN 3 THEN '验证码' ELSE '物流更新' END,
            CONCAT('这是第', i, '条通知消息的内容模板。'),
            1 + (i % 5),
            IF(v_status = 'failed', 1 + (i % 3), 0),
            3, v_status,
            CASE (i % 4) WHEN 0 THEN 'order' WHEN 1 THEN 'promotion' WHEN 2 THEN 'system' ELSE 'logistics' END,
            (i % 500) + 1
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_notifications();
DROP PROCEDURE generate_notifications;

-- 文件上传记录 (300 条)
DELIMITER //
CREATE PROCEDURE generate_file_uploads()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_ext VARCHAR(10);
    DECLARE v_type VARCHAR(100);
    
    WHILE i <= 300 DO
        SET v_ext = CASE (i % 5) WHEN 0 THEN 'jpg' WHEN 1 THEN 'png' WHEN 2 THEN 'pdf' WHEN 3 THEN 'xlsx' ELSE 'docx' END;
        SET v_type = CASE (i % 5) WHEN 0 THEN 'image/jpeg' WHEN 1 THEN 'image/png' WHEN 2 THEN 'application/pdf' WHEN 3 THEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' ELSE 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' END;
        
        INSERT INTO file_uploads (file_name, original_name, file_path, file_url, file_size, file_type, file_extension, storage_type, bucket_name, md5_hash, uploader_id, uploader_name, business_type, business_id, is_public, download_count)
        VALUES (
            CONCAT('file_', LPAD(i, 8, '0'), '.', v_ext),
            CONCAT('原始文件', i, '.', v_ext),
            CONCAT('/uploads/', CASE (i % 3) WHEN 0 THEN 'images' WHEN 1 THEN 'docs' ELSE 'temp' END, '/', DATE_FORMAT('2024-01-01' + INTERVAL i DAY, '%Y/%m/%d'), '/', CONCAT('file_', LPAD(i, 8, '0'), '.', v_ext)),
            CONCAT('https://cdn.company.com/', CASE (i % 3) WHEN 0 THEN 'images' WHEN 1 THEN 'docs' ELSE 'temp' END, '/', CONCAT('file_', LPAD(i, 8, '0'), '.', v_ext)),
            10240 + (i * 1024) % 10485760,
            v_type, v_ext,
            CASE (i % 3) WHEN 0 THEN 'local' WHEN 1 THEN 'oss' ELSE 'minio' END,
            CASE (i % 3) WHEN 0 THEN 'local-bucket' WHEN 1 THEN 'oss-bucket' ELSE 'minio-bucket' END,
            MD5(CONCAT('file', i)),
            (i % 200) + 1, CONCAT('user', (i % 200) + 1),
            CASE (i % 4) WHEN 0 THEN 'product' WHEN 1 THEN 'order' WHEN 2 THEN 'user' ELSE 'general' END,
            (i % 500) + 1,
            i % 3, (i * 7) % 1000
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL generate_file_uploads();
DROP PROCEDURE generate_file_uploads;

-- 数据导入任务 (50 条)
INSERT INTO data_import_tasks (task_name, import_type, target_table, file_path, file_name, total_rows, success_rows, failed_rows, skip_rows, status, error_log, operator_id, operator_name, started_at, finished_at, duration_seconds) VALUES
('用户数据导入', 'csv', 'users', '/imports/users_20240101.csv', 'users_20240101.csv', 5000, 4980, 15, 5, 'success', NULL, 1, 'admin', '2024-01-01 10:00:00', '2024-01-01 10:05:30', 330),
('商品批量导入', 'excel', 'products', '/imports/products_batch.xlsx', 'products_batch.xlsx', 2000, 1950, 30, 20, 'success', NULL, 2, 'operator1', '2024-01-15 14:00:00', '2024-01-15 14:08:20', 500),
('订单历史迁移', 'csv', 'orders', '/imports/orders_history.csv', 'orders_history.csv', 50000, 49500, 400, 100, 'success', '部分数据格式不符', 1, 'admin', '2024-02-01 02:00:00', '2024-02-01 02:45:00', 2700),
('库存数据同步', 'json', 'inventory_logs', '/imports/stock_sync.json', 'stock_sync.json', 10000, 9800, 150, 50, 'success', NULL, 3, 'operator2', '2024-02-15 08:00:00', '2024-02-15 08:12:00', 720),
('会员数据导入', 'excel', 'users', '/imports/members.xlsx', 'members.xlsx', 3000, 2900, 80, 20, 'failed', '第2901行数据异常：邮箱格式错误', 2, 'operator1', '2024-03-01 09:00:00', '2024-03-01 09:10:00', 600),
('地址数据清洗', 'csv', 'addresses', '/imports/addresses_clean.csv', 'addresses_clean.csv', 8000, 7500, 300, 200, 'success', NULL, 1, 'admin', '2024-03-15 11:00:00', '2024-03-15 11:08:00', 480),
('评价数据迁移', 'json', 'product_reviews', '/imports/reviews.json', 'reviews.json', 15000, 14800, 150, 50, 'success', NULL, 3, 'operator2', '2024-04-01 03:00:00', '2024-04-01 03:20:00', 1200),
('供应商数据导入', 'excel', 'suppliers', '/imports/suppliers.xlsx', 'suppliers.xlsx', 500, 490, 8, 2, 'success', NULL, 2, 'operator1', '2024-04-15 15:00:00', '2024-04-15 15:02:00', 120),
('分类数据更新', 'csv', 'categories', '/imports/categories_update.csv', 'categories_update.csv', 200, 200, 0, 0, 'success', NULL, 1, 'admin', '2024-05-01 10:00:00', '2024-05-01 10:00:30', 30),
('优惠券批量生成', 'json', 'coupons', '/imports/coupons_batch.json', 'coupons_batch.json', 10000, 10000, 0, 0, 'success', NULL, 3, 'operator2', '2024-05-15 16:00:00', '2024-05-15 16:05:00', 300);

-- 缓存统计 (30 条)
INSERT INTO cache_statistics (cache_name, cache_key, hit_count, miss_count, eviction_count, entry_count, memory_bytes, avg_get_time_ms, avg_put_time_ms, ttl_seconds, refresh_policy) VALUES
('schema', 'schema:1', 15230, 120, 0, 1, 524288, 0.5, 1.2, 3600, 'ttl'),
('schema', 'schema:2', 8900, 80, 0, 1, 262144, 0.3, 0.8, 3600, 'ttl'),
('user', 'user:session:*', 45000, 500, 120, 200, 1048576, 0.2, 0.5, 7200, 'lru'),
('user', 'user:profile:*', 32000, 300, 80, 200, 524288, 0.3, 0.6, 3600, 'lru'),
('product', 'product:detail:*', 28000, 200, 50, 100, 2097152, 0.4, 1.0, 1800, 'lru'),
('product', 'product:category:*', 12000, 100, 20, 30, 131072, 0.2, 0.4, 3600, 'ttl'),
('order', 'order:status:*', 55000, 400, 200, 500, 2097152, 0.3, 0.7, 600, 'lru'),
('order', 'order:detail:*', 18000, 150, 30, 100, 1048576, 0.5, 1.1, 1800, 'lru'),
('config', 'config:system', 95000, 10, 0, 50, 65536, 0.1, 0.3, 86400, 'manual'),
('config', 'config:feature', 42000, 50, 5, 20, 32768, 0.1, 0.2, 3600, 'ttl'),
('promotion', 'promotion:active', 8000, 200, 10, 15, 262144, 0.6, 1.5, 300, 'refresh-ahead'),
('promotion', 'promotion:coupon:*', 22000, 180, 40, 50, 524288, 0.3, 0.8, 600, 'lru'),
('inventory', 'inventory:stock:*', 35000, 250, 60, 100, 1048576, 0.4, 0.9, 120, 'refresh-ahead'),
('auth', 'auth:token:*', 120000, 800, 500, 200, 4194304, 0.2, 0.4, 7200, 'lru'),
('auth', 'auth:permission:*', 65000, 400, 100, 50, 262144, 0.3, 0.6, 3600, 'lru'),
('search', 'search:product:*', 15000, 3000, 200, 50, 5242880, 2.5, 5.0, 600, 'lru'),
('search', 'search:suggest:*', 8000, 1500, 50, 30, 1048576, 1.5, 3.0, 300, 'lru'),
('notification', 'notification:unread:*', 25000, 200, 30, 200, 524288, 0.3, 0.7, 1800, 'lru'),
('report', 'report:daily:*', 3000, 50, 5, 30, 10485760, 5.0, 10.0, 86400, 'manual'),
('report', 'report:monthly:*', 500, 10, 0, 12, 20971520, 8.0, 15.0, 2592000, 'manual'),
('session', 'session:web:*', 80000, 1000, 300, 500, 8388608, 0.2, 0.5, 7200, 'lru'),
('session', 'session:mobile:*', 45000, 600, 200, 300, 4194304, 0.3, 0.6, 7200, 'lru'),
('rate_limit', 'rate_limit:api:*', 200000, 5000, 1000, 1000, 2097152, 0.1, 0.2, 60, 'lru'),
('rate_limit', 'rate_limit:login:*', 50000, 2000, 500, 500, 524288, 0.1, 0.3, 300, 'lru'),
('metadata', 'metadata:tables:*', 5000, 30, 0, 25, 1048576, 0.8, 2.0, 3600, 'ttl'),
('metadata', 'metadata:columns:*', 3000, 20, 0, 200, 2097152, 1.0, 2.5, 3600, 'ttl'),
('i18n', 'i18n:zh_CN', 30000, 5, 0, 500, 262144, 0.1, 0.2, 86400, 'manual'),
('i18n', 'i18n:en_US', 10000, 3, 0, 500, 262144, 0.1, 0.2, 86400, 'manual'),
('geo', 'geo:city:*', 2000, 100, 10, 350, 524288, 0.5, 1.0, 86400, 'ttl'),
('geo', 'geo:district:*', 1500, 80, 5, 3000, 1048576, 0.6, 1.2, 86400, 'ttl');

-- 角色权限 (15 条)
INSERT INTO permission_roles (role_name, role_code, description, data_scope, sort_order, status, is_system) VALUES
('超级管理员', 'super_admin', '拥有所有权限', 1, 1, 1, 1),
('系统管理员', 'admin', '系统管理权限', 1, 2, 1, 1),
('运营管理员', 'ops_admin', '运营管理权限', 1, 3, 1, 0),
('商品管理员', 'product_admin', '商品管理权限', 2, 4, 1, 0),
('订单管理员', 'order_admin', '订单管理权限', 2, 5, 1, 0),
('客服', 'customer_service', '客服工单处理', 3, 6, 1, 0),
('财务', 'finance', '财务数据查看', 2, 7, 1, 0),
('仓库管理员', 'warehouse', '库存管理权限', 2, 8, 1, 0),
('市场专员', 'marketing', '营销活动管理', 3, 9, 1, 0),
('数据分析师', 'analyst', '数据查看与导出', 1, 10, 1, 0),
('普通用户', 'user', '基本用户权限', 3, 11, 1, 1),
('访客', 'guest', '只读权限', 3, 12, 1, 1),
('审计员', 'auditor', '审计日志查看', 1, 13, 1, 0),
('开发人员', 'developer', '开发调试权限', 1, 14, 0, 0),
('测试人员', 'tester', '测试环境权限', 1, 15, 0, 0);

-- 菜单权限 (40 条)
INSERT INTO permission_menus (menu_name, parent_id, menu_type, path, component, permission_code, icon, sort_order, is_visible, is_cached) VALUES
('系统管理', 0, 1, '/system', NULL, NULL, 'Setting', 100, 1, 0),
('用户管理', 1, 2, '/system/users', 'system/users/index', 'system:user:list', 'User', 1, 1, 1),
('角色管理', 1, 2, '/system/roles', 'system/roles/index', 'system:role:list', 'Peoples', 2, 1, 1),
('菜单管理', 1, 2, '/system/menus', 'system/menus/index', 'system:menu:list', 'TreeTable', 3, 1, 1),
('系统配置', 1, 2, '/system/config', 'system/config/index', 'system:config:list', 'Edit', 4, 1, 1),
('操作日志', 1, 2, '/system/logs', 'system/logs/index', 'system:log:list', 'Document', 5, 1, 0),
('定时任务', 1, 2, '/system/tasks', 'system/tasks/index', 'system:task:list', 'Time', 6, 1, 1),
('商品管理', 0, 1, '/product', NULL, NULL, 'Shopping', 200, 1, 0),
('商品列表', 8, 2, '/product/list', 'product/list/index', 'product:list', 'List', 1, 1, 1),
('商品分类', 8, 2, '/product/category', 'product/category/index', 'product:category', 'Menu', 2, 1, 1),
('商品评价', 8, 2, '/product/reviews', 'product/reviews/index', 'product:review:list', 'Comment', 3, 1, 0),
('库存管理', 8, 2, '/product/inventory', 'product/inventory/index', 'product:inventory', 'Box', 4, 1, 1),
('订单管理', 0, 1, '/order', NULL, NULL, 'Order', 300, 1, 0),
('订单列表', 13, 2, '/order/list', 'order/list/index', 'order:list', 'List', 1, 1, 1),
('订单详情', 13, 2, '/order/detail', 'order/detail/index', 'order:detail', 'View', 2, 0, 0),
('退货管理', 13, 2, '/order/refund', 'order/refund/index', 'order:refund', 'RefreshLeft', 3, 1, 1),
('营销管理', 0, 1, '/marketing', NULL, NULL, 'Present', 400, 1, 0),
('促销活动', 17, 2, '/marketing/promotion', 'marketing/promotion/index', 'marketing:promotion', 'Gift', 1, 1, 1),
('优惠券', 17, 2, '/marketing/coupon', 'marketing/coupon/index', 'marketing:coupon', 'Ticket', 2, 1, 1),
('客服管理', 0, 1, '/service', NULL, NULL, 'Service', 500, 1, 0),
('工单列表', 20, 2, '/service/tickets', 'service/tickets/index', 'service:ticket:list', 'Tickets', 1, 1, 1),
('工单处理', 20, 2, '/service/handle', 'service/handle/index', 'service:ticket:handle', 'EditPen', 2, 1, 0),
('数据报表', 0, 1, '/report', NULL, NULL, 'DataAnalysis', 600, 1, 0),
('销售报表', 23, 2, '/report/sales', 'report/sales/index', 'report:sales', 'TrendCharts', 1, 1, 1),
('用户报表', 23, 2, '/report/users', 'report/users/index', 'report:users', 'User', 2, 1, 1),
('商品报表', 23, 2, '/report/products', 'report/products/index', 'report:products', 'ShoppingBag', 3, 1, 1),
('供应商管理', 0, 1, '/supplier', NULL, NULL, 'Van', 700, 1, 0),
('供应商列表', 27, 2, '/supplier/list', 'supplier/list/index', 'supplier:list', 'List', 1, 1, 1),
('供应商评估', 27, 2, '/supplier/evaluate', 'supplier/evaluate/index', 'supplier:evaluate', 'Rating', 2, 1, 1),
('仓储管理', 0, 1, '/warehouse', NULL, NULL, 'OfficeBuilding', 800, 1, 0),
('仓库列表', 30, 2, '/warehouse/list', 'warehouse/list/index', 'warehouse:list', 'List', 1, 1, 1),
('入库管理', 30, 2, '/warehouse/inbound', 'warehouse/inbound/index', 'warehouse:inbound', 'Download', 2, 1, 1),
('出库管理', 30, 2, '/warehouse/outbound', 'warehouse/outbound/index', 'warehouse:outbound', 'Upload', 3, 1, 1),
('用户新增', 2, 3, NULL, NULL, 'system:user:create', NULL, 1, 1, 0),
('用户编辑', 2, 3, NULL, NULL, 'system:user:update', NULL, 2, 1, 0),
('用户删除', 2, 3, NULL, NULL, 'system:user:delete', NULL, 3, 1, 0),
('订单导出', 14, 3, NULL, NULL, 'order:export', NULL, 1, 1, 0),
('商品上架', 9, 3, NULL, NULL, 'product:publish', NULL, 1, 1, 0),
('商品下架', 9, 3, NULL, NULL, 'product:unpublish', NULL, 2, 1, 0),
('配置修改', 5, 3, NULL, NULL, 'system:config:update', NULL, 1, 1, 0);

-- =============================================================
-- 验证：统计表数量和数据量
-- =============================================================
SELECT '=== 表数量 ===' AS info;
SELECT COUNT(*) AS table_count FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'nlp2sql_test' AND TABLE_TYPE = 'BASE TABLE';

SELECT '=== 各表行数 ===' AS info;
SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'nlp2sql_test' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_ROWS DESC;

SELECT '=== 各表列数 ===' AS info;
SELECT TABLE_NAME, COUNT(*) AS column_count FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nlp2sql_test' GROUP BY TABLE_NAME ORDER BY column_count DESC;

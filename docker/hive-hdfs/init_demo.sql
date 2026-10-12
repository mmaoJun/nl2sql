-- NL2SQL 演示数据源：Hive demo 库
-- 外表直接指向 HDFS /demo 下的 CSV 文件（Hive/HDFS 双数据源演示用）
CREATE DATABASE IF NOT EXISTS demo;

CREATE EXTERNAL TABLE IF NOT EXISTS demo.orders (
  order_id INT,
  user_id INT,
  product_id INT,
  amount DECIMAL(10,2),
  status STRING,
  city STRING,
  order_date STRING
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/demo/orders'
TBLPROPERTIES ('skip.header.line.count'='1');

CREATE EXTERNAL TABLE IF NOT EXISTS demo.products (
  product_id INT,
  product_name STRING,
  category STRING,
  price DECIMAL(10,2),
  stock INT
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/demo/products'
TBLPROPERTIES ('skip.header.line.count'='1');

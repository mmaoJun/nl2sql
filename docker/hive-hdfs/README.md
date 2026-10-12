# Hive + HDFS 单节点演示环境

W4 数据源（Hive/Spark SQL 与 Hadoop/HDFS 场景）的本地真集群，基于 Apache 官方镜像（`apache/hadoop:3.4.1` + `apache/hive:4.0.1`）。

## 启动 / 停止

```bash
cd docker/hive-hdfs
docker compose up -d        # 首次启动自动格式化 NameNode（ENSURE_NAMENODE_DIR）
docker compose down         # 停止（数据保留在卷与 ./hive-data 中）
docker compose logs -f hive # 跟踪 Hive 日志
```

首次启动后 HiveServer2 完全就绪约需 1–2 分钟（端口先开、JVM 后启）。

## 端口

| 端口 | 用途 |
|---|---|
| 8020 | HDFS RPC |
| 9870 | NameNode Web UI + WebHDFS |
| 9864 | DataNode Web UI |
| 10000 | HiveServer2（JDBC / beeline） |
| 10002 | HiveServer2 Web UI |

## 数据与表

- 演示数据：`data/*.csv`（`bash gen_data.sh` 可复现生成：orders 200 行、products 20 行）
- 建库建表：`init_demo.sql`（demo 库 + orders/products **外表**，直接指向 HDFS `/demo` 下的 CSV）
- 上传数据到 HDFS：

```bash
docker compose exec namenode hdfs dfs -mkdir -p /demo/orders
docker compose exec namenode hdfs dfs -put -f /host-data/hive_orders.csv /demo/orders/
```

- 查询：

```bash
cat init_demo.sql | docker compose exec -T hive \
  beeline -u 'jdbc:hive2://localhost:10000/default' --silent=true --force=true
echo "SELECT status, COUNT(*) FROM demo.orders GROUP BY status;" | docker compose exec -T hive \
  beeline -u 'jdbc:hive2://localhost:10000/default' --silent=true --force=true
```

## 已验证（2026-10-12）

- WebHDFS `LISTSTATUS` ✓；`demo.orders` 按状态分组各 40 条 ✓；`demo.products` 20 条 ✓
- 计算引擎 Tez local（计算本地、存储 HDFS）；元数据库 Derby（持久化在 `./hive-data`）

## 设计决策与坑

- 镜像内 `/opt/hive/data` 为 root 属主，容器以 hive(uid 1000) 运行 → Derby 建库报 SQL 40000；
  改为 **bind mount `./hive-data`**（Windows 属主映射宽松且数据可见）解决
- 单副本（replication=1）、关闭 HDFS 权限校验，仅用于本地开发/演示
- 配置注入机制：Hadoop 镜像用环境变量（`CORE-SITE.XML_*`）；Hive 镜像用 `HIVE_CUSTOM_CONF_DIR` 挂载覆盖

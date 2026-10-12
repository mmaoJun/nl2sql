#!/usr/bin/env bash
# 生成 Hive/HDFS 演示样例数据（可复现：固定算法、无随机）
set -euo pipefail
mkdir -p "$(dirname "$0")/data"
cd "$(dirname "$0")/data"

statuses=(pending paid shipped completed cancelled)
cities=("北京" "上海" "广州" "深圳" "杭州")

printf "order_id,user_id,product_id,amount,status,city,order_date\n" > hive_orders.csv
for i in $(seq 1 200); do
  s=${statuses[$(( (i - 1) % 5 ))]}
  c=${cities[$(( (i * 7) % 5 ))]}
  uid=$(( (i % 50) + 1 ))
  pid=$(( (i % 20) + 1 ))
  amt=$(( (i * 137) % 2000 + 50 )).$(( (i * 13) % 100 ))
  d=$(printf "2026-%02d-%02d" $(( (i % 9) + 1 )) $(( (i % 27) + 1 )))
  echo "$i,$uid,$pid,$amt,$s,$c,$d" >> hive_orders.csv
done

printf "product_id,product_name,category,price,stock\n" > hive_products.csv
categories=("数码" "家居" "服饰" "食品" "图书")
for p in $(seq 1 20); do
  cat=${categories[$(( (p - 1) % 5 ))]}
  price=$(( (p * 199) % 900 + 100 )).00
  stock=$(( (p * 53) % 500 + 10 ))
  echo "$p,商品$p,$cat,$price,$stock" >> hive_products.csv
done

echo "生成完成："
wc -l hive_orders.csv hive_products.csv

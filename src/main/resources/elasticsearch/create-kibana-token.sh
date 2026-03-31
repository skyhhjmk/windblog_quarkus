#!/bin/bash

# Kibana 服务账户令牌创建脚本
# 用于生成 Kibana 连接 Elasticsearch 所需的服务账户令牌

ES_HOST="${ES_HOST:-localhost}"
ES_PORT="${ES_PORT:-9200}"
ES_USERNAME="${ES_USERNAME:-elastic}"
ES_PASSWORD="${ES_PASSWORD:-elastic123456}"
ES_URL="http://${ES_HOST}:${ES_PORT}"

echo "========================================"
echo "创建 Kibana 服务账户令牌"
echo "========================================"
echo ""

# 检查服务账户是否存在
echo "步骤 1: 检查服务账户..."
response=$(curl -s -w "\n%{http_code}" -u "${ES_USERNAME}:${ES_PASSWORD}" "${ES_URL}/_security/service/elastic/kibana")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "200" ]; then
    echo "✓ 服务账户已存在"
else
    echo "✗ 服务账户不存在 (HTTP $http_code)"
    exit 1
fi

# 创建服务账户令牌
echo ""
echo "步骤 2: 创建服务账户令牌..."
response=$(curl -s -X POST -u "${ES_USERNAME}:${ES_PASSWORD}" "${ES_URL}/_security/service/elastic/kibana/credential/token/kibana-token")

if echo "$response" | grep -q '"created":true'; then
    echo "✓ 令牌创建成功"
    token=$(echo "$response" | grep -o '"value":"[^"]*"' | cut -d'"' -f4)
    echo ""
    echo "令牌值:"
    echo "$token"
    echo ""
    
    # 更新 .env 文件
    echo "步骤 3: 更新 .env 文件..."
    if [ -f ".env" ]; then
        if grep -q "KIBANA_SERVICE_ACCOUNT_TOKEN=" .env; then
            sed -i "s/KIBANA_SERVICE_ACCOUNT_TOKEN=.*/KIBANA_SERVICE_ACCOUNT_TOKEN=$token/" .env
        else
            echo "KIBANA_SERVICE_ACCOUNT_TOKEN=$token" >> .env
        fi
        echo "✓ .env 文件已更新"
    else
        echo "⚠ .env 文件不存在，请手动添加:"
        echo "KIBANA_SERVICE_ACCOUNT_TOKEN=$token"
    fi
    
    echo ""
    echo "========================================"
    echo "完成！"
    echo "========================================"
    echo ""
    echo "下一步:"
    echo "  1. 重启 Kibana: docker-compose restart kibana"
    echo "  2. 查看 Kibana 日志：docker logs kibana"
    echo ""
else
    echo "✗ 创建令牌失败"
    echo "$response"
    exit 1
fi

#!/bin/bash

# Elasticsearch 和 Kibana 初始化脚本
# 用于创建 ILM 策略、索引模板和导入 Kibana 视图

# 从环境变量读取配置
ES_HOST="${ES_HOST:-localhost}"
ES_PORT="${ES_PORT:-9200}"
ES_USERNAME="${ES_USERNAME:-elastic}"
ES_PASSWORD="${ES_PASSWORD:-elastic123456}"
USE_HTTPS="${ELASTICSEARCH_USE_HTTPS:-false}"

# 根据 HTTPS 设置决定协议
if [ "$USE_HTTPS" = "true" ]; then
    PROTOCOL="https"
    CURL_OPTS="--insecure"
else
    PROTOCOL="http"
    CURL_OPTS=""
fi

ES_URL="${PROTOCOL}://${ES_HOST}:${ES_PORT}"
KIBANA_URL="http://${KIBANA_HOST:-localhost}:${KIBANA_PORT:-5601}"

# 认证头
AUTH_HEADER="-u ${ES_USERNAME}:${ES_PASSWORD}"

echo "========================================"
echo "Elasticsearch 和 Kibana 初始化脚本"
echo "目标：${ES_URL}"
echo "用户：${ES_USERNAME}"
echo "HTTPS: ${USE_HTTPS}"
echo "========================================"

# 等待 Elasticsearch 启动
echo ""
echo "步骤 1: 等待 Elasticsearch 启动..."
max_attempts=30
attempt=0
while [ $attempt -lt $max_attempts ]; do
    if curl -s -f $CURL_OPTS $AUTH_HEADER "${ES_URL}/_cluster/health" > /dev/null 2>&1; then
        echo "✓ Elasticsearch 已就绪"
        break
    fi
    attempt=$((attempt + 1))
    echo "  等待中... (尝试 $attempt/$max_attempts)"
    sleep 5
done

if [ $attempt -eq $max_attempts ]; then
    echo "✗ Elasticsearch 启动超时"
    exit 1
fi

# 创建 ILM 策略
echo ""
echo "步骤 2: 创建 ILM 策略..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ILM_POLICY_FILE="${SCRIPT_DIR}/ilm-policy.json"

if [ ! -f "$ILM_POLICY_FILE" ]; then
    echo "✗ 找不到 ILM 策略文件：$ILM_POLICY_FILE"
    exit 1
fi

response=$(curl -s -w "\n%{http_code}" $CURL_OPTS $AUTH_HEADER -X PUT "${ES_URL}/_ilm/policy/windblog-logs-policy" \
    -H "Content-Type: application/json" \
    -d @"$ILM_POLICY_FILE")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

if [ "$http_code" = "200" ]; then
    echo "✓ ILM 策略创建成功"
    echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
else
    echo "✗ ILM 策略创建失败 (HTTP $http_code)"
    echo "$body"
    exit 1
fi

# 删除可能冲突的遗留模板
echo ""
echo "步骤 3: 清理遗留模板..."
legacy_templates=("windblog-logs" "windblog-posts")
for template_name in "${legacy_templates[@]}"; do
    echo "  检查遗留模板: $template_name"
    if curl -s -f $CURL_OPTS $AUTH_HEADER "${ES_URL}/_index_template/${template_name}" > /dev/null 2>&1; then
        echo "  发现遗留模板 $template_name，正在删除..."
        if curl -s -f $CURL_OPTS $AUTH_HEADER -X DELETE "${ES_URL}/_index_template/${template_name}" > /dev/null 2>&1; then
            echo "  ✓ 遗留模板 $template_name 已删除"
        else
            echo "  ⚠ 删除遗留模板 $template_name 失败"
        fi
    fi
done

# 创建索引模板
echo ""
echo "步骤 4: 创建索引模板..."
TEMPLATE_FILE="${SCRIPT_DIR}/index-template.json"

if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "✗ 找不到索引模板文件：$TEMPLATE_FILE"
    exit 1
fi

response=$(curl -s -w "\n%{http_code}" $CURL_OPTS $AUTH_HEADER -X PUT "${ES_URL}/_index_template/windblog-logs-template" \
    -H "Content-Type: application/json" \
    -d @"$TEMPLATE_FILE")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

if [ "$http_code" = "200" ]; then
    echo "✓ 索引模板创建成功"
    echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
else
    echo "✗ 索引模板创建失败 (HTTP $http_code)"
    echo "$body"
    exit 1
fi

# 创建初始索引
echo ""
echo "步骤 5: 创建初始索引..."
response=$(curl -s -w "\n%{http_code}" $CURL_OPTS $AUTH_HEADER -X PUT "${ES_URL}/windblog-logs-000001" \
    -H "Content-Type: application/json" \
    -d '{
      "aliases": {
        "windblog-logs": {
          "is_write_index": true
        }
      }
    }')

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

if [ "$http_code" = "200" ]; then
    echo "✓ 初始索引创建成功"
    echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
else
    echo "⚠ 初始索引可能已存在 (HTTP $http_code)"
fi

# 验证配置
echo ""
echo "步骤 6: 创建文章搜索索引..."
echo ""

# 创建文章 ILM 策略
POSTS_ILM_FILE="${SCRIPT_DIR}/ilm-policy-posts.json"
if [ -f "$POSTS_ILM_FILE" ]; then
    response=$(curl -s -w "\n%{http_code}" $CURL_OPTS $AUTH_HEADER -X PUT "${ES_URL}/_ilm/policy/windblog-posts-policy" \
        -H "Content-Type: application/json" \
        -d @"$POSTS_ILM_FILE")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ]; then
        echo "✓ 文章 ILM 策略创建成功"
    else
        echo "⚠ 文章 ILM 策略可能已存在 (HTTP $http_code)"
    fi
else
    echo "⚠ 找不到文章 ILM 策略文件：$POSTS_ILM_FILE"
fi

# 创建文章索引模板
POSTS_TEMPLATE_FILE="${SCRIPT_DIR}/index-template-posts.json"
if [ -f "$POSTS_TEMPLATE_FILE" ]; then
    response=$(curl -s -w "\n%{http_code}" $CURL_OPTS $AUTH_HEADER -X PUT "${ES_URL}/_index_template/windblog-posts-template" \
        -H "Content-Type: application/json" \
        -d @"$POSTS_TEMPLATE_FILE")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ]; then
        echo "✓ 文章索引模板创建成功"
    else
        echo "⚠ 文章索引模板创建失败 (HTTP $http_code)"
        echo "$body"
    fi
else
    echo "⚠ 找不到文章索引模板文件：$POSTS_TEMPLATE_FILE"
fi

# 创建初始文章索引（静态索引）
response=$(curl -s -w "\n%{http_code}" $CURL_OPTS $AUTH_HEADER -X PUT "${ES_URL}/windblog-posts" \
    -H "Content-Type: application/json")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

if [ "$http_code" = "200" ]; then
    echo "✓ 初始文章索引创建成功"
else
    echo "⚠ 初始文章索引可能已存在 (HTTP $http_code)"
fi

# 验证配置
echo ""
echo "步骤 7: 验证配置..."
echo ""
echo "ILM 策略 (日志):"
curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_ilm/policy/windblog-logs-policy" | python3 -m json.tool 2>/dev/null || curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_ilm/policy/windblog-logs-policy"

echo ""
echo "ILM 策略 (文章):"
curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_ilm/policy/windblog-posts-policy" | python3 -m json.tool 2>/dev/null || curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_ilm/policy/windblog-posts-policy"

echo ""
echo "索引模板 (日志):"
curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_index_template/windblog-logs-template" | python3 -m json.tool 2>/dev/null || curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_index_template/windblog-logs-template"

echo ""
echo "索引模板 (文章):"
curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_index_template/windblog-posts-template" | python3 -m json.tool 2>/dev/null || curl -s $CURL_OPTS $AUTH_HEADER "${ES_URL}/_index_template/windblog-posts-template"

# 导入 Kibana 视图和仪表板
echo ""
echo "步骤 8: 导入 Kibana 视图和仪表板..."
KIBANA_SAVED_OBJECTS_FILE="${SCRIPT_DIR}/kibana-saved-objects.json"

if [ -f "$KIBANA_SAVED_OBJECTS_FILE" ]; then
    response=$(curl -s -w "\n%{http_code}" -X POST "${KIBANA_URL}/api/saved_objects/_import?overwrite=true" \
        -H "Content-Type: application/json" \
        --data-binary @"$KIBANA_SAVED_OBJECTS_FILE")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ]; then
        echo "✓ Kibana 视图导入成功"
        echo "$body"
    else
        echo "⚠ Kibana 视图导入失败，可以稍后手动导入 (HTTP $http_code)"
        echo "$body"
    fi
else
    echo "⚠ 找不到 Kibana saved objects 文件"
fi

# 导入 Kibana 仪表板
echo ""
echo "步骤 9: 导入 Kibana 仪表板..."
KIBANA_DASHBOARD_FILE="${SCRIPT_DIR}/kibana-dashboard.json"

if [ -f "$KIBANA_DASHBOARD_FILE" ]; then
    response=$(curl -s -w "\n%{http_code}" -X POST "${KIBANA_URL}/api/saved_objects/_import?overwrite=true" \
        -H "Content-Type: application/json" \
        --data-binary @"$KIBANA_DASHBOARD_FILE")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ]; then
        echo "✓ Kibana 仪表板导入成功"
        echo "$body"
    else
        echo "⚠ Kibana 仪表板导入失败，可以稍后手动导入 (HTTP $http_code)"
        echo "$body"
    fi
else
    echo "⚠ 找不到 Kibana 仪表板文件"
fi

echo ""
echo "========================================"
echo "✓ Elasticsearch 和 Kibana 初始化完成"
echo "========================================"
echo ""
echo "下一步:"
echo "1. 启动应用，日志将自动推送到 Elasticsearch"
echo "2. 访问 Kibana (http://localhost:5601) 查看日志和仪表板"
echo "   - 用户名：elastic"
echo "   - 密码：\$ELASTIC_PASSWORD"
echo "3. 预配置的视图和仪表板已自动导入"
echo ""

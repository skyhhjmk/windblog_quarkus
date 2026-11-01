# Elasticsearch 文章索引自愈

文章全文索引是**可丢弃的搜索优化缓存**，唯一数据源始终是 PostgreSQL。索引损坏、映射过期或同步事件丢失时，系统可以自行重建，不影响文章数据的正确性。

## 主动检查

`ElasticsearchPostIndexFeedbackService` 每 5 分钟（启动后延迟 30 秒）执行一次审计，只在 primary 节点运行：

1. 读取索引版本标记（`windblog-post-meta/_doc/version`）。标记缺失或与 `ElasticsearchPostSearchService.POST_INDEX_VERSION` 不一致时判定映射过期。
2. 比较 PostgreSQL 公开已发布文章数与别名文档数。
3. 比较 PostgreSQL `max(updated_at)` 与索引 `updatedAt` 聚合值，发现“文档都在但内容过期”。
4. 每 6 次检查执行一次完整 ID 集合比对，修复“删除一篇 + 新增一篇”导致数量不变的情况。
5. 小范围差异：重新索引变更文章、补齐缺失文档、删除过期文档。
6. 大范围差异或映射过期：创建新一代索引（`windblog-posts-0000NN`），全量写入后原子切换别名 `windblog-posts`，旧索引保留到切换完成，搜索不中断。

重建失败时旧别名继续提供服务；失败的替换索引会被清理。自动重建有冷却时间，避免故障时反复重建。

## 并发保护

自动检查与手动同步/重建都通过 PostgreSQL advisory lock（key `145002`）串行化，多个 primary 实例不会同时重建。边缘节点不维护索引，也不会执行审计。

## 版本升级

修改文章索引映射或写入文档结构时，必须递增 `ElasticsearchPostSearchService.POST_INDEX_VERSION`。模板版本与索引标记使用同一常量；部署后第一次审计会发现标记过期并自动重建。

索引结构版本不匹配时，即使 ID 集合完全一致也会重建，因为旧索引可能缺少新字段的映射。

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `elasticsearch.post-index.auto-sync-cooldown-seconds` | `600` | 自动增量修复冷却 |
| `elasticsearch.post-index.auto-rebuild-cooldown-seconds` | `1800` | 自动全量重建冷却 |
| `elasticsearch.post-index.full-audit-interval-checks` | `6` | 每多少次审计做一次完整 ID 比对 |
| `elasticsearch.post-index.rebuild-ratio-threshold` | `0.2` | 差异占比达到该值时重建 |
| `elasticsearch.post-index.rebuild-min-drift` | `20` | 差异少于该数量时优先增量修复 |
| `elasticsearch.post-index.content-freshness-tolerance-seconds` | `60` | 时间戳比较容差 |

## 管理接口

- `GET /api/admin/elasticsearch/status` / `health`：返回 `postIndexFeedback`，包含 `schemaVersion`、`expectedSchemaVersion`、`indexedUpdatedAt`、`databaseUpdatedAt`、`lastAction`、`lastRebuildAt` 等。
- `POST /api/admin/elasticsearch/posts/reindex-all`：全量同步到当前索引。
- `POST /api/admin/elasticsearch/rebuild`：新建索引代并切换别名。
- `POST /api/admin/elasticsearch/apply-index-configuration`：重新应用 ILM/模板/别名，不重建数据。

搜索服务本身在 Elasticsearch 不可用时自动回退 PostgreSQL，索引自愈失败不会影响公开搜索的可用性。

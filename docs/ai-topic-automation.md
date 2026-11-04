# AI 话题与草稿自动化

话题发现、话题池、审核和文章草稿全部由 WindBlog 自己的 AI 提供商完成。外部
Codex Creator 服务已停用：仓库中的 `codex-creator/` 子模块仅作为历史代码保留，
WindBlog 不再部署、调用或依赖它，也不再通过 HMAC / 内部 HTTP 接口与它通信。

## 数据表

- `ai_topic_pool_seeds`：话题查询种子。
- `ai_topic_pool_settings`：自动化开关、运行间隔与每次运行的种子/话题上限。
- `ai_topic_pool`：话题条目、审核状态、来源证据与关联草稿。
- `ai_topic_pool_runs`：异步话题发现运行。
- `ai_article_draft_runs`：异步文章草稿运行。

## 操作配置

`ai_operation_configs.topic` 指定话题功能使用的 AI 提供商或轮询组；留空时由
`AiManager` 按普通提供商顺序自动选择。所有生成都走
`AiManager.complete(providerId, "topic", ...)`，不再存在 Codex 分支。

## 管理 API

全部位于 `/api/admin/ai/topics`：

| 方法与路径 | 作用 |
| --- | --- |
| `GET /automation`、`PUT /automation` | 读取/更新自动化设置与种子列表 |
| `GET/POST /seeds`、`PUT/DELETE /seeds/{id}` | 查询种子 CRUD |
| `POST /runs`、`GET /runs`、`GET /runs/{id}` | 启动/查询话题发现运行 |
| `GET /`、`GET /{id}` | 话题列表与详情 |
| `POST /{id}/review` | 审核话题 |
| `POST /{id}/draft`、`POST /{id}/draft/regenerate` | 启动异步草稿生成 |
| `GET /draft-jobs/{id}` | 查询草稿任务 |

写操作使用 `Idempotency-Key`；`POST /runs` 与草稿接口在
`AdminAuthorizationPolicy` 中强制要求该请求头。

## 异步运行与恢复

管理端请求只创建 `QUEUED` 运行并立即返回，生成在进程内 `ManagedExecutor` 中执行，
避免模型耗时超过公网边缘超时（HTTP 554）。

- 启动后 10 分钟仍未开始执行的运行会被标记为 `FAILED`（部署/重启会丢失进程内任务）。
- `RUNNING` 超过 30 分钟的运行同样会失败。
- 恢复任务每 1 分钟运行一次，并在主节点启动时立即执行；边缘节点不执行恢复。
- 线程池拒绝任务时运行立即失败，不会留下永久 `QUEUED` 的运行。
- 话题发现完成后通过 `topics.import` 写入本地话题池；草稿完成后回写
  `ai_topic_pool.post_id` 与 `DRAFT_CREATED` 状态。

## 草稿生成

草稿使用 `AiManager.complete(providerId, "topic", ...)` 生成 JSON，解析后创建或更新
WindBlog 草稿（始终为 `DRAFT`），并记录 AI 元数据、审计日志与转载协议。管理端轮询
`/draft-jobs/{id}` 直到 `DRAFT_CREATED` 或 `FAILED`。

# WindBlog 后台 API 文档

## 文档入口
- Swagger UI: `/api/admin/docs`
- OpenAPI JSON: `/api/admin/openapi`

## 鉴权说明
- 登录接口：`POST /api/admin/auth/login`
- 登录成功返回 `token`，后续接口在请求头带上：
  - `Authorization: Bearer <token>`
- Token 默认有效期由配置 `admin.jwt.expire-minutes` 控制。

## 配置项
- `admin.jwt.secret`: JWT 对称签名密钥（生产环境务必修改）
- `admin.jwt.issuer`: JWT 发行者
- `admin.jwt.expire-minutes`: JWT 过期分钟数

## 接口列表

### 1) 鉴权接口
- `POST /api/admin/auth/login`: 登录
- `GET /api/admin/auth/me`: 当前登录用户

### 2) 文章管理接口
- `GET /api/admin/posts`: 分页查询文章
- `GET /api/admin/posts/{id}`: 查询文章详情
- `POST /api/admin/posts`: 创建文章草稿
- `PUT /api/admin/posts/{id}`: 编辑文章（带 `version` 做并发控制）
- `POST /api/admin/posts/{id}/publish`: 发布文章
- `DELETE /api/admin/posts/{id}`: 软删除文章

### 3) 基础后台接口
- `GET /api/admin/base/ping`: 连通性检查
- `GET /api/admin/base/overview`: 概览统计

## 示例

### 登录
```http
POST /api/admin/auth/login
Content-Type: application/json

{
  "account": "admin",
  "password": "your-password"
}
```

### 创建文章
```http
POST /api/admin/posts
Authorization: Bearer <token>
Content-Type: application/json

{
  "slug": "hello-windblog",
  "title": { "zh-cn": "你好，WindBlog" },
  "summary": { "zh-cn": "一篇测试文章" },
  "aiSummary": { "zh-cn": "AI 摘要内容" },
  "contentMarkdown": { "zh-cn": "# Hello" },
  "status": 0,
  "visibility": 0,
  "editorType": 0
}
```

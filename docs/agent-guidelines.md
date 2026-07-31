# Repository Guidelines

本文件与根目录 `AGENTS.md` 保持一致，供本机忽略 `AGENTS.md` 的工具读取。请以根目录版本为准并同步更新。

## 项目结构

WindBlog 包含 Quarkus 后端和 Flutter 管理端。后端位于 `src/main/java/com/biliwind/blog/`，测试在 `src/test/java/`
，Liquibase 迁移在 `src/main/resources/db/migration/`，模板与资源在 `src/main/resources/templates/` 和 `assets/`。
`admin-flutter/` 是独立 Git 仓库，代码按 `pages/`、`services/`、`models/`、`components/` 组织。

## 验证与风格

后端使用 `.\mvnw.cmd -q -DskipTests compile` 编译、`.\mvnw.cmd test` 测试；管理端在 `admin-flutter/` 中运行
`flutter analyze` 和 `flutter test`。Java 代码坚持完整语义命名、普通控制流和小方法；不要在业务主流程使用三元表达式、`var`
、Stream 链或含混缩写。Flutter 遵循 `flutter_lints`。

## 安全与提交

密码保护须覆盖页面和相关 API，Cookie 不得保存明文密码；本地媒体须拒绝不存在或已软删除的记录；管理端响应必须脱敏
Key、Token、Password、Secret；外部网络访问须防 SSRF 与 DNS 重绑定。提交使用 `feat:`、`fix:`、`refactor:` 或简短中文动词短语。根仓库和
`admin-flutter` 必须分别验证、暂存、提交。

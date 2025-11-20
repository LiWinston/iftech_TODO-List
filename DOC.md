# 说明文档（开发过程与思考）

## 目标与范围
实现一个前后端分离的 TODO List，完整覆盖“必须完成功能 + 基础扩展功能”，并选择“Hybrid 搜索”作为进阶特性。

## 关键需求澄清与落地
1) 删除：采用软删除（TRASHED），入 Redisson 延时队列，到期后消费者检查状态仍为 TRASHED 才执行物理删除；期间可恢复/硬删。相关操作在事务中执行，实体含 `@Version`（可进一步扩展条件版本更新）。

2) 查看（滚动加载）：后端使用 Keyset 分页（`created_at desc, id desc`），避免大数据量下 `offset/limit` 性能问题；前端根据滚动速度自适应调整 N（20~150）。

3) 优先级：采用可分数域 `priority_score (NUMERIC)` 存储，无论在两级之间或之外增添新级别，都无需批量迁移；另存 `priority_label` 作为语义展示；相关索引支持排序与范围查询。

4) 用户级个性化：条目含 `user_id`，所有接口通过 `X-User-ID` 进行隔离。分类/优先级若后续需“用户方案级配置”，可新增配置表，仅影响该用户数据。

5) Hybrid 搜索（加分项）：
	- 向量：LangChain4j 内存 ONNX（all-MiniLM-L6-v2），维度 384
	- 同表存储：使用 PGVector 列 `embedding`（及 `text`,`metadata`）与业务字段同行存储
	- 写入策略：为了避免对业务数据的误删，写入 embeddings 时使用原子 UPDATE（非 Store 的 remove/insert）
	- 检索：向量相似（EmbeddingStore.search）与 `pg_trgm` 文本相似融合，默认权重 0.6/0.4

## 关于“Embeddings 与业务数据同表存储”的调研结论
- LangChain4j `PgVectorEmbeddingStore` 支持指定表名与维度，但列名固定（`id`, `embedding`, `text`, `metadata`）。
- 因此，只要业务表包含上述列（我们在 `todo_item` 中添加），即可以同表/同行存储。
- 我们关闭 `createTable`，由 `schema.sql` 建表并建索引。为了避免 remove() 造成整行删除，写入 embeddings 采用原生 SQL UPDATE，同步更新 `embedding/text/metadata`。
- 检索仍使用 Store 的 search（只读），实现简单且与 LangChain4j 生态契合。

## 架构与模块
- Web API：`TodoController` 暴露 CRUD、分页列表、搜索
- Service：`TodoService` 封装业务逻辑，入列异步任务
- Repository：原生 SQL + JPA 实现 Keyset 分页、内容/状态更新、向量写入、文本搜索
- Worker：`Workers` 从 Redisson 队列消费延时删除与向量计算任务
- Config：`AppConfig`（Redisson、EmbeddingModel、PgVector Store）、`CorsConfig`
- DDL：`schema.sql`（扩展、表、索引、触发器）

## 接口契约（简要）
- 列表：`GET /api/todos?size=&cursorCreatedAt=&cursorId=&status=`
- 新建：`POST /api/todos`
- 更新：`PUT /api/todos/{id}`
- 完成/取消完成：`POST /api/todos/{id}/complete` / `POST /api/todos/{id}/uncomplete`
- 删除（软删）：`DELETE /api/todos/{id}`
- 恢复：`POST /api/todos/{id}/restore`
- 搜索：`GET /api/todos/search?q=&k=`
- Header：`X-User-ID`

## 工程化与扩展点
- 质量：采用 JPA + 原生 SQL 组合；关键 SQL 索引齐备；`open-in-view=false`；`schema.sql` 可复用初始化
- 安全：示例未引入认证组件，`X-User-ID` 为演示用途，实际应接入身份体系
- 可观测：生产建议加上结构化日志/追踪、队列失败重试与死信队列
- 搜索：权重/归一化参数外置；支持 SQL 端 `<->` 余弦距离计算实现“端到端 SQL 向量检索”

## 使用 AI 的说明
- 借助 Copilot/LLM 进行依赖与 API 调研（LangChain4j PGVector 参数、方法签名差异），并结合本地编译错误进行修订
- 结合文档结论与编译验证，确定“同表存储 + 写时原子 UPDATE、读时 EmbeddingStore.search”的权衡方案

## 后续计划（可选）
- 用户级“分类/优先级方案”表与迁移工具
- 列表支持多种排序（优先级/截止日期等）
- 嵌入批量更新与重建脚本（模型变更时）

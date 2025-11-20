# TODO List (Spring Boot + React + PG + Redis + PGVector)

后端：Spring Boot 3.5, JDK 17, Spring Data JPA, PostgreSQL, Redisson, LangChain4j Embeddings + PGVector
前端：Vite + React + TypeScript（前后端分离）

## 运行要求
- 本地 PostgreSQL: `localhost:5432`，数据库 `todolist` 已存在，启用 `pgvector` 与 `pg_trgm` 扩展（应用启动会尝试创建）
- 云端 Redis（已在 `application.properties` 中配置 rediss URL）
- Node.js (>=18) 用于前端

## 后端启动
在 Windows PowerShell 下执行：

```powershell
cd "c:\GitHub\iftech_TODO List\tdl"
./mvnw spring-boot:run
```

服务默认端口 `8080`。

## 前端启动
```powershell
cd "c:\GitHub\iftech_TODO List\frontend"
# 首次安装依赖
npm install
# 本地开发
npm run dev
```

Vite 开发服务器默认端口 `5173`，已代理 `/api` 到 `http://localhost:8080`。

## 核心特性
- 软删除：删除进入“垃圾桶”状态（TRASHED），通过 Redisson 延时队列到期后检查状态并物理删除；期间可“恢复/硬删”。
- 滚动加载：后端采用 Keyset 分页（created_at/id），前端根据滚动速度动态调整每页 N（20~150），避免硬编码与 offset/limit 性能问题。
- 优先级设计：使用 `priority_score (NUMERIC)` + `priority_label`。通过可分数域在任意两级之间插入新级别，避免大规模重排，同时可建索引实现高效排序/范围查询。
- 用户隔离：所有数据均带 `user_id`，API 通过 `X-User-ID` 头实现多用户隔离。
- Hybrid 搜索：
  - 文本向量：LangChain4j 内存 ONNX（all-MiniLM-L6-v2，维度 384）
  - 向量存储：PGVector（与业务数据同表 `todo_item`，列名固定：`id`, `embedding`, `text`, `metadata`）
  - 关键词检索：`pg_trgm` 相似度
  - 融合：`score = 0.6 * vector + 0.4 * text`，返回 Top-K

## 关键接口
- 列表（滚动加载）：
  - GET `/api/todos?size=20&cursorCreatedAt=...&cursorId=...&status=ACTIVE,COMPLETED`
- 新建：
  - POST `/api/todos` body: `{ title, description?, priorityScore?, priorityLabel?, categoryId? }`
- 更新：
  - PUT `/api/todos/{id}`
- 完成/取消完成：
  - POST `/api/todos/{id}/complete` / `/api/todos/{id}/uncomplete`
- 删除（软删）：
  - DELETE `/api/todos/{id}`
- 恢复：
  - POST `/api/todos/{id}/restore`
- 搜索：
  - GET `/api/todos/search?q=keyword or question&k=20`

均需请求头 `X-User-ID`（未提供则默认 `demo-user`）。

## PGVector 与同表存储说明（调研结论）
- LangChain4j `PgVectorEmbeddingStore` 允许指定 `table` 与 `dimension`。
- 列名是约定好的：`id`, `embedding`, `text`, `metadata`。因此只要业务表包含这些列，即可“同表（同行）存储”。
- 我们将 `table` 指定为 `todo_item`，同时关闭自动建表（`createTable=false`），DDL 由 `schema.sql`/JPA 管理。
- 为避免误删业务行，写入嵌入时未直接调用 Store 的删除/插入，而是使用原子 UPDATE 将向量/文本/元数据更新到同一行；检索时使用 Store 的 search（只读）。

## 设计权衡
- 乐观锁：实体含 `@Version` 字段，核心更新使用事务与幂等写法。若需“条件版本更新”，可扩展 API 传递版本并在 WHERE 条件带上版本。
- 分类/优先级个性化：当前以条目字段体现（`category_id`, `priority_*`）。如需全局可管理的“用户级分类/优先级方案”，可增设配置表并迁移时仅影响该用户数据。
- 搜索融合：权重与归一化可按需要暴露为配置；也可将向量相似在 SQL 端完成（使用 `<->` 余弦距离），此处为演示采用Store+SQL融合。

## 常见问题
- 若启动时扩展创建报错，可忽略（已 `continue-on-error=true`），只要数据库已安装对应扩展即可。
- 向量维度默认 384（all-MiniLM-L6-v2），可调 `tdl.embedding.dimension`。
- 延时删除默认 7 天，可调 `tdl.delete.delay-seconds`。

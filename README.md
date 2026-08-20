# 北京地下管线协会 AI 管理协作平台

本仓库用于实现平台第二版方案：**向上连接政策与标准，横向连接友好协会，向下服务会员企业，在平台内部形成可持续运营的产业生态。**

当前为首轮工程骨架，重点是把模块边界、身份权限、核心接口和本地开发环境搭稳。企业调查表将作为冷启动数据入口，经过协会审核和 AI 结构化后进入企业、产品、需求与场景库。

## 工程结构

```text
apps/
  server/       Java 21 + Spring Boot 模块化单体业务后端
  web/          Vue 3 + TypeScript 协会/企业管理端
services/
  ai/           FastAPI 独立 AI 能力服务
infra/
  postgres/     PostgreSQL 初始化脚本
tests/          正反向、模糊、压力、渗透与故障注入工具
docs/
  architecture.md  软件架构与模块边界
compose.yaml    本地 PostgreSQL、Redis、MinIO 编排
```

## 首批功能

- 五类首期账号身份与基于角色的菜单、接口权限
- 会员企业档案及产品、能力、需求的基础管理
- 政策与标准中心的列表和检索骨架
- 生态供需匹配及可解释评分
- 协作事项和运营工作台页面
- 企业资料文本结构化、政策问答占位接口

## 核心基础约束

- 后端会员写操作在当前单 JVM 内保持原子性；信用代码与状态统一规范化，匹配结果具备稳定排序和稳定标识。
- 演示账号可整体关闭，生产 Profile 若误开硬编码账号会拒绝启动；常见 MVC 错误统一为稳定 API 契约。
- ArchUnit 自动保护模块依赖方向，避免业务模块反向耦合启动层或内部实现。
- Web、业务后端与 AI 服务统一使用安全的 `X-Request-Id`；成功与异常响应均可关联同一次请求，非法或可注入日志的值会被替换。
- 会员业务规则已与内存仓储实现解耦；写操作使用版本号、强 ETag 与仓储 CAS 防止旧页面覆盖新数据，后续 PostgreSQL 适配器必须继续执行条件更新。
- AI 输入具备字段、列表、数值和请求体资源上限；Unicode 统一规范化，校验错误不回显原始输入，评分分项与总分严格一致。
- AI 请求体上限按 ASGI 数据块累计，无 `Content-Length` 或分块传输也不能绕过；Web 六个核心页面具有统一的安全失败态和重试机制。
- Web 权限默认拒绝，生产构建禁用 mock 回退；演示会话只保存白名单角色编号，不持久化完整用户资料或令牌。

## 本地启动

1. 复制环境变量：`Copy-Item .env.example .env`
2. 启动基础设施：`docker compose up -d postgres redis minio`
3. 启动业务后端：参见 `apps/server/README.md`
4. 启动 AI 服务：参见 `services/ai/README.md`
5. 启动 Web 管理端：参见 `apps/web/README.md`

默认开发端口：Web `5173`、业务 API `8080`、AI 服务 `8001`、MinIO 控制台 `9001`。

如需用容器启动全部模块：

```powershell
Copy-Item .env.example .env
docker compose --profile app up --build -d
```

容器模式默认从 `http://localhost:8081` 访问。执行 `./scripts/verify.ps1` 可统一校验 Compose、Web、AI 服务和 Java 后端。

## 测试与安全验证

仓库已配置单元/集成、反向与异常、变异、OpenAPI 模糊、k6 高压、OWASP ZAP 渗透基线、Trivy 供应链扫描和 Toxiproxy 故障注入。所有主动测试脚本默认只允许本机或 Compose 内部服务地址，避免误扫外部系统。

快速入口和本轮实测结果见 [测试工具使用指南](docs/testing-guide.md)，脚本参数见 [tests/README.md](tests/README.md)。常规提交执行 `.github/workflows/ci.yml`，耗时和主动扫描任务按周或手动执行 `.github/workflows/advanced-testing.yml`，依赖与密钥扫描执行 `.github/workflows/security-scan.yml`。

> 当前真实登录、持久化数据库、Excel 批量导入和真实大模型尚未接入；首轮使用演示身份、内存数据或确定性规则，便于先验证业务闭环。

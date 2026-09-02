# 北京地下管线协会管理协作平台

> 当前对外定位为“管理协作平台”。文档解析、可见范围检索、引用追踪和可选 Embedding
> 已进入技术验证，但在真实模型供应商、协会语料评测、费用阈值和数据出境审批全部验收前，
> 不对外宣称为“AI 平台”。

本仓库用于实现平台第二版方案：**向上连接政策与标准，横向连接友好协会，向下服务会员企业，在平台内部形成可持续运营的产业生态。**

当前已形成可运行的首期工程基线，模块边界、OIDC 数据域、会员审核审计和 Excel 调查采集闭环已经接通。企业调查表作为 106 家会员冷启动入口，经过逐行预检、协会审核后进入企业、产品、需求与场景库。

当前代码已接通身份生命周期、系统管理员代管上下文和主要业务数据域。早期基线曾在隔离的 PostgreSQL、MinIO、Redis、Keycloak/OIDC 环境中完成浏览器 E2E，但该历史结果不能代表当前变更已通过真实依赖验收。本次本机 Docker daemon 不可用，所有因此跳过的 Testcontainers 用例均不计入通过。正式 IdP、当前版本浏览器 E2E、TLS/私网、备份恢复、集中日志与真实告警，以及真实模型和协会语料验收仍是生产上线闸门。

## 工程结构

```text
apps/
  server/       Java 21 + Spring Boot 模块化单体业务后端
  web/          Vue 3 + TypeScript 协会/企业管理端
services/
  ai/           FastAPI 规则兼容服务（政策问答入口已停用）
infra/
  postgres/     PostgreSQL 初始化脚本
tests/          常规功能、权限、数据隔离、身份协议与运行时验收工具
docs/
  architecture.md  软件架构与模块边界
compose.yaml    本地 PostgreSQL、Redis、MinIO 编排
```

## 首批功能

- 五类首期账号身份、OIDC `sub` 与协会/企业数据域绑定
- 会员企业档案、归属权限、审核、审计及 ETag 并发控制
- 政策与标准中心的列表和检索骨架
- 生态供需匹配及可解释评分
- 协作事项和运营工作台页面
- 106 家会员正式 XLSX 模板、来源指纹、批量预检、合法行提交与逐家审核闭环
- 知识文档草稿、送审、发布、停用、归档、软删除、恢复、重解析与重新 Embedding
- 严格按协会隔离的带出处检索、无证据拒答及真实语料评测发布闸门

## 核心基础约束

- 会员数据默认落 PostgreSQL；信用代码由数据库唯一索引兜底，更新与删除使用版本条件 CAS，支持多实例并发。
- 生产默认使用 OIDC/JWT；仅显式本地/测试模式可启用 HTTP Basic 演示账号，生产 Profile 会拒绝 demo 模式。
- ArchUnit 自动保护模块依赖方向，避免业务模块反向耦合启动层或内部实现。
- Web、业务后端与 AI 服务统一使用安全的 `X-Request-Id`；成功与异常响应均可关联同一次请求，非法或可注入日志的值会被替换。
- Flyway 当前迁移链为 V1–V22，可追踪处理空库和既有非空 PostgreSQL；V16 固化政策影响分析的同协会约束，V17 固化跨协会授权、推荐和共享策略不变量，V18 固化“推荐—双方确认—邀请—洽谈—反馈—成果”闭环，V19/V20 收紧通知与附件状态，V21 冻结会员采集出处字段和知识生命周期，V22 持久化分协会 RAG 评测与发布闸门。会员及知识生命周期写入均使用强 ETag/If-Match，陈旧写入返回 412。
- AI 输入具备字段、列表、数值和请求体资源上限；Unicode 统一规范化，校验错误不回显原始输入，评分分项与总分严格一致。
- AI 请求体上限按 ASGI 数据块累计，无 `Content-Length` 或分块传输也不能绕过；Web 六个核心页面具有统一的安全失败态和重试机制。
- Web 权限默认拒绝，生产构建禁用 mock 回退和演示身份切换；OIDC 登录采用 Authorization Code + PKCE，并回读后端验证身份。

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

需要使用仓库内置的真实 PostgreSQL、MinIO、Redis 和 Keycloak/OIDC 数据执行浏览器 E2E 时，运行：

```powershell
./tools/testing/Start-E2eStack.ps1
```

脚本强制使用独立项目名 `guanxian-platform-e2e`，并读取 `tests/e2e/compose.env` 中仅供本机测试的固定身份和端口；它会等待所有依赖健康且数据库种子导入成功后，才返回 Web 地址 `http://127.0.0.1:18082`。重复执行不会删除数据卷；结束环境可运行 `docker compose --project-name guanxian-platform-e2e --env-file tests/e2e/compose.env -f compose.yaml -f compose.e2e.yaml --profile app down`，除非明确需要重新初始化，禁止附加 `--volumes`。

## 测试与质量验证

仓库默认只执行单元测试、集成测试、权限与数据隔离回归、类型检查和构建。变异测试与隔离的 OIDC/PKCE 验收仅可手动触发；模糊请求、高压/负载、ZAP 主动扫描和网络故障注入已退出 CI，不得重新加入自动或手动工作流。

快速入口和当前可复验基线见 [测试工具使用指南](docs/testing-guide.md)，脚本参数见 [tests/README.md](tests/README.md)。常规提交执行 `.github/workflows/ci.yml`，手动质量检查执行 `.github/workflows/advanced-testing.yml`，依赖与密钥扫描执行 `.github/workflows/security-scan.yml`。

2026-09-02 当前变更的本地常规基线：Java 的 69 份 Surefire 报告共定义 346 项测试，实际执行并通过 284 项，失败 0、错误 0；由于本机 Docker daemon 不可用，62 项 Testcontainers 用例被跳过，跳过不计为通过。Web 为 20 个 Vitest 文件、208 项全部通过，`vue-tsc -b` 与 Vite 生产构建通过。因此当前 V1–V22 迁移和真实 PostgreSQL/MinIO/Redis/OIDC/ClamAV 接线仍须在 Docker 可用的 CI 或预生产环境复验。早期版本的 Playwright 5/5 真实依赖结果只是历史基线，不是当前变更的验收证据。

> 当前代码提供了生产向 OIDC/JWT、PostgreSQL/Flyway、企业数据域、审计、正式 Excel 批量采集、附件知识治理、带出处检索和评测闸门；这不等于生产闭环已验收。本地测试仍可显式使用演示身份与内存仓储。Python 政策问答入口固定返回 503，Java 是唯一正式知识问答实现，且外部模型和 Embedding 默认关闭。生产上线前仍需取得 106 家真实回表并在预生产去重审核，配置正式 IdP、ClamAV 与合规模型密钥，完成当前代码的真实浏览器 E2E、V1–V22 迁移及备份恢复演练，并用协会真实语料跑过评测阈值；未通过前只能使用“管理协作平台”名称。

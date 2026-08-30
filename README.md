# 北京地下管线协会管理协作平台

> 当前对外定位为“管理协作平台”。文档解析、可见范围检索、引用追踪和可选 Embedding
> 已进入技术验证，但在真实模型供应商、协会语料评测、费用阈值和数据出境审批全部验收前，
> 不对外宣称为“AI 平台”。

本仓库用于实现平台第二版方案：**向上连接政策与标准，横向连接友好协会，向下服务会员企业，在平台内部形成可持续运营的产业生态。**

当前已形成可运行的首期工程基线，模块边界、OIDC 数据域、会员审核审计和 Excel 调查采集闭环已经接通。企业调查表作为 106 家会员冷启动入口，经过逐行预检、协会审核后进入企业、产品、需求与场景库。

## 工程结构

```text
apps/
  server/       Java 21 + Spring Boot 模块化单体业务后端
  web/          Vue 3 + TypeScript 协会/企业管理端
services/
  ai/           FastAPI 独立 AI 能力服务
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
- 106 家会员 XLSX 模板下载、批量预检、合法行提交与待审核闭环

## 核心基础约束

- 会员数据默认落 PostgreSQL；信用代码由数据库唯一索引兜底，更新与删除使用版本条件 CAS，支持多实例并发。
- 生产默认使用 OIDC/JWT；仅显式本地/测试模式可启用 HTTP Basic 演示账号，生产 Profile 会拒绝 demo 模式。
- ArchUnit 自动保护模块依赖方向，避免业务模块反向耦合启动层或内部实现。
- Web、业务后端与 AI 服务统一使用安全的 `X-Request-Id`；成功与异常响应均可关联同一次请求，非法或可注入日志的值会被替换。
- Flyway 对空库和既有非空 PostgreSQL 建立可追踪迁移；会员编辑页读取强 ETag，保存时携带 If-Match，陈旧写入返回 412。
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

## 测试与质量验证

仓库默认只执行单元测试、集成测试、权限与数据隔离回归、类型检查和构建。变异测试与隔离的 OIDC/PKCE 验收仅可手动触发；模糊请求、高压/负载、ZAP 主动扫描和网络故障注入已退出 CI，不得重新加入自动或手动工作流。

快速入口和当前可复验基线见 [测试工具使用指南](docs/testing-guide.md)，脚本参数见 [tests/README.md](tests/README.md)。常规提交执行 `.github/workflows/ci.yml`，手动质量检查执行 `.github/workflows/advanced-testing.yml`，依赖与密钥扫描执行 `.github/workflows/security-scan.yml`。

> 当前已接入生产向 OIDC/JWT、PostgreSQL/Flyway、企业数据域、审计、Excel 批量采集、附件知识入库和带出处检索闭环；本地测试仍可显式使用演示身份与内存仓储。独立 Python 智能能力服务目前仍为确定性规则实现；Java 知识链路默认关闭外部模型和 Embedding。生产上线前仍需配置正式 IdP 与真实模型凭据、执行 V1–V12 迁移/恢复演练，并用协会真实语料完成效果和费用验收。

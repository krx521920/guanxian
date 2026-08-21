# 测试工具使用指南与实测基线

## 1. 测试分层

| 层级 | 工具 | 目的 | 默认触发 |
| --- | --- | --- | --- |
| 正向/反向/异常 | JUnit、Vitest、pytest、Hypothesis | 验证正常业务、越权、坏凭据、非法参数、边界和畸形载荷 | 每次 CI |
| 变异测试 | PIT、StrykerJS、mutmut | 人为改变实现，检查测试是否真的能识别错误 | 每周/手动 |
| API 模糊测试 | Schemathesis | 根据 OpenAPI 自动生成合法、非法和边界请求 | 每周/手动 |
| 高压测试 | k6 | 验证吞吐、错误率和响应时间阈值 | 每周/手动 |
| 渗透基线 | OWASP ZAP | Web 被动检查和 OpenAPI 主动扫描 | 每周/手动 |
| 供应链安全 | Trivy、npm audit、pip-audit | 扫描依赖漏洞、密钥和错误配置 | 每次安全工作流 |
| 故障注入 | Toxiproxy | 注入 AI 服务网络延迟，验证系统降级行为 | 手动 |

## 2. 安全边界

`tests/common/SafeTarget.psm1` 会拒绝非 HTTP(S) 和非本机/Compose 内部地址。k6、Schemathesis、ZAP 与 Toxiproxy 脚本不得用于未获授权的目标。默认报告写入 `test-results/`，该目录不提交 Git。

## 3. 常规测试

在仓库根目录执行：

```powershell
./scripts/verify.ps1
```

该命令依次校验 Compose 配置、Web 类型/测试/构建、AI ruff/pytest 和 Java Maven 全量测试。各模块也可单独执行：

```powershell
Push-Location apps/server; mvn verify; Pop-Location
Push-Location apps/web; npm run typecheck; npm test; npm run build; Pop-Location
Push-Location services/ai; python -m ruff check app tests; python -m pytest; Pop-Location
```

跨模块运行时冒烟可执行 `./tests/smoke/runtime-smoke.ps1`。脚本会启动实际 Java 与 AI 进程，验证健康检查、合法账号、匿名 `401`、政策列表、确定性匹配、安全响应头，以及成功/鉴权失败/AI 匹配三条 `X-Request-Id` 往返契约；还会创建临时会员，验证 ETag 从 `"0"` 递增到 `"1"`、旧标签写入返回 `412`，最后按新标签删除。本轮结果为 `request_trace=ok`、`optimistic_concurrency=ok`，报告写入 `test-results/smoke/result.json`。
### 真实 PostgreSQL 与 IdP 验收

PostgreSQL 迁移自动测试位于 `PostgresMemberMigrationIntegrationTest`。本机 Docker Engine 已恢复，但 Testcontainers 通过 Docker Desktop 命名管道请求 `/info` 时仍收到 400，因此 Maven 会跳过该类的 2 项测试。不能把“跳过”视为通过。本轮另行使用隔离的 `postgres:16-alpine` 容器和实际 Spring Boot JAR 完成等价验收：空库 Flyway V1–V3 均成功，创建返回 ETag `"0"`，更新返回 `"1"`，删除返回 200，删除后 3 条创建/更新/删除审计仍完整保留；再按 `legacy-member-schema.sql` 装入旧库，验证 baseline 0 + V1/V2/V3 共 4 条历史、存量会员和版本 3 保留、API ETag `"3"`、新增企业使用旧库实际协会 UUID、删除后 2 条审计保留。CI 的标准 Docker socket 环境仍应执行 Testcontainers 类。

真实 Keycloak 登录协议可用以下工具复验：

```powershell
./tools/testing/Test-KeycloakPkce.ps1 `
  -Authority 'https://identity.example.com/realms/guanxian' `
  -ClientId 'guanxian-web' `
  -Username $env:OIDC_TEST_USERNAME `
  -Password $env:OIDC_TEST_PASSWORD `
  -RedirectUri 'https://platform.example.com/auth/callback'
```

工具验证 discovery issuer、S256、登录页、302 回调、state、授权码、token 兑换、access token、ID token 与 UserInfo。密码只通过参数传入，不写报告。`-AllowInsecureLoopback` 仅用于临时 Keycloak 在 `127.0.0.1` 上的 HTTP 测试；工具会拒绝将该开关用于非回环主机，生产和共享测试环境必须使用 HTTPS。本轮 Keycloak 26.7.1 实测全部通过；另以两个真实 RS256 令牌验证了后端系统管理员引导、`user_account` 绑定、协会管理员 `…0106` 数据域和绑定审计。

## 4. 变异测试

### Java/PIT

```powershell
Push-Location apps/server
mvn -pl bootstrap -am verify -Pmutation
Pop-Location
```

报告：`apps/server/bootstrap/target/pit-reports/index.html`。本轮常规 `mvn verify` 发现 89 项，其中 87 项通过、2 项 PostgreSQL/Testcontainers 测试因 Docker Desktop 命名管道兼容问题跳过；对应真实 PostgreSQL 行为已按上一节独立验收。最近一次 PIT 基线（新增本轮回归测试前）为：576 个变异体中 379 个被杀死、108 个存活、89 个无覆盖，变异分数 65.80%，测试强度 77.82%。会员权限策略存活变异为 0；剩余缺口主要来自 PostgreSQL 适配器、XLSX 防御分支及低风险展示代码。存活变异应按业务风险补断言，不为分数编写无意义测试。

### Web/StrykerJS

```powershell
Push-Location apps/web
npm run mutation
Pop-Location
```

报告：`apps/web/reports/mutation/mutation.html` 和 `mutation.json`。当前基线（2026-08-21）：122 项常规测试全部通过；618 个变异体中 592 个被杀死、23 个存活、3 个无覆盖，总分 95.79%。新增的 API 路径、HTTP 方法、ETag 与导入合同层为 90.63%，HTTP 客户端为 99.55%。配置阈值为 high 80、low 60、break 50。

Windows 受限沙箱内，Stryker 在报告完成后可能因 `taskkill` 权限返回非零退出码；报告内容仍有效。在普通终端和 GitHub Ubuntu Runner 中应以进程退出码为准。

### AI/mutmut

```powershell
Push-Location services/ai
./scripts/run_mutation.ps1
Pop-Location
```

mutmut 3 需要 Linux/WSL；Windows 脚本会自动转入 WSL，要求 WSL Python 3.11+ 并已安装 `requirements-dev.txt`。本机 WSL 仅有 Python 3.10，故本轮未生成可靠变异分数；GitHub Ubuntu 工作流会执行完整任务。

## 5. API 模糊与异常测试

先在 `services/ai` 启动服务并令其监听 `127.0.0.1:18001`，再执行：

```powershell
./tests/fuzz/run-schemathesis.ps1 -SchemaUrl http://127.0.0.1:18001/openapi.json -MaxExamples 50
```

报告：`test-results/schemathesis/junit.xml`。当前基线为 4 个操作、681 个生成请求全部通过。测试曾发现并推动修复：畸形 JSON 状态码不一致、布尔值被数值字段接受、权重与 OpenAPI 不一致、405 响应缺少 `Allow` 头、整数型 JSON 浮点表示、空白字符串清理后的长度偏差、重复业务标识冲突类型以及安全 URL 规则未写入 Schema 等问题。

当前 AI 常规测试为 47 项通过，应用整体覆盖率 96%，请求上下文模块为 100%，三个核心规则服务为 97%–100%。纯 ASGI 多分块测试同时验证：等于上限正常交付，超过 1 字节返回唯一一套 `413`，超限块和剩余正文不再交给路由。

AI 内部属性测试默认随 pytest 执行；独立高压测试为 16 线程、2,000 轮、共 6,000 次核心服务调用：

```powershell
Push-Location services/ai
python -m pytest -m stress -q
Pop-Location
```

## 6. k6 高压测试

启动业务后端并监听 `127.0.0.1:18080`：

```powershell
./tests/performance/run-k6.ps1 -Profile smoke
./tests/performance/run-k6.ps1 -Profile load
./tests/performance/run-k6.ps1 -Profile stress
```

脚本优先使用 `.tools/k6` 或系统 k6，否则使用 Docker 镜像。阈值为：HTTP 失败率小于 1%、p95 小于 500ms、p99 小于 1,000ms、检查通过率大于 99%。阈值失败会令命令和 CI 失败。

本轮加固后 stress 实测：配置最大 120 VU、90 秒、39,516 个请求、0 失败、约 438.46 请求/秒，92,204 项检查全部通过；每个健康、政策和匹配请求都使用独立 `X-Request-Id` 并校验精确回显。p95 177.45ms、p99 243.33ms、最大 396.59ms。报告：`test-results/k6/stress-summary.json`。

## 7. OWASP ZAP 渗透基线

Docker 可用时执行：

```powershell
./tests/security/run-zap.ps1 -Mode baseline -Target http://127.0.0.1:8081
./tests/security/run-zap.ps1 -Mode api -Target http://127.0.0.1:18001/openapi.json
```

规则位于 `tests/security/zap-rules.conf`，报告生成到 `test-results/zap/`。Web Nginx 与 AI API 已配置 X-Content-Type-Options、X-Frame-Options 和 Referrer-Policy 等安全头。Docker Engine 当前可用，但本次验收范围是 PostgreSQL 与 IdP，未重复执行容器化 ZAP；GitHub `advanced-testing.yml` 会在隔离 Runner 上执行 API 主动扫描并上传报告。

## 8. 供应链扫描

```powershell
Push-Location apps/web; npm audit --audit-level=moderate; Pop-Location
./.tools/security-venv/Scripts/pip-audit.exe -r services/ai/requirements.txt -r tests/requirements.txt
```

GitHub `security-scan.yml` 还使用 Trivy 扫描 HIGH/CRITICAL 依赖漏洞、密钥和错误配置。本轮 `npm audit` 与 `pip-audit` 均为 0 个已知漏洞。

## 9. Toxiproxy 故障注入

```powershell
./tests/resilience/run-toxiproxy.ps1
```

脚本通过 Compose 启动 AI 与固定版本的 Toxiproxy 2.12.0，在代理链路注入 1,500ms 延迟、验证延迟确实生效，然后移除 toxic。报告和终端结果用于确认上游超时、降级及监控配置。Docker Engine 当前可用；本次 PostgreSQL/IdP 验收未重复执行该故障注入任务。

## 10. CI 运行策略

- `ci.yml`：每次 push/PR 执行快速常规测试，并在 Java 与 AI 单模块通过后启动两项服务做跨模块契约冒烟。
- `security-scan.yml`：执行 Trivy、npm audit 与 pip-audit。
- `advanced-testing.yml`：每周五 UTC 18:00（北京时间周六 02:00）或手动执行变异、模糊、高压和 ZAP。

所有工作流仅扫描检出的仓库内容或 Runner 内临时启动的本项目服务，不连接生产环境。

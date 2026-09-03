# 测试与质量验收指南

更新时间：2026-09-03。

## 1. 当前批准的验证范围

| 层级 | 工具 | 目的 | 触发方式 |
| --- | --- | --- | --- |
| 常规单元与接口测试 | JUnit、Vitest、pytest | 验证业务规则、参数边界和稳定错误合同 | 每次 CI |
| 权限与数据隔离 | MockMvc、Spring Security、PostgreSQL Testcontainers | 验证身份、企业数据域、跨协会授权和 ETag | 每次 CI/PR |
| 类型检查与构建 | vue-tsc、Vite、Maven、ruff | 验证类型、依赖和可构建性 | 每次 CI |
| OIDC/PKCE 验收 | Keycloak、Playwright、PowerShell | 验证隔离身份环境中的授权码流程和角色业务旅程 | 手动 |
| 变异测试 | PIT、StrykerJS、mutmut | 检查常规测试能否识别实现错误 | 手动 |
| 供应链检查 | Trivy、npm audit、pip-audit | 静态检查仓库和依赖 | 安全工作流 |

模糊请求、高压/负载、ZAP 主动扫描和网络故障注入不属于当前批准范围，已从 GitHub Actions 和项目执行说明中移除。AI 高负载和生成输入标记用例也由 pytest 默认配置排除。不得把这些任务重新加入 CI、定时任务或开发自动化。

## 2. 可追溯基线

当前仓库包含 Flyway `V1` 至 `V23` 共 23 个版本化迁移。V23 为跨协会接入申请和企业共享同意增加乐观锁版本，并让自动过期、关系失效撤销也递增版本。测试总数与通过数只能从同一提交的命令输出和报告中读取，不得使用旧数量或历史真实依赖结果代替当前验收。

2026-09-02 本地验证基线：

- Web：20 个 Vitest 文件、210 项测试全部通过，`npm run typecheck` 和 `npm run build` 通过；Playwright 4 个文件中的 8 条 E2E 全部通过。
- Java：69 份 Surefire 报告共 349 项测试全部通过，失败 0、错误 0、跳过 0；其中 62 项 Testcontainers 已连接真实 Docker 执行，不再计为环境跳过。
- AI：常规范围 46 项全部通过，5 项被默认配置排除；`stress` 与 `fuzz` 标记均不进入默认执行。
- 运维配置：`tests/operations` 42 项、`tests/config` 10 项，当前本地共 52 项全部通过；E2E Compose 配置使用隔离测试环境变量渲染通过。
- 真实依赖检查：当前代码已在 PostgreSQL 16 Testcontainers 完成 Flyway V1–V23、数据域和生命周期回归；8 条 Playwright 在真实 PostgreSQL、MinIO、Redis、隔离 Keycloak/OIDC 联合环境执行，覆盖身份权限、附件、通知、会员 Excel 导入审核、跨协会授权撤销、匹配邀请洽谈反馈成果归档和带可下载出处问答。

本机 Docker engine 可用，但 Docker Hub 的证书链阻止了应用镜像基础层重新拉取；本轮因此使用当前分支构建的后端 JAR 和当前前端源码，连接仓库标准 E2E 依赖容器完成浏览器验收。CI 仍需显式验证完整 Compose 应用镜像构建；正式 IdP、ClamAV、生产 TLS、备份恢复和真实告警也需另行验收。

测试数量、迁移版本或执行范围变化时，必须在同一提交中更新本节；不得继续引用旧的 V1–V18、314 项、167 项或其他不属于当前提交的基线。

2026-09-03 单机部署及 E2E 启动脚本修复的本地验证（局部范围）：

- `tests/config` 44 项、`tests/operations` 42 项，共 86 项通过，失败/跳过均为 0。
- 配置测试显式开启 `GUANXIAN_SINGLE_HOST_DB_TEST=1` 和 `GUANXIAN_SINGLE_HOST_GATEWAY_TEST=1`，
  包含真实隔离 PostgreSQL 初始化回归及真实 Nginx 配置解析；未操作生产服务器。
- 新增 13 项启动脚本测试，覆盖成功退出、容器缺失、CLI 失败、多容器、未完成任务、
  非零退出码、OOM 和状态字段不完整等情况。成功退出用例先在旧脚本复现空值错误，再验证修复。
- 提交 `92adb52` 的浏览器 CI 在初始化容器检查阶段失败，未执行浏览器旅程。修复后仍须查看
  **同一提交**的完整 CI 和 `browser-e2e` 结果，不以以上命令替身测试或历史浏览器结果代替。

## 3. 常规验证命令

仓库根目录统一入口：

```powershell
./scripts/verify.ps1
```

模块级命令：

```powershell
Push-Location apps/server; mvn verify; Pop-Location
Push-Location apps/web; npm run typecheck; npm test; npm run build; Pop-Location
Push-Location services/ai; python -m ruff check app tests; python -m pytest; Pop-Location
python -m unittest discover -s tests/operations -p "test_*.py" -v
python -m unittest discover -s tests/config -p "test_*.py" -v
```

`services/ai/pyproject.toml` 默认排除 `stress` 和 `fuzz` 标记；常规验证不得通过命令行覆盖这一限制。

本地运行时契约可执行：

```powershell
./tests/smoke/runtime-smoke.ps1
```

该脚本只验证本项目隔离环境中的健康、正常鉴权、权限拒绝、请求追踪与 ETag 乐观并发，不执行主动扫描或高负载行为。

真实依赖浏览器 E2E：

```powershell
./tools/testing/Start-E2eStack.ps1
Push-Location apps/web
npm run test:e2e
Pop-Location
```

该栈固定使用项目名 `guanxian-platform-e2e` 和 `tests/e2e/compose.env` 中的测试专用端口/凭据。Playwright 单 worker、零重试；`E2E_WEB_BASE_URL` 如有变化，必须同步修改 Web OIDC 回调和 Keycloak 客户端登记地址。它只验证隔离环境，不替代正式 IdP、TLS、备份恢复、集中日志或告警验收。

初始化任务 `e2e-seed` 正常完成后就是退出状态，不应当重启以维持“运行中”。启动脚本通过
`docker compose ps --all --quiet e2e-seed` 查找唯一容器，并核验状态为 `exited`、退出码为 `0`、
未运行且未发生 OOM。容器缺失、查询失败、多个容器、状态不完整或任务尚未结束都必须报错，
不能输出 `Seed=completed`；失败诊断也会列出已退出容器。排错不要删除正式库或生产数据卷。

启动脚本回归测试（需要 PowerShell 7，使用命令替身，不会启动容器或接触数据库）：

```bash
python -m unittest discover -s tests/config -p test_e2e_stack_startup.py -v
```

该测试已包含在 CI 的 `production-config` 配置测试发现范围内；真实容器和浏览器旅程仍由
`browser-e2e` 单独验证。命令替身测试通过不代表浏览器 E2E 已通过。

## 4. PostgreSQL 与身份验收

真实 PostgreSQL 回归由以下 Testcontainers 测试承担：

- `PostgresMemberMigrationIntegrationTest`：空库/存量库迁移、会员 ETag、软删除恢复和审计。
- `PostgresCrossTenantAuthorizationIntegrationTest`：跨协会关系、共享策略、企业授权和匹配状态机。
- `PostgresKnowledgeIsolationIntegrationTest`：知识入库、引用轨迹、向量持久化和跨协会隔离。
- `PostgresIdentityPolicyNotificationUpgradeIntegrationTest`：V12→V23 身份、政策归属、通知订阅范围、消息状态及后续迁移兼容升级合同。
- `PostgresPolicyImpactAssociationMigrationIntegrationTest`：V15→V17 政策影响同协会回填、复合外键与不一致脏数据失败回滚。
- `PostgresCrossAssociationIntegrityMigrationIntegrationTest`：V16→V17 跨协会授权/推荐/策略约束、脏数据失败回滚、到期授权原子物化及双事务唯一授权。
- `PostgresMatchWorkflowIntegrityMigrationIntegrationTest`：V17→V18 干净升级和完整合法路径；同企业供需、旧枚举、非法父状态、残留待应答邀请、待处理邀请提前应答、普通洽谈跳级/终态后追加、成功反馈携带关闭原因、单方成功归档的失败回滚；接受邀请后直接终止、拒绝/取消/终止原因一致性、邀请终态与匹配/成果审计事实不可改写、`MATCH.outcomes` 跨协会字段白名单、运行期非法转移/参与方阻断、反馈关闭原因互斥、并发邀请唯一性和反馈 ETag/CAS。
- `PostgresSystemContextScopeIntegrationTest` 与 `PostgresEcosystemSystemContextIntegrationTest`：系统管理员全局/协会/企业上下文及写入边界。
- `PostgresEnterpriseLifecycleIsolationIntegrationTest`：企业停用/删除后的历史只读与写入阻断。
- `PostgresCollaborationSystemContextIntegrationTest`：协作事项系统上下文与跨租户隔离。
- `PostgresPolicyLifecycleIntegrationTest` 与 `PostgresPolicyImpactIntegrationTest`：政策生命周期、数据域和影响分析落库。
- `PostgresMinioRedisIntegrationTest`：真实 PostgreSQL 元数据、MinIO 对象读写/软删除恢复与 Redis 限流联合验证。
- `PostgresAttachmentContentValidationUpgradeIntegrationTest`：V19→V20 将历史 `PENDING` 附件失败关闭为 `REQUIRES_REUPLOAD`、递增版本和更新时间，同时保持 `VALIDATED` 记录不变。
- `KnowledgeApiIntegrationTest` 与 `RagEvaluationServiceTest`：知识文档草稿、送审、发布、跨协会范围、无评测时拒绝 AI 名称、真实证据/拒答指标达标放行及跨协会评测证据阻断。

Docker 不可用时，Testcontainers 未执行不能视为通过；应由标准 Ubuntu CI Runner 完成。

V16–V18 的升级测试明确验证“先检查、后约束”：无法无歧义处理的历史数据必须让迁移失败并保留原版本，运维人员根据异常的 `DETAIL` 和 `HINT` 审核修正后再执行。V19/V20 分别显式规范通知和附件状态；V21/V22 追加采集出处、知识生命周期和评测记录；V23 仅补版本控制，不替历史资料伪造出处或评测结论。所有 V1–V23 迁移必须在存量数据隔离副本复验；开发内容校验不能替代生产 ClamAV 扫描。

OIDC/PKCE 手动验收：

```powershell
./tools/testing/Test-KeycloakPkce.ps1 `
  -Authority 'https://identity.example.com/realms/guanxian' `
  -ClientId 'guanxian-web' `
  -Username $env:OIDC_TEST_USERNAME `
  -Password $env:OIDC_TEST_PASSWORD `
  -RedirectUri 'https://platform.example.com/auth/callback'
```

密码只通过参数传入，不写入报告。生产和共享环境必须使用 HTTPS。

## 5. 手动变异测试

变异测试不会定时执行，只能在明确需要评估测试质量时手动运行：

```powershell
Push-Location apps/server; mvn -pl bootstrap -am verify -Pmutation; Pop-Location
Push-Location apps/web; npm run mutation; Pop-Location
Push-Location services/ai; ./scripts/run_mutation.ps1; Pop-Location
```

变异测试只改变本地测试进程中的实现，不向运行服务生成网络请求。报告必须和当次提交、测试命令及时间一起归档，历史分数不能冒充当前结果。

## 6. CI 策略

- `.github/workflows/ci.yml`：在 `main` push 或 PR 中执行常规测试、类型检查、构建和隔离集成。
- `.github/workflows/security-scan.yml`：执行静态仓库和供应链检查。
- `.github/workflows/advanced-testing.yml`：仅允许手动执行变异测试与隔离 OIDC/PKCE 验收，不配置 schedule。

提交前应检查工作流中不存在 Schemathesis、k6、ZAP、Toxiproxy 或其他主动/高负载任务引用。

## 7. 结果声明规则

- “通过”必须对应实际执行且退出码为 0 的命令或可读取报告。
- 因环境缺失而跳过的测试必须单独列出，不能计入通过。
- 单元测试通过不能替代真实 PostgreSQL、OIDC、MinIO、Redis 或浏览器 E2E。
- 只有完成角色业务旅程、数据恢复和生产依赖验收后，才能声明平台业务或生产闭环。

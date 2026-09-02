# 测试与质量验收指南

更新时间：2026-09-02。

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

当前仓库包含 Flyway `V1` 至 `V22` 共 22 个版本化迁移。测试总数与通过数只能从同一提交的命令输出和报告中读取，不得使用旧数量或历史真实依赖结果代替当前验收。

2026-09-02 本地验证基线：

- Web：20 个 Vitest 文件、208 项测试全部通过，`npm run typecheck` 和 `npm run build` 通过。
- Java：69 份 Surefire 报告共定义 346 项测试，实际执行并通过 284 项，失败 0、错误 0。本次本机 Docker daemon 不可用，其中 62 项 Testcontainers 用例被跳过，不能计入通过。新增 RAG 发布闸门的 3 项普通测试已执行通过。
- AI：常规范围 46 项全部通过，5 项被默认配置排除；`stress` 与 `fuzz` 标记均不进入默认执行。
- 运维配置：`tests/operations` 28 项、`tests/config` 10 项，当前本地共 38 项全部通过；E2E Compose 配置使用隔离测试环境变量渲染通过。
- 真实依赖检查：早期基线曾在 PostgreSQL 16 Testcontainers 和隔离 PostgreSQL、MinIO、Redis、Keycloak/OIDC 联合环境中执行，并留有 Playwright 5/5 的历史记录。该记录不包含当前 V21/V22、ClamAV 和本轮全部代码变更，不能作为本轮真实依赖验收证据。

本次本机 Docker daemon 不可用，所以当前变更的 PostgreSQL Testcontainers、PostgreSQL/MinIO/Redis/Keycloak 联合环境和浏览器 E2E 均未完成当次复验。必须在 Docker 可用且证书链可信的 CI 或测试环境补跑；正式 IdP、备份恢复和真实告警仍需另行验收。

测试数量、迁移版本或执行范围变化时，必须在同一提交中更新本节；不得继续引用旧的 V1–V18、314 项、167 项或其他不属于当前提交的基线。

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

## 4. PostgreSQL 与身份验收

真实 PostgreSQL 回归由以下 Testcontainers 测试承担：

- `PostgresMemberMigrationIntegrationTest`：空库/存量库迁移、会员 ETag、软删除恢复和审计。
- `PostgresCrossTenantAuthorizationIntegrationTest`：跨协会关系、共享策略、企业授权和匹配状态机。
- `PostgresKnowledgeIsolationIntegrationTest`：知识入库、引用轨迹、向量持久化和跨协会隔离。
- `PostgresIdentityPolicyNotificationUpgradeIntegrationTest`：V12→V22 身份、政策归属、通知订阅范围、消息状态及后续迁移兼容升级合同。
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

V16–V18 的升级测试明确验证“先检查、后约束”：无法无歧义处理的历史数据必须让迁移失败并保留原版本，运维人员根据异常的 `DETAIL` 和 `HINT` 审核修正后再执行。V19/V20 分别显式规范通知和附件状态；V21/V22 追加采集出处、知识生命周期和评测记录，不替历史资料伪造出处或评测结论。所有 V1–V22 迁移必须在存量数据隔离副本复验；开发内容校验不能替代生产 ClamAV 扫描。

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

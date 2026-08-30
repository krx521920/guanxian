# 测试与质量验收指南

更新时间：2026-08-30。

## 1. 当前批准的验证范围

| 层级 | 工具 | 目的 | 触发方式 |
| --- | --- | --- | --- |
| 常规单元与接口测试 | JUnit、Vitest、pytest | 验证业务规则、参数边界和稳定错误合同 | 每次 CI |
| 权限与数据隔离 | MockMvc、Spring Security、PostgreSQL Testcontainers | 验证身份、企业数据域、跨协会授权和 ETag | 每次 CI/PR |
| 类型检查与构建 | vue-tsc、Vite、Maven、ruff | 验证类型、依赖和可构建性 | 每次 CI |
| OIDC/PKCE 验收 | Keycloak、PowerShell | 验证隔离身份环境中的授权码流程 | 手动 |
| 变异测试 | PIT、StrykerJS、mutmut | 检查常规测试能否识别实现错误 | 手动 |
| 供应链检查 | Trivy、npm audit、pip-audit | 静态检查仓库和依赖 | 安全工作流 |

模糊请求、高压/负载、ZAP 主动扫描和网络故障注入不属于当前批准范围，已从 GitHub Actions 和项目执行说明中移除。AI 高负载和生成输入标记用例也由 pytest 默认配置排除。不得把这些任务重新加入 CI、定时任务或开发自动化。

## 2. 可追溯基线

当前仓库包含 Flyway `V1` 至 `V17` 共 17 个版本化迁移。Java 普通全量回归包含 62 个 Surefire 测试套件、279 项测试，失败、错误和跳过均为 0。

2026-08-30 本地常规验证基线：

- Web：14 个 Vitest 文件、154 项测试通过，`vue-tsc -b` 与 Vite 生产构建通过。
- Java：62 个 Surefire 测试套件、279 项测试通过，失败、错误和跳过均为 0；Flyway V1–V17 空库迁移、V15→V17 政策影响升级和 V16→V17 跨协会完整性升级通过。
- AI：默认批准范围收集 46 项测试；`stress` 与 `fuzz` 标记均不进入默认执行。
- 运维配置：`tests/operations` 28 项、`tests/config` 10 项，CI 分目录执行，共 38 项。
- 真实依赖检查：PostgreSQL 16 Testcontainers 已验证 Flyway V1–V17 空库迁移、V15→V17 与 V16→V17 干净数据升级路径、脏数据迁移失败事务回滚及并发重复授权阻断；Redis、Keycloak、MinIO 仍需在第五阶段完整浏览器 E2E 中联合复验。

测试数量、迁移版本或执行范围变化时，必须在同一提交中更新本节；不得继续引用旧的 V1–V3、89 项、125 项或 47 项基线。

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

## 4. PostgreSQL 与身份验收

真实 PostgreSQL 回归由以下 Testcontainers 测试承担：

- `PostgresMemberMigrationIntegrationTest`：空库/存量库迁移、会员 ETag、软删除恢复和审计。
- `PostgresCrossTenantAuthorizationIntegrationTest`：跨协会关系、共享策略、企业授权和匹配状态机。
- `PostgresKnowledgeIsolationIntegrationTest`：知识入库、引用轨迹、向量持久化和跨协会隔离。
- `PostgresIdentityPolicyNotificationUpgradeIntegrationTest`：V12→V15 身份、政策归属和通知订阅升级合同。
- `PostgresPolicyImpactAssociationMigrationIntegrationTest`：V15→V17 政策影响同协会回填、复合外键与不一致脏数据失败回滚。
- `PostgresCrossAssociationIntegrityMigrationIntegrationTest`：V16→V17 跨协会授权/推荐/策略约束、脏数据失败回滚、到期授权原子物化及双事务唯一授权。
- `PostgresSystemContextScopeIntegrationTest` 与 `PostgresEcosystemSystemContextIntegrationTest`：系统管理员全局/协会/企业上下文及写入边界。
- `PostgresEnterpriseLifecycleIsolationIntegrationTest`：企业停用/删除后的历史只读与写入阻断。
- `PostgresCollaborationSystemContextIntegrationTest`：协作事项系统上下文与跨租户隔离。
- `PostgresPolicyLifecycleIntegrationTest` 与 `PostgresPolicyImpactIntegrationTest`：政策生命周期、数据域和影响分析落库。

Docker 不可用时，Testcontainers 未执行不能视为通过；应由标准 Ubuntu CI Runner 完成。

V16、V17 的升级测试明确验证“先检查、后约束”：无法无歧义处理的历史数据必须让迁移失败并保留 V15/V16 版本，运维人员根据异常的 `DETAIL` 和 `HINT` 审核修正后再执行。测试不会把迁移中的静默业务数据清洗视为可接受行为。

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

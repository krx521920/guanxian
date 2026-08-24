# 生产部署、回滚与灾备运行手册

## 1. 适用范围和底线

本手册覆盖管线协会平台的 PostgreSQL、MinIO、Redis、后端服务和前端服务。生产操作必须由两人复核：一名执行人、一名审批或见证人。变更单中必须记录 Git 提交、镜像摘要、备份清单、验证结果和回滚决定。

以下规则不可绕过：

- 禁止在命令行、日志或 Git 中记录密码、令牌和私钥。
- PostgreSQL 是业务数据权威源；MinIO 是附件内容权威源；Redis 只承载可重建的缓存和限流状态。
- 数据库迁移坚持向前兼容。应用回滚前先判断旧版本能否读取新结构，不能假定恢复备份等同于普通应用回滚。
- `postgres_restore_drill.py` 从代码上只允许 `guanxian_restore_test_` 前缀的测试库，不能用于生产恢复。
- 未完成备份完整性校验和恢复演练，不得宣称具备灾备能力。

## 2. 上线前准备

1. 从审批通过的 Git 提交构建镜像，记录每个镜像的不可变 digest；生产部署不得使用 `latest`。
2. 在受控密钥系统中配置生产密码。`.env` 仅作为部署时输入，限制文件权限且不得提交。
3. 执行配置预检：

   ```bash
   python tools/deployment/validate_production_env.py --env-file .env
   docker compose --env-file .env config --quiet
   ```

4. 确认 HTTPS 证书、DNS、OIDC issuer/JWK、回调地址、PostgreSQL、MinIO 和 Redis 的连通性。
5. 确认磁盘余量至少能容纳当前数据库的两份备份和一次恢复演练。
6. 在部署前创建一次 PostgreSQL 备份，并将 MinIO 对象存储快照或版本状态记入同一变更单。

## 3. PostgreSQL 备份

备份脚本固定调用 Compose 中的 `postgres` 服务，使用 custom format、`--no-owner` 和 `--no-privileges`。输出先写 `.partial` 文件，成功后原子改名，并生成包含文件大小和 SHA-256 的 JSON 清单。备份不会覆盖同名文件，也不会把密码写入清单。

先检查计划，不连接数据库：

```bash
python tools/operations/postgres_backup.py --dry-run
```

执行备份：

```bash
python tools/operations/postgres_backup.py
```

默认文件位于 `backups/postgres/`。该目录只适合临时落盘，不能作为唯一副本。值班人员必须：

1. 检查命令退出码为 0、归档非空、`.manifest.json` 存在。
2. 将归档和清单作为一个不可分割的集合复制到加密、访问受控、开启保留策略的异地存储。
3. 在异地副本上重新计算 SHA-256，与清单比对。
4. 记录备份时间、数据库、字节数、SHA-256、存储位置、执行人和复核人。

建议基线为每日全量备份、保留 35 天，每月归档保留 12 个月；实际周期由数据负责人依据法规和存储成本批准。任何清理都必须先证明至少还有两份可验证副本。

## 4. 仅测试目标的恢复演练

恢复演练应在与生产隔离的测试环境执行。归档必须与备份脚本生成的清单放在同一目录。工具会在运行 Docker 前核验文件名、大小和 SHA-256。

第一步始终是 dry-run（默认即 dry-run）：

```bash
python tools/operations/postgres_restore_drill.py \
  --archive backups/postgres/guanxian-20260824T010203Z.dump \
  --target-database guanxian_restore_test_20260824 \
  --confirm-target guanxian_restore_test_20260824
```

执行需要同时满足三个条件：目标名通过测试前缀校验、`--confirm-target` 完全一致、一次性环境保护值正确。

Linux/macOS：

```bash
GUANXIAN_ALLOW_TEST_RESTORE=I_UNDERSTAND_THIS_DROPS_TEST_DATA \
python tools/operations/postgres_restore_drill.py \
  --archive backups/postgres/guanxian-20260824T010203Z.dump \
  --target-database guanxian_restore_test_20260824 \
  --confirm-target guanxian_restore_test_20260824 \
  --execute
```

PowerShell：

```powershell
$env:GUANXIAN_ALLOW_TEST_RESTORE = 'I_UNDERSTAND_THIS_DROPS_TEST_DATA'
python tools/operations/postgres_restore_drill.py `
  --archive backups/postgres/guanxian-20260824T010203Z.dump `
  --target-database guanxian_restore_test_20260824 `
  --confirm-target guanxian_restore_test_20260824 `
  --execute
Remove-Item Env:GUANXIAN_ALLOW_TEST_RESTORE
```

工具会删除同名测试库、从 `template0` 新建、执行 `pg_restore --exit-on-error`，再验证 `flyway_schema_history`。恢复或验证失败时会尽力删除不完整的测试库。成功后测试库会保留供业务抽查；抽查完成后使用经过复核的精确库名删除，不得用通配符。

每季度至少执行一次恢复演练，并验证：会员数量、审计记录、最近业务记录、附件元数据及其对应 MinIO 对象。记录实际恢复耗时和最近可恢复时间点。

## 5. 部署步骤

1. 冻结变更窗口，确认负责人、回滚负责人和观察窗口。
2. 完成第 2 节预检和第 3 节部署前备份。
3. 拉取审批版本并记录 digest：`docker compose pull`。
4. 先启动 PostgreSQL、MinIO、Redis，等待健康检查通过。
5. 启动后端。Flyway 迁移失败时立即停止，不要用 `repair` 或手工改迁移历史绕过。
6. 启动前端和网关，依次验证健康接口、OIDC 登录、协会管理员、企业管理员、只读用户、附件上传下载、审计记录和限流降级。
7. 观察至少 30 分钟：5xx、P95 延迟、数据库连接、磁盘、JVM、MinIO 错误率、Redis 限流错误和身份认证失败率。
8. 证据完整且无高优先级告警后关闭变更窗口。

## 6. 回滚

应用回滚优先于数据恢复：

1. 停止继续发布，记录首个异常时间和影响范围。
2. 判断新旧版本数据库兼容性。若新迁移是向前兼容的，切回上一审批镜像 digest，保持数据库不变。
3. 若旧应用无法读取新结构，优先发布兼容修复；不要删除 Flyway 迁移文件，不要手工倒改 `flyway_schema_history`。
4. 只有数据损坏或误删且业务负责人批准时才进入生产数据恢复流程。当前仓库工具故意不提供生产恢复命令，生产恢复必须由 DBA 根据已验证备份编写一次性方案、双人复核并在隔离副本上先演练。
5. 回滚后重复登录、权限、附件、审计和核心业务冒烟测试，并保留事故时间线。

MinIO 对象与 PostgreSQL 附件元数据必须恢复到一致时间点。Redis 无需从备份恢复；清空后由应用重建，但要关注启动瞬间的缓存穿透和限流行为。

## 7. 灾难恢复顺序

建议恢复顺序：网络和密钥系统 → PostgreSQL → MinIO → 后端 → OIDC → 前端/网关 → Redis → 异步任务和通知。

暂定目标为 RPO 24 小时、RTO 4 小时，必须由业务负责人正式确认。若业务要求更低 RPO，应采用受监控的 WAL 归档/托管 PITR，而不能只依赖每日 `pg_dump`。

恢复后必须核验：

- Flyway 版本和启动日志无迁移错误。
- 数据库清单、核心表数量、最近记录时间、企业数据范围和审计链。
- 附件元数据与 MinIO 对象抽样一致，下载哈希正确。
- OIDC 真实浏览器登录及协会/企业权限边界。
- Redis 故障时限流保持 fail-closed，恢复后无异常放量。

## 8. 监控与告警最低集

生产环境至少配置下列指标和告警：

- 备份：最近成功时间、归档字节数、清单哈希校验、异地复制状态、季度恢复演练状态。
- PostgreSQL：连接使用率、事务失败、慢查询、锁等待、复制/WAL（如启用）、磁盘和预计耗尽时间。
- MinIO：容量、磁盘错误、4xx/5xx、对象读写延迟和版本/生命周期任务失败。
- Redis：连通性、内存、驱逐、命令延迟，以及限流存储不可用时的 fail-closed 次数。
- 应用：存活/就绪、5xx、P95/P99、JVM 堆、线程、OIDC/JWK 失败、权限拒绝、审计写入失败。
- 基础设施：CPU、内存、磁盘、证书到期、时钟偏差、容器重启和镜像漏洞。

高优先级告警必须包含负责人、升级路径和可执行的处置链接。首次处置先保护数据和证据，禁止在未留存日志与时间线时反复重启。

## 9. 演练和手册维护

- 每次发布执行配置预检和备份 dry-run；CI 执行运维脚本参数安全单元测试。
- 每季度做 PostgreSQL 恢复演练和 MinIO 抽样恢复，每半年做完整灾备切换桌面演练。
- 每次事故、架构变化、供应商变化或恢复演练失败后更新本手册。
- 手册版本必须与应用版本一同评审，未验证的步骤标注为“待验证”，不得当作已具备能力对外承诺。

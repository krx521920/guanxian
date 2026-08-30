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

### 系统管理员首次引导

生产环境默认不信任单独的 IdP `SYSTEM_ADMIN` 角色声明。系统管理员应以令牌 `sub` 精确绑定一个 `ACTIVE` 的 `user_account`；仅在首次建号时，才可把经过双人复核的精确 `sub` 临时写入 `GUANXIAN_JWT_BOOTSTRAP_SYSTEM_ADMIN_SUBJECTS`（多个值以逗号分隔）。变量缺失或为空时保持 fail-closed，任何未绑定且不在名单中的主体均返回 `IDENTITY_NOT_BOUND`。

引导账号完成数据库绑定并验证登录后，立即从名单移除该 `sub`、重新部署并复验。不要填写用户名、邮箱、通配符或 IdP 角色名；主体停用或解绑产生的撤销记录优先于白名单，不能用白名单恢复。升级前若仍依赖“任意 IdP 系统管理员角色可引导”的旧行为，必须先建立数据库绑定，或在变更单中登记一份最小、短期的精确主体名单，否则升级后这些未绑定令牌会按设计收到 `403`。

### V13–V15 身份与租户迁移闸门

V13–V15 属于有意收紧写入契约的迁移，不能把执行后的数据库直接交还给 V12 及更早镜像：

- V13 将未知用户状态统一隔离为 `INACTIVE`，并启用身份版本与撤销主体记录。
- V14 禁止继续创建或更新无协会归属的政策；存量孤儿政策保留为只读，必须由业务负责人提供“政策 ID → 协会 ID”映射后再归属，不能猜测归属。
- V15 将通知订阅唯一范围改为“用户 + 协会 + 类型”；能够从用户绑定确定协会的旧订阅自动回填，无法确定归属的旧订阅保留但置为 `INACTIVE`。

上线变更单必须先统计未知用户状态、无归属政策和无归属订阅，附上处置清单，并在隔离副本完成 V12→当前版本迁移回归。V13–V15 一旦在生产执行，回滚目标不得早于包含同一租户契约的兼容版本；若应用异常，优先发布向前修复。只有经过 DBA 演练和业务负责人批准的数据恢复，才能回到迁移前备份。

生产编排使用独立的 `compose.production.yml`。它只公开 80/443；PostgreSQL、后端和前端
没有宿主机端口，分别位于数据、应用和边缘网络。后端访问外部 OIDC、HTTPS MinIO 和
rediss Redis 时走单独的 egress 网络。数据库密码、对象存储凭据和 Redis URL 通过
`configtree:/run/secrets/` 装载，禁止回退到明文环境变量。

TLS 证书、私钥和所有 `*_FILE` 必须位于主机受控目录，权限只允许部署账号读取。先渲染
真实告警接收器（脚本不会打印 Webhook URL）：

```bash
python tools/operations/render_alertmanager_config.py \
  --webhook-url-file "$ALERT_WEBHOOK_URL_FILE" \
  --output observability/runtime/alertmanager.yml
```

然后预检并启动：

```bash
python tools/deployment/validate_production_env.py --env-file .env
docker compose --env-file .env -f compose.production.yml config --quiet
docker compose --env-file .env -f compose.production.yml --profile observability up -d
```

`observability/runtime/` 和 `secrets/` 已被 Git 忽略。任何渲染文件、令牌文件或证书都不得提交。

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
2. 先执行第 2 节迁移兼容判断。V13–V15 不得视为可直接回滚至 V12 的向前兼容变化；只有兼容矩阵明确批准的其他版本，才可切回上一审批镜像 digest 并保持数据库不变。
3. 若旧应用无法读取新结构，优先发布兼容修复；不要删除 Flyway 迁移文件，不要手工倒改 `flyway_schema_history`。
4. 只有数据损坏或误删且业务负责人批准时才进入生产数据恢复流程。当前仓库工具故意不提供生产恢复命令，生产恢复必须由 DBA 根据已验证备份编写一次性方案、双人复核并在隔离副本上先演练。
5. 回滚后重复登录、权限、附件、审计和核心业务冒烟测试，并保留事故时间线。

特别说明：执行过 V13–V15 的数据库禁止直接启动 V12 或更早应用镜像。旧通知仓储不了解协会级唯一键，旧政策写入也不了解强制协会上下文，直接回滚会重新引入跨协会写入风险。此时只允许切换到已经声明兼容 V13–V15 的镜像，或采用第 3 步的向前修复。

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

### 已随代码交付的基础配置

后端的 `/actuator/prometheus` 输出 Micrometer 指标，但它不是匿名接口：只有带
`OBSERVABILITY_READ` 权限的 OIDC 服务账号（或系统管理员）才能读取；`/actuator/health`
仍保持公开，供负载均衡器使用。生产日志采用 Spring Boot 的 Logstash JSON 控制台格式，
其中包含应用名和请求过滤器放入 MDC 的 `requestId`，可直接由集中日志代理采集。

仓库提供固定版本的 Prometheus/Alertmanager 可选编排，默认 `compose.yaml` 不会启动它：

```bash
docker compose -f compose.yaml -f compose.observability.yml --profile app --profile observability up -d
```

启动前由密钥系统生成一个仅含 `OBSERVABILITY_READ` 的短期、专用 OIDC 服务令牌文件，并将
**文件路径**（不是令牌值）以 `PROMETHEUS_SCRAPE_TOKEN_FILE` 注入 Compose。该文件会作为
Docker secret 挂载到 `/run/secrets/prometheus_scrape_token`，Prometheus 使用
`authorization.credentials_file` 读取它。令牌不得写入 `.env`、Git、镜像层、命令行、环境变量
或告警规则。非 Compose 生产环境应以等效的 secret volume/外部工作负载身份挂载该文件，并由
令牌轮换流程原子替换后重载 Prometheus；正式网络还应由网关以 TLS 保护指标链路。仓库中的
Alertmanager 接收器仅记录/聚合告警，上线前必须由运维在受控配置中接入值班 Webhook、邮件或
on-call 系统并完成一次演练。

生产编排同时提供 Loki、Promtail 和 Grafana。Promtail 只读采集 Docker JSON 日志，提取
Spring 结构化日志中的 `requestId`、`level` 和 `application` 标签；Grafana 预置 Prometheus
与 Loki 数据源，并且只绑定 `127.0.0.1` 管理端口，必须通过运维跳板机或私网访问。Docker
socket 即使只读也属于高权限接口，只允许在专用日志节点运行 Promtail，并由主机权限阻止普通
业务账号访问。排障时先以响应头中的 `X-Request-Id` 在 Loki 查询 `{service="server"} |=
"<requestId>"`，再关联 PostgreSQL `audit_log.request_id`。

生产 Alertmanager 不使用仓库内的 `operator-log` 安全占位接收器，而是强制挂载
`ALERTMANAGER_CONFIG_FILE`。上线验收必须发送一条受控测试告警，证明值班 Webhook 收到
firing 和 resolved 两条事件；未完成该演练不得宣称“真实告警已接入”。

预置告警只引用后端已导出的 Micrometer 指标：不可抓取、5xx 比率、HTTP P95、JVM 堆、
Hikari 连接池和节点磁盘空间。PostgreSQL、Redis、MinIO 还需要按实际部署接入各自的
exporter；没有 exporter 时不得把“无数据”误解为“健康”。

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

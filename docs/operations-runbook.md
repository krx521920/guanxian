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
   docker compose --env-file .env -f compose.production.yml --profile observability config --quiet
   ```

4. 确认 HTTPS 证书、DNS、OIDC issuer/JWK、回调地址、PostgreSQL、MinIO 和 Redis 的连通性；按 `GUANXIAN_STORAGE_BUCKET` 预先创建私有 MinIO bucket，并只授予应用账号所需的对象读写/删除权限。生产 Profile 不会自动创建 bucket，bucket 缺失时后端应拒绝启动。
5. 确认磁盘余量至少能容纳当前数据库的两份备份和一次恢复演练。
6. 在部署前创建一次 PostgreSQL 备份，并将 MinIO 对象存储快照或版本状态记入同一变更单。

### 系统管理员首次引导

生产环境默认不信任单独的 IdP `SYSTEM_ADMIN` 角色声明。系统管理员应以令牌 `sub` 精确绑定一个 `ACTIVE` 的 `user_account`；仅在首次建号时，才可把经过双人复核的精确 `sub` 临时写入 `GUANXIAN_JWT_BOOTSTRAP_SYSTEM_ADMIN_SUBJECTS`（多个值以逗号分隔）。变量缺失或为空时保持 fail-closed，任何未绑定且不在名单中的主体均返回 `IDENTITY_NOT_BOUND`。

引导账号完成数据库绑定并验证登录后，立即从名单移除该 `sub`、重新部署并复验。不要填写用户名、邮箱、通配符或 IdP 角色名；主体停用或解绑产生的撤销记录优先于白名单，不能用白名单恢复。升级前若仍依赖“任意 IdP 系统管理员角色可引导”的旧行为，必须先建立数据库绑定，或在变更单中登记一份最小、短期的精确主体名单，否则升级后这些未绑定令牌会按设计收到 `403`。

### V13–V23 身份、租户、跨协会、匹配、附件、知识与评测迁移闸门

V13–V23 属于有意收紧或扩展生产数据契约的迁移，不能假定执行后的数据库可由旧镜像安全写入：

- V13 将未知用户状态统一隔离为 `INACTIVE`，并启用身份版本与撤销主体记录。
- V14 禁止继续创建或更新无协会归属的政策；存量孤儿政策保留为只读，必须由业务负责人提供“政策 ID → 协会 ID”映射后再归属，不能猜测归属。
- V15 将通知订阅唯一范围改为“用户 + 协会 + 类型”；能够从用户绑定确定协会的旧订阅自动回填，无法确定归属的旧订阅保留但置为 `INACTIVE`。
- V16 为政策影响分析回填非空协会归属，并用政策与企业两侧复合外键和触发器保证同协会。只要存在政策、企业协会不一致的旧影响记录，迁移就会先失败，不做猜测性回填。
- V17 要求企业共享同意绑定具体资源，约束同意状态与撤销时间，并以部分唯一索引阻止同一授权键出现多个 `ACTIVE` 记录；同时约束跨协会推荐的资源归属和审核字段、关系完整生命周期，以及共享策略的时间、状态、版本、资源类型和对应字段白名单。空资源、非法状态、重复有效授权、跨域错绑或审核矛盾的推荐、关系状态字段矛盾及非法策略都会使迁移失败。
- V18 要求需求企业与候选企业不同，推荐先于双方确认，邀请/洽谈/反馈/成果不绕过父状态；普通洽谈从 `INITIAL_CONTACT` 开始并顺序推进，接受邀请后可以首条 `TERMINATED` 明确终止；`SUCCESS` 的关闭原因必须为空，`NO_DEAL`/`WITHDRAWN` 的关闭原因必须非空。待处理/过期邀请不能预填应答，拒绝/取消/终止原因必须与父匹配关闭原因一致；邀请终态、匹配推荐与确认事实、成果归档主体和时间不可改写。参与企业与协会归属、双方成功反馈和资源版本也必须一致。`MATCH` 跨协会字段白名单只额外开放 `outcomes`。任何歧义记录都会使迁移失败，不会自动补推荐人、确认人、邀请接受或第二方反馈。
- V19 把历史未知通知状态统一隔离为 `FAILED`，再把新写入限定为 `PENDING`、`DELIVERED`、`READ`、`FAILED`、`ARCHIVED`；当前尚未实现的非政策、带筛选或非站内通知订阅保留为审计记录但置为 `INACTIVE`，不得在上线变更中将其冒充为已支持能力。
- V20 把无法从元数据证明已完成同步内容校验的历史 `PENDING` 附件标记为 `REQUIRES_REUPLOAD`、递增版本并更新时间；这些文件在重新上传前不能下载或进入知识库。上线前必须统计受影响附件并通知归属企业，不能把内容校验冒充为病毒扫描。
- V21 增加会员模板版本、原文件 SHA-256、提交单位/企业和知识生命周期字段。历史批次的出处字段允许为空是为了保留事实，不得事后猜测或伪造；知识文档新增审核、删除主体及历史表，旧文档状态必须符合新的有限枚举。
- V22 新增分协会 RAG 评测运行表。迁移只创建存储，不生成通过结论；没有协会真实资料评测记录时，产品名称闸门必须保持关闭。
- V23 为跨协会接入申请和企业共享同意增加 `version`，并让审核、取消、撤销、自动过期及关系失效撤销使用或推进乐观锁版本；旧镜像不了解这些版本，禁止继续承担写流量。

上线变更单必须先统计未知用户状态、无归属政策/订阅、跨协会政策影响、V17/V18 的非法授权和匹配数据、V19 会归一的通知、V20 需重新上传的附件、V21 缺少出处的历史导入批次和知识文档状态，并在生产数据隔离副本完成 V12→V23 迁移回归。出现失败必须保留原版本、审核并修正业务数据后重试，禁止使用 `flyway repair`、手工修改迁移历史或未经审核的批量清洗绕过。

V17 不会在迁移时把单个“截止时间已过但状态仍为 `ACTIVE`”的旧授权静默改成 `EXPIRED`；同一资源发生首次重新授权时，触发器才会在同一事务内物化旧记录，并让唯一索引串行化并发请求。V18 只把旧反馈已有的 `submitted_at` 复制为初始 `updated_at`，不会推断其他业务事实。V19/V20 会分别显式归一通知和附件状态，V21/V22 不会替历史批次补造来源或替协会生成评测通过记录；V23 只为现有行初始化版本 0。V13–V23 一旦在生产执行，回滚目标必须是已经声明兼容这些数据库契约的镜像；若应用异常，优先发布向前修复。只有经过 DBA 演练和业务负责人批准的数据恢复，才能回到迁移前备份。

### 106 家资料与知识库投产顺序

1. 只从平台下载版本 `GX-MEMBER-SURVEY-2026-01` 的正式模板，发送给企业；不接受自行增删列、公式或旧模板。
2. 回表先进入预生产。运营人员核对源文件 SHA-256、提交单位、提交时间和工作表行号，处理文件内与正式库重复的统一信用代码；错误行修正后重新提交原文件的新版本，禁止直接改库。
3. 协会管理员逐家核对企业简介、产品、服务、需求、应用场景和联系方式，审核通过后才进入正式可见状态。导入批次、审核人和审计日志共同构成出处链。
4. 知识附件上传后必须取得 `VALIDATED` 状态；生产 `scan-mode` 必须为 `clamav`，私网 ClamAV 不可用时上传失败关闭。只有草稿送审并由有权审核人发布后才参与检索。
5. 模型与 Embedding 密钥只通过 `AI_PROVIDER_API_KEY_FILE`、`EMBEDDING_API_KEY_FILE` 指向的只读 secret 文件注入。Chat Agent 只有在 `GUANXIAN_AI_PROVIDER_ENABLED=true` 与 `GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED=true` 同时成立，并配置完整 HTTPS chat-completions endpoint、模型名和费用阈值时才会调用外部模型；任何开关关闭都会安全降级为本地带出处问答。流式端点为 `POST /api/v1/assistant/chat/stream`，运维代理必须保持该精确路径的 `proxy_buffering off` 和至少 120 秒读取超时。启用数据出境前完成审批并设定单次费用上限，抽查 `model_execution` 中 `PLATFORM_CHAT_AGENT` 的成功、失败和 `STREAM_CANCELLED` 记录。单机 Compose 目前继续强制关闭外部模型，启用前必须另行完成受控 egress 与密钥挂载评审。
6. 使用协会真实已发布资料建立至少 10 例的证据/拒答混合评测集。只有 readiness 返回 `ready=true` 且业务负责人签字后，才能在该协会范围内使用“AI 平台”名称；任何新语料、新模型或关键参数变更后重新评测。

生产编排使用独立的 `compose.production.yml`。它只公开 80/443；PostgreSQL、后端和前端
没有宿主机端口，分别位于数据、应用和边缘网络。后端访问外部 OIDC、HTTPS MinIO 和
rediss Redis 时走单独的 egress 网络。数据库密码、对象存储凭据、Redis URL 和模型密钥通过
`configtree:/run/secrets/` 装载，禁止回退到明文环境变量；ClamAV 必须使用受控私网地址。

TLS 证书、私钥和所有 `*_FILE` 必须位于主机受控目录，权限只允许部署账号读取。先渲染
真实告警接收器（脚本不会打印 Webhook URL）：

```bash
python tools/operations/render_alertmanager_config.py \
  --webhook-url-file "$ALERT_WEBHOOK_URL_FILE" \
  --output observability/runtime/alertmanager.yml

python tools/operations/render_minio_prometheus_target.py \
  --endpoint "$GUANXIAN_STORAGE_ENDPOINT" \
  --output observability/runtime/minio-target.json
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
  --report test-results/restore-drill/postgres-20260824.json \
  --execute
```

PowerShell：

```powershell
$env:GUANXIAN_ALLOW_TEST_RESTORE = 'I_UNDERSTAND_THIS_DROPS_TEST_DATA'
python tools/operations/postgres_restore_drill.py `
  --archive backups/postgres/guanxian-20260824T010203Z.dump `
  --target-database guanxian_restore_test_20260824 `
  --confirm-target guanxian_restore_test_20260824 `
  --report test-results/restore-drill/postgres-20260824.json `
  --execute
Remove-Item Env:GUANXIAN_ALLOW_TEST_RESTORE
```

工具会删除同名测试库、从 `template0` 新建、执行 `pg_restore --exit-on-error`，再验证 `flyway_schema_history` 和至少一张恢复的业务数据表。成功后生成含起止时间、耗时、归档 SHA-256、源/目标库和验证结果的 JSON 报告。恢复或验证失败时会尽力删除不完整的测试库。成功后测试库会保留供业务抽查；抽查完成后使用经过复核的精确库名删除，不得用通配符。

每季度至少执行一次恢复演练，并验证：会员数量、审计记录、最近业务记录、附件元数据及其对应 MinIO 对象。记录实际恢复耗时和最近可恢复时间点。

## 4.1 MinIO 备份和隔离恢复

MinIO 备份使用官方 `mc` 客户端；应用访问密钥从只读文件读取，只进入子进程环境，不写入命令、清单或报告。先 dry-run，再执行镜像：

```bash
python tools/operations/minio_backup.py \
  --endpoint "$GUANXIAN_STORAGE_ENDPOINT" \
  --bucket "$GUANXIAN_STORAGE_BUCKET" \
  --access-key-file "$STORAGE_ACCESS_KEY_FILE" \
  --secret-key-file "$STORAGE_SECRET_KEY_FILE"

python tools/operations/minio_backup.py \
  --endpoint "$GUANXIAN_STORAGE_ENDPOINT" \
  --bucket "$GUANXIAN_STORAGE_BUCKET" \
  --access-key-file "$STORAGE_ACCESS_KEY_FILE" \
  --secret-key-file "$STORAGE_SECRET_KEY_FILE" \
  --execute
```

快照逐对象记录相对路径、字节数和 SHA-256。恢复只能写入 `guanxian-restore-test-` 前缀的隔离 bucket，同时要求目标二次确认和环境保护值；`mc diff` 无差异才算通过：

```bash
GUANXIAN_ALLOW_MINIO_TEST_RESTORE=I_UNDERSTAND_THIS_WRITES_TEST_OBJECTS \
python tools/operations/minio_restore_drill.py \
  --snapshot backups/minio/guanxian-private-20260902T010203Z \
  --endpoint "$GUANXIAN_STORAGE_ENDPOINT" \
  --target-bucket guanxian-restore-test-20260902 \
  --confirm-target guanxian-restore-test-20260902 \
  --access-key-file "$STORAGE_ACCESS_KEY_FILE" \
  --secret-key-file "$STORAGE_SECRET_KEY_FILE" \
  --report test-results/restore-drill/minio-20260902.json \
  --execute
```

恢复 bucket 默认保留供抽样下载和哈希复核；删除必须走单独变更单并使用精确 bucket 名。对象版本、保留锁和生命周期规则要由 MinIO 管理策略独立备份，`mc mirror` 不能替代这些控制面配置。

## 4.2 PostgreSQL PITR 方案

生产 Compose 已启用 `wal_level=replica`、`archive_mode=on`，把完成的 WAL 写到独立 `postgres-wal-archive` 卷。下列命令会强制切换一个 WAL 并确认 `pg_stat_archiver` 成功计数增长，输出可追溯报告：

```bash
python tools/operations/postgres_pitr_readiness.py \
  --report test-results/restore-drill/pitr-readiness.json \
  --execute
```

这只证明 WAL 归档通道就绪，不等于完成 PITR。正式 PITR 还必须同时具备：定期 `pg_basebackup` 物理基线、连续异地 WAL 复制、基线与 WAL 的不可变清单、受控密钥和保留期。季度演练在隔离主机从最近物理基线启动新实例，设置 `restore_command` 和 `recovery_target_time`，创建 `recovery.signal`，禁止连接生产网络；实例达到目标时间并暂停后，核对会员、审计、附件元数据和目标时间前后哨兵记录，再提升为可读验证实例。报告记录目标时间、最后回放 WAL、实际 RPO/RTO、执行/复核人。缺少物理基线或异地 WAL 时，PITR 状态必须标记为“未具备”。

## 5. 部署步骤

1. 冻结变更窗口，确认负责人、回滚负责人和观察窗口。
2. 完成第 2 节预检和第 3 节部署前备份。
3. 拉取审批版本并记录 digest：`docker compose pull`。
4. 启动生产 Compose 中的 PostgreSQL 并等待健康；确认外部 MinIO 私有 bucket 和 Redis 已由基础设施团队启动，且可从后端 egress 网络访问。
5. 启动后端。Flyway 迁移失败时立即停止，不要用 `repair` 或手工改迁移历史绕过。
6. 启动前端和网关，依次验证健康接口、OIDC 登录、协会管理员、企业管理员、只读用户、附件上传下载、审计记录和限流降级。
7. 观察至少 30 分钟：5xx、P95 延迟、数据库连接、磁盘、JVM、MinIO 错误率、Redis 限流错误和身份认证失败率。
8. 证据完整且无高优先级告警后关闭变更窗口。

## 6. 回滚

应用回滚优先于数据恢复：

1. 停止继续发布，记录首个异常时间和影响范围。
2. 先执行第 2 节迁移兼容判断。V13–V23 不得视为可直接回滚的普通向前兼容变化；只能切换到兼容矩阵明确验证过同协会影响、跨协会约束、匹配闭环、通知/附件状态、会员出处、知识生命周期、评测记录和 V23 ETag 版本的镜像，才可保持数据库不变并使用上一审批镜像 digest。
3. 若旧应用无法读取新结构，优先发布兼容修复；不要删除 Flyway 迁移文件，不要手工倒改 `flyway_schema_history`。
4. 只有数据损坏或误删且业务负责人批准时才进入生产数据恢复流程。当前仓库工具故意不提供生产恢复命令，生产恢复必须由 DBA 根据已验证备份编写一次性方案、双人复核并在隔离副本上先演练。
5. 回滚后重复登录、权限、附件、审计和核心业务冒烟测试，并保留事故时间线。

特别说明：执行过 V13–V23 的数据库禁止直接启动 V12 或更早应用镜像，也禁止启动仍可能写入跨协会政策影响、非法匹配状态、未支持通知状态，或把 `REQUIRES_REUPLOAD` 附件当作可用内容的未验证镜像。旧应用还不了解 V21 的出处/知识生命周期、V22 的评测闸门和 V23 的跨协会版本列；直接回滚会产生写入失败、误用未校验附件、绕过知识审核或错误宣传能力。此时只允许切换到已经声明兼容 V13–V23 的镜像，或采用第 3 步的向前修复。

发布记录必须包含：变更单号、Git 提交、全部镜像 digest、迁移前后 Flyway 版本、PostgreSQL/MinIO 备份清单、配置校验结果、浏览器 E2E 报告、观察窗口和回滚负责人。网关验收逐项确认 DNS 指向、证书 SAN 与域名一致、证书剩余有效期、80→443 的 308 跳转、TLS 1.2/1.3、HSTS，以及 `/actuator/` 不可经公网访问。应用回滚使用记录的上一审批 digest，不使用浮动标签；回滚后重新执行同一健康、OIDC 和核心业务冒烟清单。

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
`OBSERVABILITY_READ` 权限的 OIDC 服务账号（或系统管理员）才能读取。`/api/v1/health` 聚合 PostgreSQL、MinIO、Redis 和 OIDC/JWK 的真实探针，任一失败返回 503；私网还可读取 `readiness` 健康组。生产日志采用 Spring Boot 的 Logstash JSON 控制台格式，
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

真实 Webhook 接收器应把原始事件写入受控 NDJSON 证据文件，然后执行（默认等待时间与生产告警分组间隔匹配）：

```bash
python tools/operations/alert_delivery_drill.py \
  --alertmanager-url http://127.0.0.1:9093 \
  --receiver-evidence /secure/alert-receiver/events.ndjson \
  --report test-results/alert-delivery/report.json \
  --execute
```

工具只有同时在真实接收器证据中找到同一 `drill_id` 的 `firing` 与 `resolved` 才输出 verified。只看到 Alertmanager 接受 API 请求不能算告警送达。

生产 Compose 已接入 PostgreSQL exporter、Redis exporter 和 MinIO 原生受保护 exporter，并对三者配置不可抓取告警。PostgreSQL 密码、Redis 密码映射和 MinIO bearer token 均从只读 secret 文件加载；MinIO HTTPS 目标由渲染脚本生成 file_sd 文件。Prometheus Targets 页面必须三者均为 UP，且受控停止测试能触发对应告警后，才可把监控项标记为已验收。

常用 Loki 查询：按请求号 `{service="server"} |= "<requestId>"`；仅错误 `{service="server"} | json | level="ERROR"`。发布窗口必须在 Grafana 时间选择器中设置精确 UTC 起止时间。查询结果需与 `audit_log.request_id` 交叉核对并导出到变更单，禁止只截一张没有时间范围和查询条件的图片。

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

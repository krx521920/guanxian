# 管线智联后端

北京地下管线协会管理协作平台的 Java 21 / Spring Boot 3 模块化单体后端。会员档案默认持久化到 PostgreSQL，数据库结构由 Flyway 管理；生产认证默认使用 OIDC/JWT。

## 模块划分

| 模块 | 职责 |
| --- | --- |
| `bootstrap` | 应用启动、健康检查、协会/企业工作台聚合 |
| `shared-kernel` | 统一响应、业务异常、参数校验错误结构、请求追踪过滤器 |
| `iam` | OIDC/JWT 资源服务器、账号绑定、协会/企业数据域、RBAC 与安全错误响应 |
| `member` | 会员档案、Excel 采集、审核、审计、可见范围及生态查询接口 |
| `policy` | 政策标准持久化、审核发布、协会数据域、影响分析入口与订阅关联 |
| `ecosystem` | 产品/服务与需求目录、持久化匹配、双方确认、邀请、洽谈、反馈和成果归档 |
| `collaboration` | 协作事项受理、指派、状态流转、关闭、恢复、历史与审计 |
| `ai-adapter` | 文档解析、分段、Embedding 适配、引用检索、模型供应商门禁与降级 |

依赖方向保持为 `bootstrap -> 业务模块 -> shared-kernel`；`ecosystem` 只通过 `member.api.MemberDirectory` 读取企业资料，通过 `AiTextService` 使用 AI 能力，避免直接访问其他模块的内部存储。

## 本地启动

环境要求：JDK 21、Maven 3.9+、PostgreSQL 16，以及能够签发 JWT 的 OIDC 身份提供方。复制根目录 `.env.example` 后，至少替换数据库密码、Issuer 和 JWK Set 地址；默认运行模式不会创建演示账号。

```bash
cd apps/server
mvn clean verify
mvn -pl bootstrap -am spring-boot:run
```

应用启动时由 Flyway 按顺序执行 V1–V17，覆盖基础结构、会员档案、OIDC 数据域、审计、批量导入、业务生命周期、跨协会授权、附件/通知、匹配状态机、会员软删除、知识向量持久化、身份生命周期约束、政策协会归属约束及分协会通知订阅唯一键。V16 为政策影响分析补充协会列、同协会复合外键和写入触发器，禁止把一个协会的政策关联到另一协会企业；V17 为跨协会授权资源、同意状态、唯一有效授权、推荐资源归属与审批生命周期、关系完整生命周期、共享策略生命周期及各资源类型可见字段白名单增加数据库约束和参与方索引。已有非空数据库若没有 `flyway_schema_history`，会先以版本 0 建立基线，再执行后续迁移并保留已有企业记录。旧的 `docker-entrypoint-initdb.d` 初始化入口已删除，后续结构变化必须新增迁移版本，禁止直接修改已发布迁移。

V16、V17 会在 DDL 生效前检查历史数据。政策与企业协会不一致，或存在空授权资源、非法授权状态/撤销时间、重复 `ACTIVE` 授权、无业务资源/跨域错绑/审核字段矛盾的推荐、关系状态字段矛盾、非参与方暂停、非法共享策略时间/状态/版本/资源类型/可见字段时，迁移会以可操作的 `DETAIL`/`HINT` 失败并整体回滚；不得用 `flyway repair`、修改迁移历史或无审核批量更新来绕过。V17 不会在迁移时静默改写单个已过期但仍标记 `ACTIVE` 的旧授权；同一资源首次重新授权时，触发器会在同一事务中将旧记录物化为 `EXPIRED`，再由部分唯一索引串行化并发授权。

生产默认 `GUANXIAN_SECURITY_MODE=jwt`。必须配置：

- `GUANXIAN_JWT_ISSUER_URI`：令牌 `iss` 的精确值。
- `GUANXIAN_JWT_JWK_SET_URI`：身份提供方公开密钥地址。
- `GUANXIAN_JWT_PRINCIPAL_CLAIM`：默认 `preferred_username`。
- `GUANXIAN_JWT_BOOTSTRAP_SYSTEM_ADMIN_SUBJECTS`：可选、逗号分隔的精确 IdP `sub` 白名单；默认空并拒绝所有未绑定系统管理员，仅用于首次建号。
- JWT 的 `roles`、`realm_access.roles` 或 `permissions` 声明；后端只接受平台白名单角色和权限。

纯单元测试可显式使用 `GUANXIAN_SECURITY_MODE=demo` 和 `GUANXIAN_MEMBER_REPOSITORY=memory`；PostgreSQL 集成测试使用 demo 身份配合真实 PostgreSQL Testcontainers。演示模式在 `prod` / `production` Profile 下会拒绝启动；默认配置和 Compose 均使用 PostgreSQL + JWT。

默认监听 `http://localhost:8080`，公开健康检查：

```bash
curl http://localhost:8080/api/v1/health
```

## 首批接口

| 方法 | 地址 | 权限 |
| --- | --- | --- |
| GET | `/api/v1/health` | 公开 |
| GET | `/actuator/prometheus` | `OBSERVABILITY_READ`（仅采集服务账号/系统管理员） |
| GET | `/api/v1/users/me` | 已登录 |
| GET/POST | `/api/v1/members` | `MEMBER_READ` / 协会工作人员 |
| GET/PUT/DELETE | `/api/v1/members/{id}` | 数据域内读取 / 受限写入 / 协会管理员 |
| PUT | `/api/v1/members/{id}/review` | `MEMBER_REVIEW`，必须携带 `If-Match` |
| GET | `/api/v1/members/import-template` | `MEMBER_IMPORT` |
| POST | `/api/v1/members/imports/preview` | `MEMBER_IMPORT`，XLSX 预检 |
| GET/POST | `/api/v1/members/imports/{batchId}` / `.../commit` | 查看预检 / 提交合法行 |
| GET | `/api/v1/audit-logs` | `AUDIT_READ`，按协会数据域过滤 |
| GET/POST | `/api/v1/access-bindings` | JWT 模式系统管理员查询/绑定 OIDC 身份；修改既有账号必须携带 `If-Match` |
| PUT | `/api/v1/access-bindings/{id}/disable` / `.../restore` | 显式停用/恢复账号绑定，必须携带 `If-Match` |
| DELETE | `/api/v1/access-bindings/{id}` | 解绑 OIDC `sub` 并冻结账号，必须携带 `If-Match` |
| 同上 | `/api/v1/enterprises/**` | 企业 CRUD 兼容别名 |
| GET | `/api/v1/policies` | `POLICY_READ` |
| GET/POST | `/api/v1/matches` | `MATCH_REQUEST` |
| 同上 | `/api/v1/ecosystem/matches` | 生态匹配领域别名 |
| GET | `/api/v1/collaborations` | `COLLABORATION_READ` |
| GET | `/api/v1/dashboards/association` | `DASHBOARD_ASSOCIATION_READ` |
| GET | `/api/v1/dashboards/enterprise` | `DASHBOARD_ENTERPRISE_READ` |

匹配请求示例：

```bash
curl -u enterprise-admin:enterprise123 \
  -H "Content-Type: application/json" \
  -d '{"demandCompany":"示例企业","demandTitle":"燃气阀门合作","scene":"燃气管网","requirements":"泄漏监测与阀门制造","limit":5}' \
  http://localhost:8080/api/v1/matches
```

## 响应约定

成功与失败都使用同一外层结构：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "timestamp": "2026-08-14T08:00:00Z"
}
```

错误时 `code` 为稳定字符串，例如 `VALIDATION_FAILED`、`MALFORMED_REQUEST`、`INVALID_PARAMETER`、`UNSUPPORTED_MEDIA_TYPE`、`METHOD_NOT_ALLOWED`、`RESOURCE_NOT_FOUND`、`AUTHENTICATION_REQUIRED`、`ACCESS_DENIED`。MVC 参数转换、请求体解析、媒体类型、请求方法和未知路由也使用相同外层结构。前端已兼容字符串或数字类型的错误码。

所有响应（包括 `401`、`403` 和 MVC 异常）都携带 `X-Request-Id`。入站值只有完全符合 `[A-Za-z0-9._:-]{1,128}` 时才会透传，否则生成 UUID。处理期间该值写入 SLF4J MDC 的 `requestId`，请求结束后恢复原有上下文或清理，避免线程复用串号。该标识只用于日志关联，不能替代认证、幂等键或业务流水号。

生产 Profile 会将控制台日志输出为 Logstash JSON，每行带应用名和 MDC 中的 `requestId`；请由部署环境的日志代理转送到集中日志系统。Micrometer Prometheus 指标已启用 HTTP 请求直方图，`/actuator/prometheus` 必须使用拥有 `OBSERVABILITY_READ` 的短期 OIDC 服务令牌读取，不能把它暴露给公网或匿名抓取器。

会员企业状态只接受 `ACTIVE`、`PENDING_REVIEW`、`INCOMPLETE`、`DISABLED`（忽略大小写及首尾空白）。协会管理员或已选定协会上下文的系统管理员手工新增时空值按 `ACTIVE` 处理；请求体中的归属不能覆盖系统管理员当前上下文。协会运营人员新增、企业自助修改和 Excel 导入统一进入 `PENDING_REVIEW`，由协会管理员审核。统一社会信用代码入库前会去除首尾空白并转为大写，唯一性判断使用规范化后的值。

会员应用服务通过模块内部 `MemberRepository` 端口访问数据。默认 `PostgresMemberRepository` 使用 JDBC 映射 JSONB 列，并由数据库唯一索引和版本条件语句保证跨实例一致性；`InMemoryMemberRepository` 只在测试配置中创建。

单个会员的 `GET`、`POST` 和 `PUT` 响应返回强 `ETag`，其值对应响应体中的非负 `version`，例如 `ETag: "3"`。`PUT` 和 `DELETE` 必须携带上次读取到的单个强标签 `If-Match: "3"`：缺失返回 `428 PRECONDITION_REQUIRED`，弱标签、通配符、多值或非法格式返回 `400 INVALID_IF_MATCH`，版本过期返回 `412 PRECONDITION_FAILED`。创建版本为 `0`，每次成功更新递增 1；PostgreSQL 更新和删除均以版本作为原子条件。

`capabilities`、`products` 和 `cooperationNeeds` 每个列表最多 50 项，元素仍执行各自长度限制，避免单个企业档案形成无界集合。
### 身份绑定、数据范围与审计

JWT 模式以令牌 `sub` 精确绑定 `user_account.external_subject`。账号必须处于 `ACTIVE`，所属协会也必须处于 `ACTIVE`；企业账号在企业为 `DISABLED`、`DELETED` 或已有删除时间时立即失去数据范围。`DRAFT`、`INCOMPLETE`、`PENDING_REVIEW` 企业仍可登录补充并提交资料。协会身份必须绑定 `association_id`，企业身份还必须绑定 `enterprise_id`；缺失时分别返回 `IDENTITY_NOT_BOUND` 或 `IDENTITY_SCOPE_INCOMPLETE`，组织被冻结时返回 `IDENTITY_SCOPE_INACTIVE`。系统管理员也必须绑定一个 `ACTIVE` 账号；唯一例外是 `GUANXIAN_JWT_BOOTSTRAP_SYSTEM_ADMIN_SUBJECTS` 中精确列出的未绑定 `sub`，用于首次建立账号绑定。空配置或非名单主体一律返回 `IDENTITY_NOT_BOUND`，已停用或已撤销主体即使仍在白名单也不能进入。

账号状态与企业状态相互独立：恢复企业不会自动恢复曾被人工停用的账号。账号停用、恢复和解绑均使用强 ETag 乐观锁并写入审计；解绑会清除 `external_subject` 并将账号置为 `INACTIVE`，因此旧令牌中的 `sub` 无法继续解析。重新绑定一个已停用/已解绑账号不会隐式激活，必须由系统管理员再次显式恢复。

系统管理员通过 `X-Guanxian-Association-Id` 和可选的 `X-Guanxian-Enterprise-Id` 进入代管上下文。未选择协会时只允许全平台读取，所有业务写入均拒绝；选择协会后读写范围收窄到该协会；继续选择企业后，企业级资源与匹配参与数据进一步收窄到该企业。路径、查询参数和请求体中的协会或企业标识只能与请求头上下文一致，不能临时扩权。

企业管理员只能修改其绑定企业；协会工作人员只能操作本协会数据，且只有协会管理员可以审核或删除。可见范围为 `PRIVATE`、`ASSOCIATION`、`PARTNERS`、`MEMBERS`、`PUBLIC`：友好协会访问由 `association_relationship` 的启用关系控制。创建、修改、审核、删除、导入预检/提交和账号绑定均写审计日志，并携带操作主体、协会、企业和 `X-Request-Id`。

### 106 家会员资料采集

调查模板为 XLSX，单文件最大 5 MiB、最多 500 条有效数据行，拒绝公式单元格和被修改的表头。上传先生成持久化预检批次，逐行返回校验与重复错误；提交只导入标记为 `VALID` 的行，所有新企业进入待审核。批次提交使用事务和行锁/CAS 防止重复提交，导入后仍需协会管理员审核，不会自动认证。

## 自动化测试与变异测试

常规测试覆盖公开健康检查、合法账号鉴权、匿名访问和错误密码 `401`、角色越权 `403`、参数校验 `400`、超长及畸形载荷、未知资源 `404`、重复信用代码 `409`，以及企业创建、查询、筛选、更新、删除的权限边界。ArchUnit 同时约束业务模块不得反向依赖 `bootstrap`，并保护 `shared-kernel` 与 `member.api` 的依赖方向。运行完整的日常构建：

```bash
cd apps/server
mvn clean verify
```

日常 `verify` **不会**执行耗时的变异测试。需要检查“测试是否真的能发现代码逻辑被改变”时，显式启用 `mutation` profile：

```bash
cd apps/server
mvn -Pmutation -pl bootstrap -am clean verify
```

该 profile 使用 PIT 及 JUnit 5 插件。测试位于 `bootstrap`，`crossModule` 会将变异范围扩展到它依赖的业务模块；报告固定输出到：

- HTML：`bootstrap/target/pit-reports/index.html`
- XML：`bootstrap/target/pit-reports/mutations.xml`

快速检查 PIT 配置、覆盖关系与可生成的变异点，而不逐个执行变异体：

```bash
mvn -Pmutation '-Dpit.dryRun=true' -pl bootstrap -am clean verify
```

报告中 `KILLED` 表示现有测试发现了变异，`SURVIVED` 表示需要补充断言或测试场景，`NO_COVERAGE` 表示测试尚未执行到该代码。首次引入阶段不设置分数门槛，待基线稳定后再通过 `mutationThreshold` 和 `coverageThreshold` 逐步设为 CI 门禁。

2026-08-30 当前工作区普通全量回归基线为 62 个 Surefire 测试套件、279 项测试，失败、错误和跳过均为 0。真实 PostgreSQL 16 Testcontainers 已覆盖 Flyway V1–V17 空库迁移、V15→V17 政策影响升级、V16→V17 跨协会完整性升级、脏数据失败回滚及同一资源并发授权唯一性。PIT 分数只能引用与当前提交对应的 `bootstrap/target/pit-reports/` 产物，历史分数不得冒充当前结果；这些数据库测试也不能替代真实 MinIO、Redis、正式 OIDC 或完整浏览器 E2E。

## Docker

```bash
cd apps/server
docker build -t guanxian-server:dev .
docker run --rm -p 8080:8080 guanxian-server:dev
```

## Phase 2 上线闸门

1. 将系统管理员账号绑定、停用、恢复、解绑和代管上下文接入管理页面，并落实高权限操作双人复核。
2. 使用正式 IdP 完成真实浏览器 OIDC/PKCE 登录与各身份权限验收，关闭全部演示身份。
3. 在生产数据隔离副本完成 V12→V17 升级、V16/V17 脏数据处置、备份恢复和回滚兼容演练。
4. 为会员附件补齐病毒扫描、数据质量评分和导入批次撤销策略。
5. 配置合规的真实模型与 Embedding 供应商，使用协会真实语料完成出处、效果、费用和数据出境验收；通过前继续使用“管理协作平台”名称。

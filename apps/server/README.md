# 管线智联后端

北京地下管线协会 AI 管理协作平台的 Java 21 / Spring Boot 3 模块化单体后端。会员档案默认持久化到 PostgreSQL，数据库结构由 Flyway 管理；生产认证默认使用 OIDC/JWT。

## 模块划分

| 模块 | 职责 |
| --- | --- |
| `bootstrap` | 应用启动、健康检查、协会/企业工作台聚合 |
| `shared-kernel` | 统一响应、业务异常、参数校验错误结构、请求追踪过滤器 |
| `iam` | OIDC/JWT 资源服务器、账号绑定、协会/企业数据域、RBAC 与安全错误响应 |
| `member` | 会员档案、Excel 采集、审核、审计、可见范围及生态查询接口 |
| `policy` | 政策标准列表与检索 |
| `ecosystem` | 生态匹配列表、匹配请求、可解释规则评分 |
| `collaboration` | 协作事项列表，后续承接受理、跟进、反馈闭环 |
| `ai-adapter` | AI 能力端口与本地规则实现，后续可替换为独立 Python AI 服务 |

依赖方向保持为 `bootstrap -> 业务模块 -> shared-kernel`；`ecosystem` 只通过 `member.api.MemberDirectory` 读取企业资料，通过 `AiTextService` 使用 AI 能力，避免直接访问其他模块的内部存储。

## 本地启动

环境要求：JDK 21、Maven 3.9+、PostgreSQL 16，以及能够签发 JWT 的 OIDC 身份提供方。复制根目录 `.env.example` 后，至少替换数据库密码、Issuer 和 JWK Set 地址；默认运行模式不会创建演示账号。

```bash
cd apps/server
mvn clean verify
mvn -pl bootstrap -am spring-boot:run
```

应用启动时由 Flyway 按顺序执行 V1–V3：基础结构、会员档案扩展、OIDC 数据域/审计/批量导入。已有非空数据库若没有 `flyway_schema_history`，会先以版本 0 建立基线，再执行后续迁移；迁移使用幂等 DDL，并保留已有企业记录。旧的 `docker-entrypoint-initdb.d` 初始化入口已删除，后续结构变化必须新增迁移版本，禁止直接修改已发布迁移。

生产默认 `GUANXIAN_SECURITY_MODE=jwt`。必须配置：

- `GUANXIAN_JWT_ISSUER_URI`：令牌 `iss` 的精确值。
- `GUANXIAN_JWT_JWK_SET_URI`：身份提供方公开密钥地址。
- `GUANXIAN_JWT_PRINCIPAL_CLAIM`：默认 `preferred_username`。
- JWT 的 `roles`、`realm_access.roles` 或 `permissions` 声明；后端只接受平台白名单角色和权限。

开发或自动化测试必须显式设置 `GUANXIAN_SECURITY_MODE=demo` 和 `GUANXIAN_MEMBER_REPOSITORY=memory`。演示模式在 `prod` / `production` Profile 下会拒绝启动；默认配置和 Compose 均使用 PostgreSQL + JWT。

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
| GET/POST | `/api/v1/access-bindings` | JWT 模式系统管理员绑定 OIDC 身份 |
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

会员企业状态只接受 `ACTIVE`、`PENDING_REVIEW`、`INCOMPLETE`、`DISABLED`（忽略大小写及首尾空白）。协会管理员或系统管理员手工新增时空值按 `ACTIVE` 处理；协会运营人员新增、企业自助修改和 Excel 导入统一进入 `PENDING_REVIEW`，由协会管理员审核。统一社会信用代码入库前会去除首尾空白并转为大写，唯一性判断使用规范化后的值。

会员应用服务通过模块内部 `MemberRepository` 端口访问数据。默认 `PostgresMemberRepository` 使用 JDBC 映射 JSONB 列，并由数据库唯一索引和版本条件语句保证跨实例一致性；`InMemoryMemberRepository` 只在测试配置中创建。

单个会员的 `GET`、`POST` 和 `PUT` 响应返回强 `ETag`，其值对应响应体中的非负 `version`，例如 `ETag: "3"`。`PUT` 和 `DELETE` 必须携带上次读取到的单个强标签 `If-Match: "3"`：缺失返回 `428 PRECONDITION_REQUIRED`，弱标签、通配符、多值或非法格式返回 `400 INVALID_IF_MATCH`，版本过期返回 `412 PRECONDITION_FAILED`。创建版本为 `0`，每次成功更新递增 1；PostgreSQL 更新和删除均以版本作为原子条件。

`capabilities`、`products` 和 `cooperationNeeds` 每个列表最多 50 项，元素仍执行各自长度限制，避免单个企业档案形成无界集合。
### 身份绑定、数据范围与审计

JWT 模式以令牌 `sub` 精确绑定 `user_account.external_subject`。协会身份必须绑定 `association_id`，企业身份还必须绑定 `enterprise_id`；缺失时分别返回 `IDENTITY_NOT_BOUND` 或 `IDENTITY_SCOPE_INCOMPLETE`。系统管理员保留不绑定企业的引导身份，用于首次建立账号绑定；该身份应只由受控的 IdP 管理员角色签发。

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

当前基线（2026-08-21）：常规 `verify` 发现 84 项测试，83 项通过，1 项 PostgreSQL/Testcontainers 迁移测试因本机 Docker 不可用而明确跳过；其余失败和错误均为 0。完整 PIT 对 576 个变异体进行验证，杀死 379 个、存活 108 个、无覆盖 89 个，变异得分 65.80%，已覆盖代码的测试强度为 77.82%。权限策略的存活变异已清零；剩余低分主要集中在本机未执行的 PostgreSQL 适配器、XLSX 防御分支及部分基础展示代码。CI 必须提供 Docker 并把迁移测试作为门禁，PIT 报告以本次 `bootstrap/target/pit-reports/` 产物为准。

## Docker

```bash
cd apps/server
docker build -t guanxian-server:dev .
docker run --rm -p 8080:8080 guanxian-server:dev
```

## 下一阶段

1. 将系统管理员的账号绑定接口接入管理页面，并补充绑定停用、离职回收与双人复核。
2. 在实际 PostgreSQL/Docker 环境执行 V3 迁移演练、备份恢复和批量导入容量测试。
3. 为会员资料增加附件对象存储、病毒扫描、数据质量评分和导入批次撤销策略。
4. 将 `ai-adapter` 的规则实现替换为 AI 服务 HTTP 客户端，加入超时、重试、熔断与人工确认。
5. 将协作事项升级为需求受理、推荐确认、沟通跟进、结果反馈的状态机。

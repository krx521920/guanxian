# 管线智联后端

北京地下管线协会 AI 管理协作平台的 Java 21 / Spring Boot 3 模块化单体后端骨架。第一阶段采用内存数据实现可运行闭环，模块边界已为后续接入 PostgreSQL、对象存储和独立 AI 服务预留。

## 模块划分

| 模块 | 职责 |
| --- | --- |
| `bootstrap` | 应用启动、健康检查、协会/企业工作台聚合 |
| `shared-kernel` | 统一响应、业务异常、参数校验错误结构、请求追踪过滤器 |
| `iam` | 当前用户、演示账号、RBAC 与安全错误响应 |
| `member` | 会员企业档案、企业基础 CRUD、供生态模块使用的查询接口 |
| `policy` | 政策标准列表与检索 |
| `ecosystem` | 生态匹配列表、匹配请求、可解释规则评分 |
| `collaboration` | 协作事项列表，后续承接受理、跟进、反馈闭环 |
| `ai-adapter` | AI 能力端口与本地规则实现，后续可替换为独立 Python AI 服务 |

依赖方向保持为 `bootstrap -> 业务模块 -> shared-kernel`；`ecosystem` 只通过 `member.api.MemberDirectory` 读取企业资料，通过 `AiTextService` 使用 AI 能力，避免直接访问其他模块的内部存储。

## 本地启动

环境要求：JDK 21、Maven 3.9+。

```bash
cd apps/server
mvn clean test
mvn -pl bootstrap -am spring-boot:run
```

默认监听 `http://localhost:8080`，公开健康检查：

```bash
curl http://localhost:8080/api/v1/health
```

业务接口当前使用 HTTP Basic 演示鉴权：

| 账号 | 密码 | 角色与用途 |
| --- | --- | --- |
| `system-admin` | `system123` | `SYSTEM_ADMIN`，系统级演示管理员 |
| `association-admin` | `admin123` | `ASSOCIATION_ADMIN`，协会管理员 |
| `association-operator` | `operator123` | `ASSOCIATION_OPERATOR`，协会业务运营 |
| `enterprise-admin` | `enterprise123` | `ENTERPRISE_ADMIN`，企业管理员 |
| `enterprise-member` | `member123` | `ENTERPRISE_MEMBER`，企业普通成员（只读） |
| `observer` | `observer123` | 额外只读观察员，只能查看企业和政策 |

演示账号仅用于开发骨架，禁止直接用于生产。设置 `GUANXIAN_DEMO_USERS_ENABLED=false` 后，所有内置账号都会失效且认证保持默认拒绝。启用 `prod` 或 `production` Profile 时，如果没有显式关闭演示账号，应用将拒绝启动，避免硬编码密码被误带入生产。生产阶段应替换为 OIDC/JWT 或统一身份平台，并增加企业数据范围约束。

## 首批接口

| 方法 | 地址 | 权限 |
| --- | --- | --- |
| GET | `/api/v1/health` | 公开 |
| GET | `/api/v1/users/me` | 已登录 |
| GET/POST | `/api/v1/members` | `MEMBER_READ` / `ENTERPRISE_WRITE` |
| GET/PUT/DELETE | `/api/v1/members/{id}` | 读 / 写 / 协会管理员 |
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

会员企业状态只接受 `ACTIVE`、`PENDING_REVIEW`、`INCOMPLETE`、`DISABLED`（忽略大小写及首尾空白）；空值仍按 `ACTIVE` 处理。统一社会信用代码入库前会去除首尾空白并转为大写，唯一性判断使用规范化后的值。

会员应用服务通过模块内部 `MemberRepository` 端口访问数据，当前由 `InMemoryMemberRepository` 提供进程内适配。业务校验与写操作的单 JVM 串行语义保持不变；接入 PostgreSQL 时应新增持久化适配器，并由数据库唯一约束和事务保证跨实例一致性。

单个会员的 `GET`、`POST` 和 `PUT` 响应返回强 `ETag`，其值对应响应体中的非负 `version`，例如 `ETag: "3"`。`PUT` 和 `DELETE` 必须携带上次读取到的单个强标签 `If-Match: "3"`：缺失返回 `428 PRECONDITION_REQUIRED`，弱标签、通配符、多值或非法格式返回 `400 INVALID_IF_MATCH`，版本过期返回 `412 PRECONDITION_FAILED`。创建版本为 `0`，每次成功更新递增 1；服务层与仓储层都进行版本条件检查。未来数据库适配器必须用 `UPDATE/DELETE ... WHERE id=? AND version=?` 等原子 CAS 实现，不能先查后无条件写。

`capabilities`、`products` 和 `cooperationNeeds` 每个列表最多 50 项，元素仍执行各自长度限制，避免单个企业档案形成无界集合。

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

当前基线（2026-08-15）：65 项 JUnit/ArchUnit 测试全部通过；PIT 对 179 个变异体捕获 162 个，13 个存活、4 个无覆盖，变异分数 91%，测试强度 93%，变异代码行覆盖 370/387（96%）。

## Docker

```bash
cd apps/server
docker build -t guanxian-server:dev .
docker run --rm -p 8080:8080 guanxian-server:dev
```

## 下一阶段

1. 为现有会员仓储端口增加 PostgreSQL 适配器，并增加 Flyway 迁移、数据库唯一约束和事务。
2. 接入 Excel 批量导入、审核、数据质量评分和附件存储。
3. 将企业编辑权限限制到当前账号所属企业，增加数据可见范围与审计日志。
4. 将 `ai-adapter` 的规则实现替换为 AI 服务 HTTP 客户端，加入超时、重试、熔断与人工确认。
5. 将协作事项升级为需求受理、推荐确认、沟通跟进、结果反馈的状态机。

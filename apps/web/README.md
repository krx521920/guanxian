# 管线智联 Web 管理端

北京地下管线协会管理协作平台的 Vue 3 管理端。当前版本围绕第二版产品框架，提供协会与企业两类工作空间，并已接入基于账号身份的前端导航和路由权限。真实模型与语料评测完成前，界面不以“AI 平台”对外宣传。

## 已有页面

- OIDC Authorization Code + PKCE 统一身份登录；本地/测试可显式启用五类演示身份
- 协会工作台：会员概览、场景分布、动态和待推进协作
- 企业工作台：资料完整度、商机、政策提醒和协作概览
- 会员企业：按数据域检索、状态筛选、调查模板下载、XLSX 逐行预检/提交、新增、编辑、协会审核与 ETag 冲突保护
- 政策标准：分级筛选、影响摘要与政策列表
- 生态匹配：仅从当前身份可操作的开放需求生成，支持协会推荐、双方确认、定向邀请/应答、逐级洽谈、双方反馈、成果归档与带原因关闭
- 协作事项：协作闭环、进度、负责人和下一步行动

## 角色权限

| 角色 | 默认工作台 | 可见业务模块 |
| --- | --- | --- |
| `SYSTEM_ADMIN` | 协会工作台 | 协会工作台、会员企业、政策标准、生态匹配、协作事项 |
| `ASSOCIATION_ADMIN` | 协会工作台 | 协会工作台、会员企业、政策标准、生态匹配、协作事项 |
| `ASSOCIATION_OPERATOR` | 协会工作台 | 协会工作台、会员企业、政策标准、生态匹配、协作事项 |
| `ENTERPRISE_ADMIN` | 企业工作台 | 企业工作台、会员企业（仅本企业可编辑）、政策标准、生态匹配、协作事项 |
| `ENTERPRISE_MEMBER` | 企业工作台 | 企业工作台、会员企业（按可见范围只读）、政策标准、生态匹配、协作事项 |

页面导航使用后端 `/api/v1/users/me` 返回的已验证角色；所有业务授权仍由后端 `@PreAuthorize` 最终裁决。前端角色判断只用于界面可见性，不能替代接口授权。

生产构建强制使用 OIDC。`oidc-client-ts` 将 Authorization Code + PKCE 状态和用户会话保存在 `sessionStorage`，访问令牌只通过内存桥接注入 API 请求；页面启动时必须用令牌回读后端当前用户接口。`VITE_AUTH_MODE=demo` 仅在非 production 构建中生效，生产即使误配也禁止身份切换。更高安全等级部署可进一步引入 BFF + HttpOnly Cookie，消除浏览器可读令牌。

## 本地启动

```bash
cd apps/web
npm install
copy .env.example .env.local
npm run dev
```

默认地址为 `http://localhost:5173`。生产/联调至少配置 `VITE_OIDC_AUTHORITY`、`VITE_OIDC_CLIENT_ID`、回调地址和退出回调地址；身份提供方必须登记完全一致的 URI。纯本地界面测试可显式设置 `VITE_AUTH_MODE=demo`，但不得用于生产构建。

如需让本地演示页面连接以 HTTP Basic 演示模式运行的后端，可在 `.env.local` 中设置
`DEV_API_PROXY_AUTH_SYSTEM_ADMIN`、`DEV_API_PROXY_AUTH_ASSOCIATION_ADMIN`、
`DEV_API_PROXY_AUTH_ASSOCIATION_OPERATOR`、`DEV_API_PROXY_AUTH_ENTERPRISE_ADMIN` 和
`DEV_API_PROXY_AUTH_ENTERPRISE_MEMBER`。前端切换演示身份时，Vite 开发代理会选择对应账号连接后端。
这些变量只由 Vite 开发服务器读取，不会随 `VITE_*` 客户端环境变量进入浏览器生产包；
正式环境不得配置这些演示认证项。

## 真实 API 与失败反馈

前端统一通过 `src/services/http.ts` 请求 `/api/v1`，支持常见的 `{ code, message, data }` 响应包装和直接返回数据两种形式。当前首批接口约定如下：

| 方法 | 地址 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/dashboards/association` | 协会工作台 |
| GET | `/api/v1/dashboards/enterprise` | 企业工作台 |
| GET/POST | `/api/v1/members` | 数据域内会员列表 / 协会工作人员新增 |
| GET | `/api/v1/members/{id}` | 会员详情并读取强 `ETag` |
| PUT | `/api/v1/members/{id}` | 携带 `If-Match` 更新；企业管理员只能更新绑定企业 |
| PUT | `/api/v1/members/{id}/review` | 协会管理员携带 `If-Match` 审核 |
| GET | `/api/v1/members/import-template` | 下载 XLSX 调查模板 |
| POST | `/api/v1/members/imports/preview` | 上传并逐行预检，不直接落会员数据 |
| POST | `/api/v1/members/imports/{batchId}/commit` | 只提交预检合法行，统一进入待审核 |
| GET | `/api/v1/policies` | 政策标准 |
| GET | `/api/v1/matches` | 生态匹配 |
| GET | `/api/v1/matches/generation-demands` | 分页读取当前身份真正有权生成匹配的开放需求 |
| POST | `/api/v1/matches/demand/{demandId}/generate` | 生成并持久化匹配记录；最长等待 60 秒 |
| POST | `/api/v1/matches/{id}/recommend`、`confirm`、`close` | 携带匹配版本 `If-Match` 推进或关闭 |
| POST | `/api/v1/matches/{id}/invitations` | 携带匹配版本发送邀请 |
| POST | `/api/v1/matches/invitations/{id}/respond` | 携带邀请版本应答；过期邀请不能操作 |
| POST | `/api/v1/matches/{id}/negotiations` | 携带匹配版本按顺序追加洽谈阶段 |
| POST | `/api/v1/matches/{id}/feedback` | 首次反馈不带版本，更新时携带反馈版本 `If-Match` |
| POST | `/api/v1/matches/{id}/outcomes` | 双方成功反馈后携带匹配版本归档成果 |
| GET | `/api/v1/collaborations` | 协作事项 |


每个请求拥有独立的超时控制器，支持调用方通过 `AbortSignal` 取消；并发请求之间互不影响。超时统一返回 `REQUEST_TIMEOUT`，且所有完成、失败和取消路径都会清理计时器与外部监听器。

所有 HTTP 请求都会携带 `X-Request-Id`。调用方提供的值只有完全符合 `[A-Za-z0-9._:-]{1,128}` 时才会保留，否则使用 Web Crypto 生成新值；优先使用 `crypto.randomUUID()`，不支持时回退到 `crypto.getRandomValues()`，不使用 `Math.random`。`ApiRequestError.requestId` 优先采用格式安全的服务端响应头，否则采用本次出站请求 ID，便于前后端日志关联。错误对象不保存响应正文或调试字段，向用户或日志输出时只应使用 `message`、`status`、`code` 和 `requestId`。

会员新增、编辑和审核页与批量采集按钮已连接真实 API。批量上传使用浏览器生成的 multipart boundary，不手工设置 `Content-Type`；模板以二进制 Blob 下载。导入预检会显示每行错误，确认后只提交合法行。

会员编辑页只接受形如 `"7"` 的强 ETag；接口缺少或返回弱 ETag 时拒绝进入可保存状态。保存成功后替换为响应的新 ETag，收到 412 时要求重新加载，不会静默覆盖其他用户的修改。

协会工作台、企业工作台、会员企业、政策标准、生态匹配和协作事项均提供明确的加载、失败与重试状态。匹配详情用请求序号隔离晚到结果，组件卸载后不再写入页面；超时写请求会先刷新数据库状态，避免把“响应超时”误报成“业务未保存”。匹配列表当前为前端分页，后端 `GET /matches` 仍返回当前身份可见的完整集合，不宣称数据库分页。

## 验证

```bash
npm run typecheck
npm run test
npm run build
```

当前自动化测试分为三类：

- 正向测试：验证标准 API 包装解包、直接 JSON 响应、安全请求 ID 传播、异步资源成功与重试恢复、五类账号登录及其默认工作台、各身份可见导航。
- 反向测试：验证企业账号不可见协会工作台与采集功能、企业业务人员不能编辑、企业管理员只能维护绑定企业、缺少角色声明时默认拒绝，且网络错误不会生成任何伪数据。
- 错误合同测试：验证 HTTP 4xx/5xx、业务码失败、无效响应、请求 ID、页面错误映射、重试去重、卸载后晚到结果、超时与取消竞态、并发请求隔离和失效会话清理。

主要测试文件：

| 文件 | 覆盖范围 |
| --- | --- |
| `src/composables/useAsyncResource.test.ts` | 成功、API/普通异常安全映射、请求编号、重试、并发去重和卸载保护 |
| `src/services/http.test.ts` | HTTP envelope、业务错误码、请求 ID、正文隔离、超时/取消、并发隔离与网络错误传递 |
| `src/services/auth.test.ts` | 演示模式登录、身份切换、退出、角色白名单、旧会话清理和存储异常 |
| `src/services/auth-oidc.test.ts` | OIDC 配置、回调校验、后端身份映射、令牌过期、开放重定向防护和退出 |
| `src/services/platform-api.test.ts` | 强 ETag/If-Match、所有会员采集端点的精确路径与方法、multipart 边界、模板下载、批次提交、412 冲突和网络失败传递 |
| `src/services/workflow-api.test.ts` | 可生成需求、匹配邀请、应答、洽谈、反馈 ETag、成果和协作接口合同 |
| `src/config/navigation.test.ts` | 五类身份的默认工作台、正向授权与反向隔离 |
| `src/router/permissions.test.ts` | 受保护页面显式角色声明与默认拒绝基础 |
| `src/views/policy-display.test.ts` | 政策施行日期为空时的安全展示 |
| `src/views/business-form.test.ts` | 业务表单中文状态、日期、超时与错误消息转换 |
| `src/views/interaction-contract.test.ts` | 原生按钮行为、生产 API 去模拟数据和匹配全流程入口 |
| `src/views/match-workflow.test.ts` | 服务端动作许可、洽谈阶段顺序、邀请过期与刷新后安全关闭详情 |

### 真实浏览器 E2E

Playwright 连接隔离的 PostgreSQL、MinIO、Redis 和 Keycloak E2E 栈，执行真实 OIDC 登录、身份页面权限、两家企业与协会的匹配业务闭环、附件上传下载/删除恢复及通知已读验收：

```bash
npm run test:e2e
```

首次运行需要安装 Chromium。完整前置条件、测试账号和数据隔离要求见 `tests/e2e/README.md`。如覆盖默认 Web 基址，必须同步修改 Web 构建中的 OIDC 回调地址和 Keycloak 客户端登记地址；只改 Playwright 基址会导致登录回调失败。测试固定单 worker、零重试。

### 变异测试


`npm run mutation` 使用 StrykerJS + Vitest 对以下高风险逻辑注入条件反转、返回值替换、字符串变化等变异，再检查测试是否能捕获：

- `src/composables/useAsyncResource.ts`
- `src/services/http.ts`
- `src/services/auth.ts`
- `src/services/platform-api.ts`
- `src/config/navigation.ts`

终端会输出总变异得分；详细 HTML 和 JSON 报告分别生成于：

- `reports/mutation/mutation.html`
- `reports/mutation/mutation.json`

质量阈值为：80 分以上绿色、60–79.99 分黄色、低于 60 分红色，低于 50 分时命令失败。变异测试比普通单元测试耗时明显更长，适合在提交合并前或 CI 的独立质量任务中运行；日常开发优先运行 `npm run test`。

当前常规基线（2026-08-31）：20 个 Vitest 文件、208 项测试全部通过，`npm run typecheck` 与 `npm run build` 通过。本次本机 Docker daemon 不可用，当前版本的真实依赖 Playwright 旅程未执行；早期 3 个文件/5 条旅程的结果只是历史基线，不是当前变更的验收证据。最近一次 Stryker 报告生成于 2026-08-26，共 937 个变异体，其中 664 个被杀死、79 个存活、194 个无覆盖；该历史报告不属于本轮验收，只能将与当前提交对应的报告作为质量依据。正式 IdP 与当前版本浏览器 E2E 未验收前，不得将前端判定为生产闭环。

如只想排查一个测试文件，可执行：

```bash
npx vitest run src/services/http.test.ts
```


## Docker 构建与运行

生产镜像采用多阶段构建：Node.js 阶段执行类型检查和 Vite 构建，最终仅保留 Nginx 与静态产物。

```bash
cd apps/web
docker build -t guanxian-web:latest .
docker run --rm -p 8081:80 --name guanxian-web guanxian-web:latest
```

访问 `http://localhost:8081`。Nginx 已配置 Vue Router history fallback，直接访问 `/members`、`/policies` 等前端路由不会返回 404。

容器内的 `/api/` 请求会反向代理到 `http://server:8080`。使用 Docker Compose 时，应将后端服务命名为 `server`，并让前后端容器加入同一网络。例如：

```yaml
services:
  web:
    build: ./apps/web
    ports:
      - "8081:80"
    depends_on:
      - server
  server:
    # 后端镜像或 build 配置
    expose:
      - "8080"
```

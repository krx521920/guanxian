# 管线智联 Web 管理端

北京地下管线协会 AI 管理协作平台的 Vue 3 管理端骨架。当前版本围绕第二版产品框架，提供协会与企业两类工作空间，并已接入基于账号身份的前端导航和路由权限。

## 已有页面

- OIDC Authorization Code + PKCE 统一身份登录；本地/测试可显式启用五类演示身份
- 协会工作台：会员概览、场景分布、动态和待推进协作
- 企业工作台：资料完整度、商机、政策提醒和协作概览
- 会员企业：按数据域检索、状态筛选、调查模板下载、XLSX 逐行预检/提交、新增、编辑、协会审核与 ETag 冲突保护
- 政策标准：分级筛选、影响摘要与政策列表
- 生态匹配：匹配依据、推荐理由、匹配状态与供需双方
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

## API 与 mock 回退

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
| GET | `/api/v1/collaborations` | 协作事项 |

开发环境显式设置 `VITE_MOCK_FALLBACK=true` 后，只有 `fetch` 抛出的网络类 `TypeError` 才会回退到 `src/mocks/data.ts`。HTTP 4xx/5xx、业务错误码、响应契约错误、超时和用户主动取消均不会触发 mock。生产构建无条件关闭 mock 回退，即使误配了该环境变量也不会隐藏真实接口故障。

每个请求拥有独立的超时控制器，支持调用方通过 `AbortSignal` 取消；并发请求之间互不影响。超时统一返回 `REQUEST_TIMEOUT`，且所有完成、失败和取消路径都会清理计时器与外部监听器。

所有 HTTP 请求都会携带 `X-Request-Id`。调用方提供的值只有完全符合 `[A-Za-z0-9._:-]{1,128}` 时才会保留，否则使用 Web Crypto 生成新值；优先使用 `crypto.randomUUID()`，不支持时回退到 `crypto.getRandomValues()`，不使用 `Math.random`。`ApiRequestError.requestId` 优先采用格式安全的服务端响应头，否则采用本次出站请求 ID，便于前后端日志关联。错误对象不保存响应正文或调试字段，向用户或日志输出时只应使用 `message`、`status`、`code` 和 `requestId`。

会员新增、编辑和审核页与批量采集按钮已连接真实 API。批量上传使用浏览器生成的 multipart boundary，不手工设置 `Content-Type`；模板以二进制 Blob 下载。导入预检会显示每行错误，确认后只提交合法行。

会员编辑页只接受形如 `"7"` 的强 ETag；接口缺少或返回弱 ETag 时拒绝进入可保存状态。保存成功后替换为响应的新 ETag，收到 412 时要求重新加载，不会静默覆盖其他用户的修改。

协会工作台、企业工作台、会员企业、政策标准、生态匹配和协作事项统一使用 `useAsyncResource` 管理异步页面状态。加载失败时不再永久停留在加载动画，也不会产生未处理 Promise：页面只显示固定的安全中文提示，`ApiRequestError` 额外显示请求编号，普通异常不展示原始消息、响应正文或堆栈。错误态提供“重新加载”；请求尚未结束时的重复点击会复用同一个 Promise，组件卸载后的晚到结果或异常不会再写入 Vue 状态。

## 验证

```bash
npm run typecheck
npm run test
npm run build
npm run mutation
```

当前自动化测试分为三类：

- 正向测试：验证标准 API 包装解包、直接 JSON 响应、安全请求 ID 传播、异步资源成功与重试恢复、五类账号登录及其默认工作台、各身份可见导航。
- 反向测试：验证企业账号不可见协会工作台与采集功能、企业业务人员不能编辑、企业管理员只能进入受限编辑路由、缺少角色声明时默认拒绝、生产环境及非网络错误不得回退到 mock。
- 异常测试：验证 HTTP 4xx/5xx、HTTP 200 但业务码失败、无效 JSON/错误码类型、缺少 `data`、不安全/重复请求 ID、页面安全错误映射、重试去重、卸载后晚到结果、超时与取消竞态、并发请求隔离、伪造会话和浏览器存储被禁用。

主要测试文件：

| 文件 | 覆盖范围 |
| --- | --- |
| `src/composables/useAsyncResource.test.ts` | 成功、API/普通异常安全映射、请求编号、重试、并发去重和卸载保护 |
| `src/services/http.test.ts` | HTTP envelope、业务错误码、请求 ID、正文隔离、超时/取消、并发隔离、开发/生产 mock 开关 |
| `src/services/auth.test.ts` | 演示模式登录、身份切换、退出、角色白名单、旧会话清理和存储异常 |
| `src/services/auth-oidc.test.ts` | OIDC 配置、回调校验、后端身份映射、令牌过期、开放重定向防护和退出 |
| `src/services/platform-api.test.ts` | 强 ETag/If-Match、所有会员采集端点的精确路径与方法、multipart 边界、模板下载、批次提交、412 冲突和 mock 隔离 |
| `src/config/navigation.test.ts` | 五类身份的默认工作台、正向授权与反向隔离 |
| `src/router/permissions.test.ts` | 受保护页面显式角色声明与默认拒绝基础 |
| `src/views/policy-display.test.ts` | 政策施行日期为空时的安全展示 |

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

当前基线（2026-08-21）：122 项 Vitest 测试全部通过；Stryker 对 618 个核心逻辑变异进行验证，杀死 592 个、存活 23 个、无覆盖 3 个，总变异得分 95.79%。其中 API 路径/ETag/导入合同层为 90.63%，HTTP 客户端为 99.55%，认证模块为 91.49%。报告以本次 `npm run mutation` 输出及本地 `reports/mutation/` 产物为准。

如只想排查一个测试文件，可执行：

```bash
npx vitest run src/services/http.test.ts
```

安全联调时建议设置 `VITE_MOCK_FALLBACK=false`。只有在纯前端开发演示环境才开启 mock 回退。

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

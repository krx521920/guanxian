# 个人模型接入

## 使用范围

左下角「模型接入」在「聊天」上方。每个已登录且具有 POLICY_READ 权限的账号可以保存一组当前生效的个人配置：厂商、模型 ID、API Key、启用状态。不是全平台模型配置，不提供管理员代看或代设他人密钥的接口。

支持豆包/火山方舟、DeepSeek、Kimi/月之暗面、千问/百炼北京地域的 OpenAI-compatible Chat Completions 文本接口。模型 ID 由用户从控制台填写，不把易变的模型版本硬编码为唯一选项。需要所选模型支持文本流式输出；只读业务工具还需要该模型支持 function calling。网页会员订阅不等于开发者 API 余额。本版不包含图片、语音、Coding Plan 专用接口、自定义中转站或任意 Base URL。

官方接口依据（2026-09-05 核对）：

- [火山方舟 OpenAI 兼容文档](https://www.volcengine.com/docs/82379/1330626)：`https://ark.cn-beijing.volces.com/api/v3/chat/completions`。
- [DeepSeek 首次调用](https://api-docs.deepseek.com/)：`https://api.deepseek.com/chat/completions`。
- [Kimi 快速开始](https://platform.kimi.com/docs/get-api-key)：`https://api.moonshot.cn/v1/chat/completions`。
- [百炼 Base URL 说明](https://help.aliyun.com/en/model-studio/base-url)：北京共享域名仍可用，`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；必须搭配同地域按量付费 API Key。专属业务空间域名和其他地域不在本版范围。

## 操作

1. 选择厂商、填写模型 ID 和该厂商 API Key。
2. 阅读并勾选外发和计费说明，保存配置。留空 API Key 仅能保留同厂商原密钥；切换厂商必须重新填写。
3. 点击「测试连接」。测试使用已保存配置，只发送固定测试文本，不带业务数据或历史记录；可能产生少量 API 费用。每个账号至少间隔 30 秒。
4. 开启个人模型后，下一次聊天优先使用它。关闭或删除则使用原平台默认模式。

连接测试成功只代表收到第一段流式文本，不证明真实业务工具正确、模型效果良好或高并发性能达标。真实业务问答需另外验收。个人模型调用失败不会悄悄切换到另一家厂商。

## 数据与密钥保护

- 所有设置端点从认证后的 ActorScope.userId 确认归属，不接受客户端指定的 owner、subject 或目标 URL。
- API Key 用独立的 AES-256-GCM 主密钥加密；随机 nonce，认证附加数据绑定 userId 和 provider。数据库只保存密文。GET/PUT 只回传 hasKey，不回传密钥或密文；响应 `Cache-Control: no-store`。
- 浏览器密码框仅临时持有输入，保存、关闭、卸载、换账号时清空。不写 localStorage/sessionStorage，不通过 URL 传递密钥。
- 服务端仅允许四个预设 HTTPS 目的地，不跟随重定向；厂商错误响应体在模型日志处理前丢弃。不要开启 HTTP wire/body/header 调试日志或请求体审计，也不要在代理层记录 Authorization。
- 密钥仅在请求内解密使用，不建跨账号明文缓存。每次修改产生新 revision，隔离更换模型后的会话记忆；记忆同时绑定用户、协会、企业与权限快照。
- 个人配置不能绕过全局 `guanxian.ai.rag.external-model-data-egress-enabled`。全局关闭时继续本地模式，连接测试被拒绝。
- 用户同意启用后，提问、会话历史、可见知识片段和只读工具摘要会发送给所选厂商。原有工具权限与协会隔离仍生效；组织应事先批准外发范围。
- 当前数据库记录删除不删除历史备份中的密文；备份按既有保留策略处置。主密钥应与数据库备份分开保护；主密钥遗失会导致原密文不可解密。轮换需重新加密迁移或要求用户重新保存，不能直接覆盖主密钥后宣称旧配置可用。

## 部署前置条件（本次未部署）

1. 本分支基于本地已验证的 Chat Agent 提交 `24fa624`。GitHub fetch 的 TLS 证书链失败，提交/PR 前需用有效证书重新核对最新 main 并检查 V24 迁移编号是否冲突，不可关闭 TLS 校验。
2. 应用数据库迁移 `V24__personal_model_settings.sql` 创建个人配置表。必须先在隔离 PostgreSQL 中验证迁移/备份恢复，再安排正式发布。
3. 配置 `guanxian.ai.personal.encryption-key`：base64 编码的随机 32 字节独立密钥。可使用环境变量 `GUANXIAN_AI_PERSONAL_ENCRYPTION_KEY`，生产优先通过 secret/configtree 注入。无配置时禁止保存，不降级成明文。
4. 现有 `compose.single-host.yml` 的 server 只有内部网络，且外发开关固定关闭。只在 deploy.env 中加参数不够。仓库提供显式 opt-in 的 `compose.personal-model.yml` 作为部署示例：挂载 `${SINGLE_HOST_DIR}/secrets/ai_personal_encryption_key` 到 `guanxian.ai.personal.encryption-key`，并给 server 增加独立出网网络。管理员批准外发后才设置 `GUANXIAN_PERSONAL_MODEL_EGRESS_ALLOWED=true`。此 overlay 不会改变全局 provider enabled=false，也不会开放其他服务出网。
5. 使用 overlay 时，每次部署/重建都必须同时指定基础 compose 和此 overlay。当前生产维护 wrapper 固定只读基础 compose，**尚不能自动部署这个 overlay**；需另行审核更新 wrapper，不能绕过其权限边界。
6. 有防火墙/代理时，只允许上述厂商域名的 TLS 流量。overlay 提供网络连通性，不是操作系统级域名防火墙；如需强制出网白名单需配合代理或网络策略。
7. 不在聊天中索取密钥；上线后用户自行在模块输入真实 API Key，验证余额、地域、模型 ID、流式回答和实际只读查询。

## 验证命令

```text
# apps/server
mvn -B -ntp -pl bootstrap -am test -Dtest=*Personal*,*Assistant* -Dsurefire.failIfNoSpecifiedTests=false

# apps/web
npm test
npm run build
npx playwright test --config playwright.personal-model.config.ts
```

协议测试使用 Spring AI 真实 HTTP/SSE 解析器和工具调用循环，HTTP 传输由本地测试替身提供；不使用真实厂商密钥，不产生计费。浏览器测试挂载真实 AppShell 与组件，设置接口由本地 fixture 提供，不冒充生产端到端验收。PostgreSQL 集成测试需要 Docker，无 Docker 时明确跳过。

本地验证记录（2026-09-05）：前端 242 项单元测试通过、生产构建通过；桌面/手机 3 项浏览器交互测试通过。后端全量运行及最后定向回归后的报告合计 389 项：326 通过、63 项因 Docker 不可用跳过、0 失败、0 错误。跳过项包含新增 PostgreSQL 迁移/持久化测试，因此不能宣称已在真实数据库完成该模块验收。没有调用真实模型厂商、推送、创建 PR 或部署。

仍不包含：账号充值、精确账单统计、真实模型质量评测、写操作代理或网页外 MCP 执行。请求 token 和时间限制沿用平台护栏；成本估计使用管理员配置费率，不是各家实际账单。

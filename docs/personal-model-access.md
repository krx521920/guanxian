# 个人模型接入

## 使用范围

协会工作台和右下角浮动聊天的输入区，发送按钮左侧小图标打开「模型接入」。每个已登录且具有 POLICY_READ 权限的账号可以保存一组当前生效的个人配置：厂商、模型 ID、API Key。不是全平台模型配置，不提供管理员代看或代设他人密钥的接口。

支持豆包/火山方舟、DeepSeek、Kimi/月之暗面、千问/百炼北京地域的 OpenAI-compatible Chat Completions 文本接口。模型 ID 由用户从控制台填写，不把易变的模型版本硬编码为唯一选项。需要所选模型支持文本流式输出；只读业务工具还需要该模型支持 function calling。网页会员订阅不等于开发者 API 余额。本版不包含图片、语音、Coding Plan 专用接口、自定义中转站或任意 Base URL。

官方接口依据（2026-09-05 核对）：

- [火山方舟 OpenAI 兼容文档](https://www.volcengine.com/docs/82379/1330626)：`https://ark.cn-beijing.volces.com/api/v3/chat/completions`。
- [DeepSeek 首次调用](https://api-docs.deepseek.com/)：`https://api.deepseek.com/chat/completions`。
- [Kimi 快速开始](https://platform.kimi.com/docs/get-api-key)：`https://api.moonshot.cn/v1/chat/completions`。
- [百炼 Base URL 说明](https://help.aliyun.com/en/model-studio/base-url)：北京共享域名仍可用，`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；必须搭配同地域按量付费 API Key。专属业务空间域名和其他地域不在本版范围。

## 操作

1. 选择厂商、填写模型 ID 和该厂商 API Key。
2. 点击「保存并启用」，默认使用该模型，不再显示两项勾选框。按钮前有简短外发/计费说明；打开弹窗本身不启用、不发送任何模型请求。留空 API Key 仅能保留同厂商原密钥；切换厂商必须重新填写。
3. 点击「测试连接」。测试使用已保存配置，只发送固定测试文本，不带业务数据或历史记录；可能产生少量 API 费用。每个账号至少间隔 30 秒。
4. 保存并启用后，下一次聊天优先使用它。删除配置则回到原平台默认模式。原先停用的配置不会因为打开弹窗而自动启用；需要点击保存。

弹窗桌面宽度上限 480px、高度上限 680px；小屏幕按视口收缩，只有正文滚动，关闭和保存区固定可见。服务器前置条件未满足时仍如实提示并阻止不可执行的动作：密钥加密未就绪不能保存；外发未启用只能「保存配置」，不能测试或声称已成功调用大模型。

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

## 部署前置条件

1. 已按 `main` 的企业自助与管理员开户代码整合，保留团队权限、修改密码、返回按钮及公开审核流程；未部署。Git HTTPS 证书链故障时通过已授权 GitHub API 上传并逐项校验 Git tree，不关闭 TLS 验证。
2. 个人配置表迁移由未上线开发稿 `V24` 改为 `V29__personal_model_settings.sql`，在现有 `V25–V28` 之后顺序执行，不修改既有迁移或启用 out-of-order。隔离 PostgreSQL 测试覆盖从 V28 升至 V29；CI 要求实际执行该测试，不能跳过。正式发布前仍须备份和独立部署审批。
3. 配置 `guanxian.ai.personal.encryption-key`：base64 编码的随机 32 字节独立密钥。可使用环境变量 `GUANXIAN_AI_PERSONAL_ENCRYPTION_KEY`，生产优先通过 secret/configtree 注入。无配置时禁止保存，不降级成明文。
4. 现有 `compose.single-host.yml` 的 server 只有内部网络，且外发开关固定关闭。只在 deploy.env 中加参数不够。仓库提供显式 opt-in 的 `compose.personal-model.yml` 作为部署示例：挂载 `${SINGLE_HOST_DIR}/secrets/ai_personal_encryption_key` 到 `guanxian.ai.personal.encryption-key`，并给 server 增加独立出网网络。管理员批准外发后才设置 `GUANXIAN_PERSONAL_MODEL_EGRESS_ALLOWED=true`。此 overlay 不会改变全局 provider enabled=false，也不会开放其他服务出网。
5. 使用 overlay 时，每次部署/重建都必须同时指定基础 compose 和此 overlay。当前生产维护 wrapper 固定只读基础 compose，**尚不能自动部署这个 overlay**；需另行审核更新 wrapper，不能绕过其权限边界。
6. 有防火墙/代理时，只允许上述厂商域名的 TLS 流量。overlay 提供网络连通性，不是操作系统级域名防火墙；如需强制出网白名单需配合代理或网络策略。
7. 不在聊天中索取密钥；上线后用户自行在模块输入真实 API Key，验证余额、地域、模型 ID、流式回答和实际只读查询。

### 现有生产维护账号的一次性初始化

截图里的两条提示是实际部署条件未满足，不是前端误报。`tools/deployment/configure_personal_model_access.py` 为当前固定路径、固定版本维护程序提供一次性管理员配置，默认只预演，不重启服务。生产开启之前需审阅并执行以下步骤，不能仅隐藏提示或绕过权限：

```bash
# 由已有 admin 会话执行；先核对/上传本次已审阅的脚本。
sudo python3 tools/deployment/configure_personal_model_access.py
sudo python3 tools/deployment/configure_personal_model_access.py \
  --execute --confirm-egress I_APPROVE_PERSONAL_MODEL_DATA_EGRESS
```

- 只在 `/opt/guanxian-single/secrets/ai_personal_encryption_key` 不存在且从未初始化时生成独立随机 32 字节主密钥。已有有效密钥原样复用；缺失旧密钥或非法内容停止，不轮换、不输出密钥。
- 主密钥叶文件为 0444 供非 root Java 容器只读，主目录和 secrets 目录必须为 root 私有，避免宿主机其他用户读取。密钥必须与数据库备份分开保管，丢失时恢复原密钥，不能重新生成顶替。
- 新建 root 私有 `/opt/guanxian-single/personal-model.compose.yml`，仅给 server 挂载加密密钥、开启个人模型出网及现有外发开关。平台共享模型与 Embedding 仍关闭，不给其他服务增加出网能力，不开放端口、不变更数据库。
- 维护程序必须匹配此前安装的 SHA256 `2390806386ee5501538cf7f591f5287046f298b04345a8978a9acadaa08e0e00`。初始化只给原 COMPOSE 命令追加固定 root overlay 路径；不新增 sudo 权限、任意命令或目标路径参数。已有不同版本停止供人工审阅。
- 原维护程序先备份到 root 私有 `personal-model-setup-backup-*` 目录，Compose 校验成功后才原子替换。以后正常 build/restart 始终包含此 overlay，不会因下一次部署丢失模型配置。
- 此脚本需要 admin/root 一次性执行，现有 karax 维护权限不能替代它。脚本执行成功后仍需通过原固定维护程序重启 server、重载 gateway 并验收；脚本不声称已经连接任何模型。
- 回退只回退维护程序/关闭模型出网后重建 server；保留主密钥及已有个人配置，不恢复整个业务库或删除密钥。原代码回滚点不受影响。

本次代码测试使用模拟 Docker 和临时目录，不冒充生产配置已启用；真实 API 连通性必须由用户在网页输入自己的 API Key 后测试。

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

# 管理员直接开通企业负责人账号

状态：本地实现，默认关闭，未部署。此变更基于本地企业自助功能 V27，增加 V28；不能只复制前端上线。真实 PostgreSQL / Keycloak 测试通过前不作为生产可用承诺。

## 用户流程

1. 系统管理员在左侧明确选择管理协会，在“会员企业”对应企业行点击“企业账号”。填写登录名、负责人授权核验说明并确认，点击“开通企业账号”。
2. 系统创建统一认证账号、绑定所选企业、授予本企业 `ENTERPRISE_ADMIN` 权限，并记录开通人的 subject、账号、组织范围、时间和请求标识。无须企业再次确认邀请或等待绑定审批。
3. 弹窗返回独立随机临时密码，只在本次响应显示。管理员通过已核验渠道单独交付。首次登录必须在 Keycloak 修改密码，企业入口随后进入“我的企业”。管理员入口不受影响，已有合法深链接仍保留。
4. 负责人通过头像菜单“修改密码”进入统一认证改密流程，要求重新认证。网站后台不接收、不保存负责人自己设置的密码。
5. 忘记密码时，系统管理员核验接收人并填写重置原因，二次确认后生成新临时密码。旧会话及重置开始前的应用访问令牌失效，负责人再次登录必须改密。

开通账号不代表企业资料已审核或已公开；现有资料草稿、审核和公开授权流程保持不变。游客和普通企业成员没有开户/重置入口或接口权限。

## 范围与限制

- 本期入口仅允许 `SYSTEM_ADMIN` + `ACCESS_BINDING_WRITE`，不是所有协会运营人员。须指定有效协会，企业必须在所选范围内且未删除/停用。前端按钮不是安全边界，后端重复校验权限、绑定及版本。
- 一家企业只允许一个此流程创建的主负责人。发现任何已有企业绑定、同名平台账号或其他开户任务时拒绝覆盖。已有邀请绑定、历史企业账号不能用本模块直接接管或重置，需继续原认证管理渠道。
- 不自动给已导入的 106 家企业开户，不发邮件，不批量发送密码，不生成统一默认密码，不创建 SSH 账号。
- 不提供“查看密码”、密码列表或永久明文导出。临时密码丢失只能明确重置，GET 状态接口永不返回密码。
- 账号被停用、撤销、解绑或绑定版本变化后，重置不能恢复权限。需独立身份核验处理。
- 重置使用秒级令牌签发时间截止点，截止秒及以前的令牌拒绝；同一秒内立刻重新签发的令牌也会拒绝，应下一秒重新登录。

## 一次性部署准备（本次未执行）

1. 对现有业务数据库和 Keycloak 数据库做受保护备份；在隔离环境验证 V27、V28 迁移与恢复。先部署兼容后端，再部署前端。
2. 在业务 realm 创建专用 confidential client `guanxian-account-provisioner`：开启 service accounts，关闭标准交互登录和 direct access grants。不使用 master 管理员密码，不给浏览器客户端管理权限。
3. 服务账号仅配置本 realm 用户管理所需的 `realm-management` 角色 `manage-users`、`view-users`、`query-users`，不授予 `realm-admin`、`manage-realm`、`manage-clients`。这些角色仍具有敏感的用户管理能力，须限制网络和妥善保护凭据；当前实现不是 Keycloak 细粒度“仅托管用户”权限方案。
4. 将新的 client secret 存为 root 管理的受保护文件，只读挂载到后端容器，确保后端进程可读而其他业务用户不可读。不要写入 Git、前端环境变量、命令行参数或普通日志。
5. 在 Realm Settings → User Profile 保留现有字段与校验，**追加**以下受保护属性；不要用测试 profile 整体覆盖生产配置：

```json
{
  "name": "guanxianProvisioningId",
  "multivalued": false,
  "permissions": { "view": ["admin"], "edit": ["admin"] },
  "validations": { "length": { "min": 36, "max": 36 } }
}
```

该标记不得允许普通用户编辑、不得配置到公开 token 中。保持其他未声明属性默认禁止。它用于确认恢复操作只能处理同一开户任务创建的身份，不能冒领其他同名用户。缺少属性配置时 Keycloak 可能忽略标记，开户会安全地停止在未完成状态。

6. 核对 realm 用户资料和登录策略：本期创建用户名、firstName、lastName，不要求邮箱。若生产强制邮箱/邮箱验证或其他字段，先确定收集方案，不可自动放宽现有策略。本方案仅在账号名登录、首次改密流程经隔离验收后启用。
7. 后端可用配置如下。生产 realm URL 必须 HTTPS，使用受信任证书；禁止 `-k` 或关闭验证。容器必须能访问 `/admin/realms/...` 和 token endpoint。Compose 不会自动传入宿主环境，须明确添加 environment 和只读 secret 挂载，以下路径是容器路径：

```text
GUANXIAN_ACCOUNTS_ENABLED=true
GUANXIAN_ACCOUNTS_REALM_URL=https://<身份服务域名>/identity/realms/guanxian
GUANXIAN_ACCOUNTS_CLIENT_ID=guanxian-account-provisioner
GUANXIAN_ACCOUNTS_CLIENT_SECRET_FILE=/run/secrets/account-provisioner
```

未显式启用时页面显示“开户服务尚未启用”，不降级为本地密码库。关闭管理连接不会撤销已成功开户用户的登录权限。

参考：Keycloak 官方 [用户资料权限、Service Accounts 和应用发起改密](https://www.keycloak.org/docs/latest/server_admin/)、[Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html)。实现使用临时密码重置接口、会话 logout 和 OIDC `kc_action=UPDATE_PASSWORD`，不调用密码读取接口，不给新负责人写入 realm 角色。

## 一致性与故障恢复

认证系统与业务数据库不是单个分布式事务。V28 `enterprise_managed_account` 持久化 `CREATING → ACTIVE` 或 `ACTIVE → RESETTING → ACTIVE`，并记录真实外部 subject、绑定版本和令牌截止点，不存密码。

- 先创建禁用的外部身份，确认受保护任务标记和真实 subject，再设临时密码、绑定业务身份和启用。不能假定 Keycloak 接受调用方提供的用户 ID，也不跟随返回的 Location/HTTP 重定向。
- 网络响应丢失或数据库提交失败时，保留未完成状态。即使外部身份已启用，应用仍拒绝未完成或过期绑定的令牌，不能返回假的开户成功。
- 页面不自动重放写请求。先刷新状态，明确点击“恢复未完成操作”；恢复重新生成临时密码。若状态已 ACTIVE 而密码丢失，应明确重置，不回查原密码。
- 外部同名账号/错误任务标记不会被接管。若冲突导致任务停留在 CREATING，运维核验后另行处理任务与用户名；本期没有删除/取消任务按钮，不能盲删外部用户或手工改成 ACTIVE。
- 远程调用限制响应大小 256 KiB、单次请求整体 10 秒，禁止重定向；错误不回显上游响应体、密码或 secret。前端写操作有 180 秒上限。代理若更早超时，也必须按“结果未知→读状态”处理，不能重复点击。

## 验证与上线门禁

本地已有：H2 事务/授权测试、HTTP 控制器测试、受控 HTTP 服务上的实际 Admin REST 请求合同测试、前端单元/构建及隔离浏览器页面测试。测试覆盖越权、跨协会、重复绑定、强版本检查、密码不持久化、故障恢复、旧令牌失效和无业务管理员角色继承。

本轮本地后端 `mvn verify` 汇总 435 项：369 项通过、66 项因缺少 Docker 环境跳过、0 失败；前端构建和 301 项单元测试通过。隔离浏览器页面回归 36 项通过，包含新增 6 项账号管理测试。Compose 配置校验及新 E2E 脚本类型检查通过。上述数字不代表真实认证端到端验收通过。

`ManagedEnterpriseAccountsPostgresTest` 使用真实 PostgreSQL 迁移/事务；CI 断言该套件必须实际执行，不能 skip。`apps/web/tests/e2e/enterprise-account-provisioning.spec.ts` 使用真实 Keycloak + PostgreSQL，验证首次强制改密、我的企业、自助改密和重置后的旧会话拒绝，无接口 mock。

真实 E2E 仅允许 `http://127.0.0.1:18082` 的一次性 Compose 环境。`compose.e2e.yaml` 中服务账号、测试 secret 和 user profile 全部仅供隔离验收，不得复制到生产。CI 的正常浏览器 E2E 作业包含该测试。

本机没有可用 Docker，真实 PostgreSQL 套件会跳过，真实 Keycloak 端到端测试尚未执行。必须在有 Docker 的 CI/隔离环境通过上述门禁，之后再审批部署并进行最小范围试开通。模拟测试通过不能当成正式网站已可开户。

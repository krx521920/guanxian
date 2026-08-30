# 浏览器端到端验收

这组测试只连接隔离的 E2E 环境，使用真实 Keycloak Authorization Code + PKCE 登录和后端身份绑定，不注入演示身份或伪造 JWT。

覆盖范围：

- 协会管理员、两家企业管理员及企业业务人员的登录与页面权限；
- 产品/服务与需求建档、提交、审核、匹配生成、协会推荐、双方确认；
- 定向邀请及应答、五阶段洽谈、双方成功反馈和成果归档；
- 附件上传、原文下载校验、软删除和恢复。E2E 服务启用 Redis 附件写操作限流，因此这些成功写入也会经过 Redis；对象正文由 MinIO 保存；
- PostgreSQL 通知读取、跳转和标记已读。通知本身不是 Redis 队列，不能把该测试描述成“Redis 通知”。

从仓库根目录启动隔离环境：

```powershell
./tools/testing/Start-E2eStack.ps1
```

环境就绪后执行：

```powershell
Push-Location apps/web
npm ci
npx playwright install --with-deps chromium
npm run test:e2e
Pop-Location
```

可用 `E2E_WEB_BASE_URL` 覆盖默认的 `http://127.0.0.1:18082`，但必须同时让 Web 构建的 OIDC 回调地址、Keycloak 客户端登记地址和该基址完全一致；只改 Playwright 基址会导致登录回调失败。测试使用单 worker、零重试，失败时报告、截图、录像和 trace 写入 `test-results/browser-e2e/`。

测试会创建带时间戳的业务数据，只能在项目名为 `guanxian-platform-e2e` 的隔离数据卷中运行，不得指向生产数据库、生产对象存储或生产身份域。重复启动默认保留数据卷；只有确需全新基线并核对项目名后，才可单独执行清理卷操作。

2026-08-31 本地基线：3 个 Playwright spec、5 条业务旅程在单 worker、零重试条件下全部通过。

# 测试工具快速入口

默认验证范围只包含常规功能、权限拒绝、数据隔离、身份协议和运行时契约。模糊请求、高压/负载、ZAP 主动扫描和网络故障注入已退出 CI 与项目执行入口。

```powershell
# 常规功能、权限、数据隔离和集成测试
./scripts/verify.ps1

# 启动后端与 AI 服务，验证鉴权、匹配、请求追踪和 ETag 乐观并发
./tests/smoke/runtime-smoke.ps1

# 启动隔离的 PostgreSQL、MinIO、Redis、Keycloak/OIDC 联合环境
./tools/testing/Start-E2eStack.ps1
Push-Location apps/web; npm run test:e2e; Pop-Location
```

浏览器 E2E 固定使用项目名 `guanxian-platform-e2e`、单 worker 和零重试，不得连接生产数据或生产身份域。覆盖基址时还必须同步 Web OIDC 回调和 Keycloak 客户端登记地址。

变异测试分别执行：

```powershell
Push-Location apps/server; mvn -pl bootstrap -am verify -Pmutation; Pop-Location
Push-Location apps/web; npm run mutation; Pop-Location
Push-Location services/ai; ./scripts/run_mutation.ps1; Pop-Location
```

详细前置条件、覆盖矩阵、报告路径和本轮实测基线见 `docs/testing-guide.md`。仓库中遗留的主动测试脚本仅作为历史材料保留，不属于批准的执行范围，也不得由 CI、自动化或开发流程调用。

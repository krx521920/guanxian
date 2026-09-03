# 阿里云 4 核 8 GB 单机部署（首次安装）

状态：新增部署入口，须完成目标服务器逐项验收后才能称为已上线。
适用于 Ubuntu 24.04、Docker Compose、已有域名和 Certbot 证书的**新安装**。
不是覆盖旧生产数据库的升级/恢复工具。不要与 `compose.yaml` 或
`compose.production.yml` 合并运行，也不要导入 E2E 数据、演示密码或测试 realm。

## 资源及边界

- 同机运行 PostgreSQL、Redis、MinIO、ClamAV、Keycloak、Java、Vue 和 Nginx。
- 只有 Nginx 发布 80/443。数据库、缓存、对象存储、身份管理后台不发布公网端口。
- Keycloak 使用 PostgreSQL、生产 `start`、PKCE、精确回调，禁用公开注册和密码授权。
- `/identity` 为正式登录服务；`/auth/callback` 保留给 Vue，不能互相覆盖。
- Java 访问 MinIO/Redis 使用 HTTPS/rediss，由同机 Nginx 终止 TLS；Nginx 到这两项
  服务的最后一跳是隔离 Docker 网络内的明文。此方案**不是跨主机端到端加密**。
- 真实 Redis 附件限流健康检查仍开启。仅关闭与该连接重复且默认指向 localhost 的
  Spring 通用 Redis health indicator，不关闭自定义 `redisAttachmentRateLimiter`。
- ClamAV 不可用时附件上传拒绝，不切回 content-only；首次下载签名库可能较慢。
- 配置了进程内存上限及日志轮转，但这不是经过生产压力验证的容量承诺。首次构建
  应在业务服务全部启动前顺序执行；运行期需观察内存/磁盘，不同时跑大型批处理。
- 本方案没有同时部署完整 Prometheus/Grafana/Loki 栈；没有配置邮件发送/外部模型。
- 数据库未配置 PITR/高可用。每天备份数据库及附件到异地并完成恢复验收后，才导入
  正式会员数据；同机 Docker 卷和阿里云单份系统快照不能替代异地备份。

## 1. 生成服务器专用配置

确认代码是已审核的 main；保留本次修改并完成代码传输后才能执行新脚本。
在仓库根目录执行，`--release` 填本次实际代码提交的短 SHA，用户名换成实际操作人。
不要把密码放进命令行；脚本会隐蔽提示输入两次。

```bash
sudo python3 tools/deployment/prepare_single_host.py \
  --domain guanxian.miraphant.com --username operator-name --release ACTUAL_COMMIT_SHA
```

脚本先检查证书，拉取官方依赖并固定 digest，再生成 `/opt/guanxian-single`。
已有目录时拒绝运行，不轮换密码、不覆盖身份。拉取失败时先解决网络问题，不改源到
未知镜像仓库。不允许跳过证书校验或改成演示模式。

`/opt/guanxian-single` 及 secrets 子目录为 root/0700，env/manifest 为0600。
单个容器 secret 文件为0444，这是文件型 Compose bind mount 下非 root 进程可读所需；
主机普通用户仍不能穿越0700父目录，容器只获得各自声明的 secret。不要递归放宽目录权限。
初始化 realm 内含临时操作人密码；首登必须修改。完成登录验收后在维护窗口移除
临时密码材料和 Keycloak 引导管理员，不要把目录、私钥、截图中的密码上传到 Git。
MinIO 原生 `mc admin user add` 需要短时在初始化容器的进程参数中传递应用凭据；
不会写入宿主机命令历史/Compose环境，容器不得共享PID命名空间或启用命令跟踪。

## 2. 构建与分阶段启动

以下命令在仓库根目录执行。每一步成功后再进入下一步，报错保留日志，不反复重建数据卷。

```bash
sudo docker compose --env-file /opt/guanxian-single/deploy.env -f compose.single-host.yml --profile app config --quiet
sudo docker compose --env-file /opt/guanxian-single/deploy.env -f compose.single-host.yml build server
sudo docker compose --env-file /opt/guanxian-single/deploy.env -f compose.single-host.yml build web
sudo docker compose --env-file /opt/guanxian-single/deploy.env -f compose.single-host.yml up -d --wait --wait-timeout 900 postgres redis minio keycloak clamav gateway
sudo docker compose --env-file /opt/guanxian-single/deploy.env -f compose.single-host.yml --profile app up -d --wait --wait-timeout 300 server web
```

启动 server 时会先自动执行 minio-init，创建私有桶和受限应用账号，成功后才启动后端。
此时页面应可达，但操作人账号尚未完成数据库绑定，按设计拒绝访问业务数据。
Flyway 自动创建业务结构及默认协会，不创建106家企业或假业务记录。

Web 镜像中的 `/api/` 代理必须保留强 ETag，不能通过 gzip 将版本号弱化；前端依赖该版本号
提交审核和编辑。单机网关的 `/api/` 直接连接后端，但其他编排可能经过 Web 代理。
修改 `apps/web/nginx.conf` 后须重新构建 Web 镜像，不能只 reload 外层网关。更新这类代理
配置不需要重新执行 `prepare_single_host.py`，也不需要更换账号、密码或重建数据卷。

## 3. 首次绑定操作人

核对 `/opt/guanxian-single/manifest.json` 中 domain、operator、subject 和协会 ID。
下面脚本只在身份库为空且 V23 已完成时写入一条绑定和一条审计，重复运行会报错，
不会更新现有用户。按仓库运行手册要求留存执行/复核记录。

初始化使用下面的受控文件（sudo同时负责读取）：

```bash
sudo sh -c 'docker compose --env-file /opt/guanxian-single/deploy.env -f compose.single-host.yml exec -T postgres psql -U guanxian -d guanxian -v ON_ERROR_STOP=1 < /opt/guanxian-single/bootstrap.sql'
```

打开 `https://guanxian.miraphant.com`，使用创建时填写的操作人用户名/密码，完成改密。
选择“北京地下管线协会”上下文。先验证空状态，再创建真实协会管理员和企业身份；
后续账号需同时在正式 Keycloak 中建号并通过平台账号管理建立 subject/企业绑定。
不能通过重新导入 realm、改 SQL 或恢复被停用的主体来绕过账号生命周期。

## 4. 网关上线后的续期

当前证书最初通过 standalone 获得。网关占用80端口后必须改为 webroot，并验证重载：

```bash
sudo python3 tools/deployment/configure_single_host_renewal.py
systemctl list-timers --all certbot.timer
```

脚本让 Certbot `reconfigure` 验证 webroot，安装仅对该域名生效的 deploy hook，再运行
`renew --dry-run --run-deploy-hooks`。不会停止数据库/网站，不覆盖已有hook。网关以目录
方式挂载 live/archive，证书软链接更新后 nginx reload 能读取新文件。
不要把 dry-run 成功等同于已完成正式到期续期；仍须监测真实证书到期时间。

## 5. 交付验收（逐项留证，不自动标记通过）

- HTTP跳转HTTPS、证书域名正确；网关后重新做续期模拟及重载验证。
- 容器健康、重启次数、主机 `free -h`/`df -h`/`docker stats`；磁盘预警。
- 首登改密、真实OIDC、数据库绑定、协会上下文、注销/重新登录；未绑定用户拒绝访问。
- 真实资料在预生产预览、错误行/重复信用代码处理、协会审核，保留原件出处。
- 附件经过ClamAV、MinIO持久化、Redis限流，验证上传/下载及服务异常的明确报错。
- 数据库/MinIO离机备份、恢复演练、基本可用性及证书到期告警、发布与回滚记录。
- 不对外称“AI平台”：外部模型、Embedding、数据出境、真实知识评测仍未启用。

本文没有提供删除数据卷命令。排错不能执行 `down -v` 或重新初始化已有正式库。

参考：

- [Keycloak生产容器与入口脚本](https://www.keycloak.org/server/containers)
- [Keycloak反向代理配置](https://www.keycloak.org/server/reverseproxy)
- [Compose文件型secret的权限限制](https://docs.docker.com/reference/compose-file/services/#secrets)
- [Certbot修改续期方式与续期验证](https://eff-certbot.readthedocs.io/en/stable/using.html#modifying-the-renewal-configuration-of-existing-certificates)

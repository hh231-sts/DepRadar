# DepRadar

DepRadar 是一个依赖风险审查与发布门禁工具。开发者上传 CycloneDX SBOM 登记应用版本，系统异步查询 OSV 漏洞数据，保存风险证据与依赖引入路径，再结合人工审查、限期例外和项目规则生成发布门禁结果。

项目采用 Java 21 模块化单体架构，Web 接口、扫描 Worker 和恢复调度运行在同一个 Spring Boot 应用中。

## 功能

- 版本管理：登记 SBOM、校验 Maven 坐标、按文件哈希去重，保存原始清单和依赖图。
- 异步扫描：分批查询漏洞，使用事务 Outbox、RabbitMQ 发布确认和任务租约完成投递与故障恢复。
- 风险审查：查看原始公告与最短引入路径，提交报告审查、申请限期例外，记录审批过程。
- 发布门禁：返回 `PASS`、`BLOCK` 或 `INDETERMINATE`，附带报告绑定、评估原因和有效截止时间。
- 版本对比与 CI：比较组件和风险变化，通过命令行脚本把门禁结果接入流水线。

## 技术栈与目录

| 层次 | 技术 |
| --- | --- |
| 应用 | Java 21、Spring Boot 3.5.16、Spring Security |
| 数据 | MyBatis、MySQL 8.4、Flyway |
| 异步任务与缓存 | RabbitMQ 4.1、Redis 7.4 |
| 页面 | Thymeleaf、原生 JavaScript、CSS |
| 构建与运行 | Maven Wrapper 3.9.11、Docker Compose |
| 测试 | JUnit 5、MockMvc、Testcontainers、Awaitility |

```text
src/main/java/io/depradar/
  identity/       操作人、团队角色与请求授权
  catalog/        项目、版本、SBOM 和依赖图
  scan/           漏洞来源、批次执行、消息投递与恢复
  report/         风险证据、依赖路径与版本对比
  governance/     审查、例外、规则、门禁与审计
  ci/             项目级 CI 凭据
  web/            页面入口与 REST API
  shared/         JSON、标识、分页与错误处理
src/main/resources/
  db/migration/   数据库迁移
  replay/         固定漏洞数据
  templates/      页面模板
  static/assets/  前端脚本与样式
src/test/         业务流程集成测试
examples/         SBOM 样例
scripts/          CI 接入脚本
```

实现细节见[架构与开发说明](docs/architecture.md)，接口约定见 [API 文档](docs/api.md)。

## 快速启动

安装 Docker Engine 和 Compose v2，将 `.env.example` 复制为 `.env`，按需设置应用端口和服务密码：

```sh
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

默认访问地址为 **http://localhost:8080**。应用端口由 `.env` 中的 `APP_PORT` 控制，例如设置为 `18080` 后访问 http://localhost:18080。

Compose 启动 MySQL、Redis、RabbitMQ 和应用四个服务，对外端口绑定 `127.0.0.1`。Flyway 随应用启动执行迁移，MySQL 与 RabbitMQ 的数据使用持久化卷保存。

| 服务 | 默认端口 |
| --- | --- |
| 应用 | 8080 |
| MySQL | 3306 |
| Redis | 6379 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ 管理页面 | 15672 |

打开工作台后进入默认工作空间。系统预置「默认维护人」（OWNER）和「默认审查人」（REVIEWER），右上角可切换操作人。团队成员管理支持按姓名添加操作人并分配角色。浏览器请求使用 `depradar_operator` Cookie 中的操作人上下文，CI 请求使用项目级 Bearer 凭据。

### 漏洞数据来源

通过 `OSV_MODE` 选择数据来源：

| 值 | 数据来源 | 用途 |
| --- | --- | --- |
| `replay` | `src/main/resources/replay/osv.json` 中的固定坐标与公告 | 本地开发、样例流程和可重复测试 |
| `online` | `https://api.osv.dev` 的批量查询与公告详情接口 | 查询已登记组件的在线漏洞数据 |

默认使用 `replay`。回放模式下，已收录坐标返回固定结果，未收录坐标生成 `INCOMPLETE` 查询结果，页面显示当前数据来源。

切换为在线模式时，在 `.env` 中设置 `OSV_MODE=online`，重建应用容器，然后在版本页提交新扫描：

```sh
docker compose up -d --force-recreate app
```

每次扫描保存当次来源证据及规则版本，历史报告保持原有绑定。

## 本地开发

安装 JDK 21 并设置 `JAVA_HOME`，启动依赖服务：

```sh
docker compose up -d mysql redis rabbitmq
```

Linux 和 macOS：

```sh
./mvnw spring-boot:run
```

Windows PowerShell：

```powershell
.\mvnw.cmd spring-boot:run
```

本地 JVM 从进程环境读取连接配置；Compose 从 `.env` 读取容器配置。修改服务密码后，将对应的 `DB_PASSWORD`、`MQ_PASSWORD` 同步到本地进程环境。

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/depradar?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | JDBC 连接 |
| `DB_USER` / `DB_PASSWORD` | `depradar` / `depradar-local` | 应用数据库凭据 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 连接 |
| `MQ_HOST` / `MQ_PORT` | `localhost` / `5672` | RabbitMQ 连接 |
| `MQ_USER` / `MQ_PASSWORD` | `depradar` / `depradar-local` | RabbitMQ 凭据 |
| `OSV_MODE` | `replay` | 漏洞数据来源 |
| `APP_PORT` | `8080` | Compose 的应用映射端口 |
| `MYSQL_ROOT_PASSWORD` | `root-local` | Compose 的 MySQL 初始化配置 |

应用配置集中在 `src/main/resources/application.yml`，包括批次大小、扫描配额、租约时长和重试次数。

需要通过 Maven 镜像或代理下载依赖时，可在 Docker 构建阶段传入 Maven settings：

```sh
docker build --secret id=maven_settings,src=/path/to/settings.xml -t depradar-app .
```

Dockerfile 使用 BuildKit secret 挂载 settings，构建缓存保存 Maven 依赖，运行镜像包含 JRE 与应用 JAR。

## 跑通业务流程

### 登记版本与审查

1. 以「默认维护人」创建项目，例如 `payment-service`。
2. 上传 `examples/sbom-v2.json`，标签填写 `2.0.0`。
3. 在版本页启动扫描。回放模式下扫描完成为 `SUCCEEDED`，等待审查期间门禁为 `INDETERMINATE`。
4. 查看来源证据，提交 `APPROVED` 审查决定及理由。门禁更新为 `PASS`，页面展示绑定信息与有效截止时间。

### 风险处置与例外

1. 在同一项目上传 `examples/sbom-v1.json`，标签填写 `1.0.0`，启动扫描。
2. 回放结果包含 Log4Shell 风险，门禁为 `BLOCK`；风险条目展示公告及 `payment-service → commons → log4j` 引入路径。
3. 以「默认维护人」申请例外，填写业务理由和截止时间。
4. 切换为「默认审查人」，批准例外并完成报告审查。满足项目规则后，门禁更新为 `PASS`。
5. 例外到期后，该风险重新参与阻断评估，审批过程保存在项目审计记录中。

`examples/sbom-incomplete.json` 增加了一个固定查询失败的组件，用于观察批次重试和 `PARTIAL` 扫描结果。其已知高风险产生 `BLOCK`；完成已知风险例外与报告批准后，查询资料不完整对应 `INDETERMINATE`。

相同清单可提交新扫描，每次扫描创建独立审查记录。项目页支持选择两次扫描进行组件与风险对比，具体计算规则见[版本对比](docs/architecture.md#版本对比)。

## 接入 CI

以 OWNER 操作人在项目页创建 CI 凭据，将返回的 token 保存到流水线的 `DEPRADAR_CI_TOKEN` secret 中。创建响应提供 token 原文，数据库保存其 SHA-256；吊销后，后续请求返回凭据失效。

CI 脚本使用 Python 3.11+ 标准库，依次完成 SBOM 上传、扫描提交、状态轮询和门禁查询。

Linux 和 macOS：

```sh
python scripts/depradar-ci.py \
  --base-url http://localhost:8080 \
  --project 项目UUID \
  --tag 2.0.0 \
  --sbom examples/sbom-v2.json \
  --idempotency-key "$CI_JOB_ID" \
  --timeout 600
```

Windows PowerShell：

```powershell
python scripts/depradar-ci.py `
  --base-url http://localhost:8080 `
  --project 项目UUID `
  --tag 2.0.0 `
  --sbom examples/sbom-v2.json `
  --idempotency-key $env:CI_JOB_ID `
  --timeout 600
```

执行前将凭据注入进程环境；PowerShell 对应 `$env:DEPRADAR_CI_TOKEN`。将地址和项目 UUID 替换为实际配置。同一 job 重试复用幂等键，不同 job 使用新键；省略该参数时，脚本自动生成 UUID。

| 退出码 | 含义 | 流水线处理 |
| --- | --- | --- |
| `0` | 绑定匹配且处于有效期内的 `PASS` | 继续后续发布阶段 |
| `2` | `BLOCK` | 处理阻断风险或完成例外审批后重试 |
| `3` | 扫描不完整、取消、超时、绑定冲突或请求失败 | 根据扫描状态与错误信息处理后重试 |

脚本每 5 秒轮询一次，等待 `INDETERMINATE` 状态下的人工审查，默认超时 600 秒。流水线在发布阶段前调用脚本，使用当次绑定与门禁结果决定后续步骤。

## 测试

运行集成测试需要 JDK 21 和 Docker。Testcontainers 启动 MySQL、Redis、RabbitMQ 临时实例，测试使用固定回放数据、可调业务时钟和本地 HTTP 服务验证流程。

```sh
./mvnw verify
```

Windows PowerShell 使用 `.\mvnw.cmd verify`。

[WorkflowIntegrationTest](src/test/java/io/depradar/WorkflowIntegrationTest.java) 包含三组测试：

| 场景 | 覆盖内容 |
| --- | --- |
| 业务闭环 | 操作人切换、版本导入、风险与路径、例外审批、报告审查、CI 绑定与凭据吊销、OSV 分页与详情契约 |
| 任务一致性 | Outbox 投递、重复消息、租约恢复、旧租约结果处理、取消后的迟到结果 |
| 门禁与对比 | 例外到期、证据到期、重新扫描、规则变更、不完整报告与版本对比、团队访问控制 |

## 运行观察

| 入口 | 用途 |
| --- | --- |
| `GET /actuator/health/liveness` | 进程存活状态 |
| `GET /actuator/health/readiness` | 应用、MySQL、Redis、RabbitMQ 就绪状态 |
| `GET /actuator/prometheus` | OWNER 操作人读取 HTTP、JVM 与业务指标 |
| http://localhost:15672 | RabbitMQ 队列与消息状态，凭据来自 `.env` |

业务指标包括 `depradar_scans_active`、`depradar_outbox_pending`、`depradar_scans_completed_total`、`depradar_batch_retries_total`、`depradar_batch_recoveries_total` 和 `depradar_outbox_publish_failures_total`。

排查任务时，先查看应用日志和批次状态，再检查 Outbox 投递情况：

```sh
docker compose logs -f app
docker compose exec mysql mysql -udepradar -p depradar
```

```sql
SELECT id, status, attempts, lease_until, error
FROM scan_batches ORDER BY available_at DESC LIMIT 20;

SELECT id, batch_id, sent_at, attempts, last_error
FROM outbox ORDER BY available_at DESC LIMIT 20;
```

`depradar.scan` 为扫描队列，`depradar.scan.dead` 保存消费异常消息。租约恢复与排队恢复由应用调度执行，处理过程见[任务恢复](docs/architecture.md#任务恢复)。

## 开发资料

- [架构与开发说明](docs/architecture.md)：模块职责、数据模型、事务、任务状态和扩展入口。
- [API 文档](docs/api.md)：请求上下文、接口列表、请求体、分页与错误响应。
- [OSV 批量查询与分页](https://google.github.io/osv.dev/post-v1-querybatch/)。
- [OSV 公告详情](https://google.github.io/osv.dev/get-v1-vulns/)。

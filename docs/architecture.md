# 架构与开发说明

DepRadar 围绕应用版本组织依赖扫描和发布审查。一个版本绑定一份 SBOM，每次扫描记录当次漏洞查询结果和规则版本，审查、例外及门禁围绕这次扫描计算。

## 模块职责

源码位于 `src/main/java/io/depradar`，采用按业务领域分包的模块化单体结构。REST 请求、消息消费和定时恢复使用同一组业务服务与数据库。

| 包 | 主要职责 | 入口 |
| --- | --- | --- |
| `identity` | 操作人上下文、团队成员与角色授权 | `SecurityConfig`、`Access`、`IdentityService` |
| `catalog` | 项目与版本登记、SBOM 解析、依赖图与幂等记录 | `CatalogService`、`SbomParser` |
| `scan` | 扫描提交、漏洞来源、批次执行、消息投递与恢复 | `ScanService`、`ScanJobs`、`Messaging` |
| `report` | 查询证据、风险条目、依赖路径、跨版本对比 | `ReportService` |
| `governance` | 规则版本、报告审查、限期例外、门禁和审计 | `GovernanceService` |
| `ci` | 项目级凭据的创建、查询与吊销 | `CiService` |
| `web` | REST 请求映射与页面入口 | `ApiController`、`PageController` |
| `shared` | JSON、标识、哈希、游标分页和统一错误响应 | `Json`、`Values`、`Errors` |

Mapper 接口使用 MyBatis 注解定义 SQL。数据库结构由 `src/main/resources/db/migration` 下的 Flyway 脚本管理，页面由 Thymeleaf 提供入口，`static/assets/app.js` 调用 REST API 并渲染业务内容。

## 数据流

```mermaid
flowchart LR
    SBOM[上传 SBOM] --> Release[登记版本与依赖图]
    Release --> Submit[提交扫描]
    Submit --> Tx[事务保存扫描 批次 Outbox]
    Tx --> Dispatcher[Dispatcher 投递消息]
    Dispatcher --> MQ[RabbitMQ]
    MQ --> Worker[Worker 领取租约]
    Worker --> Source[OSV 或固定回放]
    Source --> Report[保存证据 风险与报告]
    Report --> Review[报告审查与限期例外]
    Review --> Gate[三态门禁]
    Gate --> CI[CI 流水线]
```

### 数据模型

| 数据 | 表 | 关系与作用 |
| --- | --- | --- |
| 操作人与团队 | `operators`、`teams`、`memberships` | 操作人通过成员关系获得团队角色 |
| 项目与规则 | `projects`、`policies` | 项目维护当前规则版本，规则历史按版本保存 |
| 应用版本 | `releases` | 保存标签、原始 SBOM、SHA-256 与根组件引用 |
| 依赖图 | `components`、`dependency_edges` | 记录 Maven 组件和 SBOM 声明的依赖关系 |
| 扫描任务 | `scans`、`scan_batches`、`batch_components` | 按版本编号创建扫描，再按组件拆分批次 |
| 查询结果 | `component_results`、`findings` | 保存来源完整性、查询时间、证据、风险与路径 |
| 消息与幂等 | `outbox`、`idempotency_keys` | 保存待投递事件及请求到业务资源的映射 |
| 审查与例外 | `reviews`、`exceptions` | 关联报告绑定、审批决定、并发版本和截止时间 |
| CI 与审计 | `ci_credentials`、`audit_events` | 保存凭据哈希、吊销状态与业务决定记录 |

V1 建立业务表，V2 将操作人实体迁移为 `operators`，并初始化默认工作空间及两位操作人。后续结构变更通过递增版本的迁移脚本追加。

## 版本登记

`SbomParser` 解析 CycloneDX JSON 1.6。输入包含 `metadata.component.bom-ref`、`components` 和 `dependencies`，其中组件支持嵌套定义。

| 校验项 | 规则 |
| --- | --- |
| 文件大小 | 1 字节至 10 MB |
| 组件数量 | 每个版本 1–5,000 个 Maven 组件 |
| 组件字段 | `bom-ref`、`group`、`name`、`version`、`purl` 为非空字符串 |
| 引用与坐标长度 | `bom-ref`、`group`、`name`、`version` 最长 255 字符，`purl` 最长 1,200 字符 |
| 组件标识 | `bom-ref` 在根组件和库组件之间保持唯一 |
| Maven 坐标 | `group`、`name` 使用字母、数字、下划线、点和连字符 |
| PURL | 解码后的包路径与 `pkg:maven/group/name@version` 一致 |
| 依赖关系 | 节点引用根组件或已登记组件，边及声明节点保持唯一 |

`CatalogService.upload` 锁定项目后处理去重与幂等：

- `sbomHash` 按上传文件的原始字节计算 SHA-256，文件格式变化也会生成新的哈希。
- 相同内容复用项目中的版本；已存在标签绑定其他内容时返回 `409`。
- 导入的请求哈希由标签与清单哈希组成，扫描提交的请求哈希由版本 ID 组成。
- 幂等记录以项目、操作类型和请求键为主键。同键同请求返回已创建资源，同键不同请求返回 `409`。

原始清单、组件与依赖边在同一事务中保存。升级依赖时登记新的清单内容与标签，历史版本保留原有内容。

## 扫描执行

### 任务创建与投递

`ScanService.submit` 在项目锁内读取当前规则，分配版本内递增的 `ordinal`，并在同一 MySQL 事务中保存扫描、批次、批次组件和 Outbox 事件。

默认参数来自 `application.yml`：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `depradar.batch-size` | 50 | 每批组件数 |
| `depradar.max-active-scans` | 5 | 每个项目处于排队或运行状态的扫描上限 |
| `depradar.scan-requests-per-minute` | 10 | 每个项目每分钟新扫描提交次数 |
| `depradar.lease-seconds` | 120 | 批次租约时长 |
| `depradar.max-attempts` | 3 | 批次领取次数上限 |
| `depradar.scheduling` | `true` | Outbox 投递与恢复调度开关 |

幂等重放在配额统计之前返回原扫描。Redis 的 Lua 脚本维护按项目和分钟划分的入口计数。

`Messaging.Dispatcher` 每秒读取待发送 Outbox，通过 RabbitMQ 持久化消息投递批次 ID。收到发布确认并确认消息成功路由后，记录 `sent_at`。投递失败更新 `last_error` 和下一次投递时间，重试间隔按 5 秒递增，最大 60 秒。

RabbitMQ 消费并发为 2，每个消费者预取 1 条消息。消费异常消息进入 `depradar.scan.dead`，业务批次继续由恢复调度管理。

### 租约与结果提交

`ScanJobs.claim` 按扫描、批次顺序加锁，领取已到执行时间的 `QUEUED` 批次，增加尝试次数并生成租约令牌。重复投递的消息通过批次状态完成幂等处理。

漏洞来源在请求之间调用心跳续租。`ScanJobs.complete` 在事务中核对扫描状态、租约令牌和截止时间，再保存组件查询结果、风险条目及批次状态。有效令牌对应当前执行者，取消操作将扫描与活跃批次更新为 `CANCELLED`。

来源查询失败时，批次按 5 秒、10 秒的间隔重新执行；达到领取上限后保存最后一次查询结果和失败原因。全部批次结束后，根据查询完整性生成扫描终态、报告哈希和待审查记录。

### 任务恢复

恢复调度每 15 秒检查活跃扫描：

1. 运行批次的租约到期后，将批次恢复为 `QUEUED` 并创建新 Outbox 事件。
2. 已发送超过 30 秒、仍待领取且没有待投递事件的批次，重新创建投递事件。
3. 批次再次领取时检查尝试次数；达到上限后保存来源资料不完整的结果，参与扫描汇总。

### 扫描状态

| 状态 | 含义 |
| --- | --- |
| `QUEUED` | 扫描已创建，等待批次执行 |
| `RUNNING` | 已有批次开始执行 |
| `SUCCEEDED` | 所有组件的必要来源查询完整 |
| `PARTIAL` | 部分组件查询完整，部分查询失败 |
| `FAILED` | 所有组件的必要来源查询均失败 |
| `CANCELLED` | 排队或运行中的扫描被取消 |

扫描状态描述查询完整性，漏洞风险由 `findings` 表达，发布决定由门禁计算。`SUCCEEDED`、`PARTIAL` 和 `FAILED` 汇总生成报告；重新查询时创建新的扫描记录。

## 漏洞证据与依赖路径

`VulnerabilitySource` 提供统一查询接口，返回每个组件的来源、查询时间、完整性、公告、原始证据和错误原因。

- `OsvSource` 调用 `/v1/querybatch`，跟随各组件的分页 token，再通过 `/v1/vulns/{id}` 获取公告详情。连接超时为 10 秒，读取超时为 20 秒。
- `ReplaySource` 按 Maven 坐标读取 `replay/osv.json`，用于固定流程和测试数据。未收录坐标或带有错误的条目生成不完整查询结果。
- `Severity` 从来源标签与 CVSS v3 基础分中取最高等级，将 `MEDIUM` 归一化为 `MODERATE`；缺少可用等级时返回 `UNKNOWN`。
- 在线来源将撤回公告保存在查询证据中，有效风险集合由其余公告生成。

`CatalogService.paths` 从 SBOM 根组件执行 BFS，为可达组件保存一条最短引入路径。没有根到组件路径时保存空数组。原始依赖关系通过 `dependencies` 接口读取。

## 审查与发布门禁

报告绑定由以下字段组成：

```text
releaseId + sbomHash + scanId + reportHash + policyVersion
```

审查操作针对版本的最新扫描，提交时核对清单哈希、报告哈希和规则版本。报告审查及例外审批使用 `expectedVersion` 执行乐观并发控制，成功后递增版本，冲突返回 `409`。

### 项目规则与例外

新项目默认规则为阻断 `CRITICAL`、`HIGH`、`UNKNOWN`，要求人工批准，证据有效期为 24 小时，例外最长期限为 7 天。OWNER 创建新规则版本后，后续扫描绑定新版本，报告审查与例外围绕新扫描重新建立。

例外关联一次扫描中的一个风险条目，由另一位具备审查权限的操作人决定。申请截止时间位于当前时间与项目规则规定的期限之间，规则允许配置 1–7 天的上限。报告审查与风险例外分别记录：前者表达对报告的发布决定，后者使指定风险在有效期内获得放行条件。

规则变更、审查决定、例外申请与决定、CI 凭据创建与吊销均写入 `audit_events`。

### 门禁计算

`GovernanceService.gate` 先校验请求的扫描 ID 与清单哈希，再按当前规则读取风险、例外和审查状态，按以下顺序返回结果：

| 优先级 | 条件 | 结果 |
| --- | --- | --- |
| 1 | 当前规则下存在有效阻断风险，或存在有效人工拒绝 | `BLOCK` |
| 2 | 扫描资料不完整、证据到期、规则版本变化或等待人工批准 | `INDETERMINATE` |
| 3 | 来源查询、证据有效期、规则版本、风险处置与审查均满足条件 | `PASS` |

证据有效期从本次扫描最早的必要来源查询时间开始计算。`PASS.validUntil` 取证据截止时间与实际用于放行的各风险例外截止时间中的最早值。

新扫描更新版本的当前绑定；查询旧扫描返回 `409`。门禁在每次请求时按当前数据重新计算，CI 在发布阶段前获取该次结果及绑定。

## 版本对比

`ReportService.compare` 接收同一项目的两次扫描，以 `group:name:version` 比较组件，以组件坐标加公告 ID 比较风险。

结果包含新增与移除组件、新增风险、已不再检出的风险和未确定的旧风险。两侧扫描均为 `SUCCEEDED` 时，目标中缺失的旧风险进入 `resolvedRisks`；任一侧查询不完整时，这部分差异进入 `unresolvedRisks`，并返回 `riskComparisonComplete=false`。

## 事务与缓存

业务写事务使用 `READ COMMITTED`，根据操作范围锁定项目、版本、扫描或批次后读取最新状态。门禁与版本对比使用 `REPEATABLE READ` 事务读取一致性快照。

Redis 用于扫描入口计数和依赖图缓存。图缓存按版本 ID 与 SBOM 哈希区分，缓存时间为 1 小时；门禁直接读取 MySQL 中的扫描、规则、审批和例外数据。

## 操作人和 CI 上下文

`SecurityConfig` 采用无会话的请求上下文。浏览器从 `depradar_operator` Cookie 选择本地操作人，缺省时使用「默认维护人」；该 Cookie 表达客户端选择的业务操作人。修改请求携带 Cookie CSRF token。团队权限由 `memberships` 中的角色决定。

| 角色 | 权限 |
| --- | --- |
| `OWNER` | 读取、版本登记、扫描、报告审查、例外申请与审批、团队成员管理、规则与 CI 凭据管理 |
| `MAINTAINER` | 读取、项目创建、版本登记、扫描与例外申请 |
| `REVIEWER` | 读取、报告审查、审批他人的例外 |
| `VIEWER` | 读取项目、版本、报告、规则、门禁与审计 |

团队成员至少保留一位 OWNER。例外审批同时校验操作人角色与申请人身份。

CI 凭据为 32 字节随机值编码的 `dr_` 前缀 token，按 SHA-256 查询有效记录，并绑定单个项目。CI 上下文具备该项目的读取、版本登记、扫描提交与取消、门禁查询权限。人工审批、团队管理、规则管理和凭据管理使用操作人上下文。

## 扩展开发

- 新增漏洞来源：实现 `VulnerabilitySource`，通过配置选择对应 Bean，并在查询间调用心跳；用固定 HTTP 响应验证分页、详情、错误和完整性转换。
- 调整扫描执行：从 `ScanService` 的事务边界、`ScanJobs` 的租约状态及 `Messaging` 的投递流程入手，验证重复消息、超时接管和取消场景。
- 扩展发布规则：在 `GovernanceService` 中实现评估，更新规则持久化与请求模型，补充规则变化、证据到期及例外到期测试。
- 修改数据结构：新增 Flyway 迁移，并同步对应 Mapper、接口字段和集成测试。
- 调整交互：页面结构位于 `templates/app.html`，路由与 API 调用位于 `static/assets/app.js`，样式位于 `static/assets/app.css`。

回到[项目说明](../README.md)，或查看 [API 文档](api.md)。

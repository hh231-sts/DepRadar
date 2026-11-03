# API 文档

REST API 统一以 `/api/v1` 为前缀。普通请求使用 JSON，SBOM 导入使用 multipart。字段使用 camelCase，时间使用 ISO-8601 UTC，资源 ID 从创建或查询响应中获取。

控制器位于 `src/main/java/io/depradar/web/ApiController.java`，请求模型及字段校验与接口定义放在同一文件中。

## 请求上下文

### 工作台请求

浏览器通过 `depradar_operator` Cookie 选择本地操作人，缺省使用「默认维护人」。团队成员角色决定该操作人的业务权限。切换操作人时，将该 Cookie 设置为 `/context` 返回的操作人 ID。

`GET /context` 返回：

| 字段 | 含义 |
| --- | --- |
| `operator` | 当前操作人的 `id` 与 `name` |
| `operators` | 可选操作人列表 |
| `csrfToken` | 修改请求使用的 CSRF token |
| `csrfHeader` | 提交 CSRF token 的请求头名称 |

获取上下文时保存响应 Cookie，后续修改请求同时携带该 Cookie 与指定的 CSRF 请求头。下面的 PowerShell 示例读取默认工作空间并创建项目：

```powershell
$baseUrl = 'http://localhost:8080'
$context = Invoke-RestMethod -Uri "$baseUrl/api/v1/context" -SessionVariable workspace
$headers = @{}
$headers[$context.csrfHeader] = $context.csrfToken
$teams = Invoke-RestMethod -Uri "$baseUrl/api/v1/teams" -WebSession $workspace

$project = Invoke-RestMethod -Method Post `
  -Uri "$baseUrl/api/v1/teams/$($teams[0].id)/projects" `
  -WebSession $workspace -Headers $headers `
  -ContentType 'application/json' `
  -Body (@{ name = 'payment-service' } | ConvertTo-Json)
$project.id
```

### CI 请求

为 `/api/v1` 请求设置 `Authorization: Bearer <token>` 后，服务使用 CI 上下文，并按凭据绑定的项目授权。CI 请求通过 Bearer 凭据校验，工作台修改请求通过操作人上下文与 CSRF 校验。

CI 凭据具备项目读取、版本导入、扫描提交与取消、门禁查询权限。人工审查、例外申请与审批、团队管理、规则修改及凭据管理使用相应团队角色的操作人上下文。完整权限见[架构说明](architecture.md#操作人和-ci-上下文)。

## 接口列表

以下路径均省略 `/api/v1` 前缀。

### 团队与项目

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/context` | 当前操作人、操作人列表与 CSRF 信息 |
| `GET` | `/teams` | 当前操作人加入的团队及角色 |
| `POST` | `/teams` | 创建团队，创建者成为 OWNER |
| `GET` | `/teams/{teamId}/members` | 团队成员列表 |
| `PUT` | `/teams/{teamId}/members` | 按姓名添加成员或更新其角色 |
| `GET` | `/teams/{teamId}/projects` | 项目列表，游标分页 |
| `POST` | `/teams/{teamId}/projects` | 创建项目及默认规则 |
| `GET` | `/projects/{projectId}` | 项目详情 |

团队与项目创建请求体为 `{"name":"名称"}`，名称长度为 1–120 字符。成员修改请求体如下，姓名长度为 1–190 字符，角色取 `OWNER`、`MAINTAINER`、`REVIEWER` 或 `VIEWER`：

```json
{"name":"审查人姓名","role":"REVIEWER"}
```

### 版本与扫描

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/releases` | 版本列表，游标分页 |
| `POST` | `/projects/{projectId}/releases` | 上传 SBOM 并登记版本 |
| `GET` | `/releases/{releaseId}` | 版本、标签、清单哈希和根组件 |
| `GET` | `/releases/{releaseId}/sbom` | 下载原始 SBOM |
| `GET` | `/releases/{releaseId}/components` | 组件列表，游标分页 |
| `GET` | `/releases/{releaseId}/dependencies` | 根组件、组件与依赖边 |
| `GET` | `/releases/{releaseId}/scans` | 扫描列表，游标分页 |
| `POST` | `/releases/{releaseId}/scans` | 提交新扫描 |
| `GET` | `/scans/{scanId}` | 扫描详情与批次进度 |
| `POST` | `/scans/{scanId}/cancel` | 取消排队或运行中的扫描 |

版本导入的 Content-Type 为 `multipart/form-data`，字段为 `tag` 和 `file`。标签长度为 1–120 字符，文件格式和内容校验见[版本登记](architecture.md#版本登记)。成功返回 `201` 与版本对象。

扫描提交返回 `202` 与扫描对象，`Location` 响应头指向 `/api/v1/scans/{scanId}`。进度响应包含 `scan` 和 `batches`，前者提供扫描状态、规则版本、报告哈希与执行时间，后者提供批次状态、尝试次数、租约和错误。

导入和扫描提交支持 `Idempotency-Key` 请求头，长度为 1–120 字符。同一项目、同一操作下，键和请求匹配时返回原资源，键已用于其他请求时返回 `409`。

### 风险与报告

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/scans/{scanId}/report` | 扫描、组件查询结果与风险数量 |
| `GET` | `/scans/{scanId}/findings` | 风险列表，游标分页 |
| `GET` | `/findings/{findingId}` | 公告、严重级别与原始证据 |
| `GET` | `/findings/{findingId}/paths` | 最短引入路径与路径说明 |
| `GET` | `/projects/{projectId}/compare` | 对比两次扫描的组件与风险 |

组件查询结果的 `status` 为 `COMPLETE` 或 `INCOMPLETE`，`source` 标识查询来源，`queriedAt` 为查询时间，`error` 保存失败原因。查询证据与风险条目的 `evidenceJson`、风险路径 `pathJson` 以及审计的 `detailJson` 是 JSON 字符串，读取后按 JSON 解析。

版本对比请求携带 `fromScanId` 和 `toScanId` 两个查询参数。响应包含：

| 字段 | 含义 |
| --- | --- |
| `from` / `to` | 基线与目标扫描 |
| `riskComparisonComplete` | 两侧扫描是否均完成必要来源查询 |
| `addedComponents` / `removedComponents` | 新增与移除组件 |
| `addedRisks` | 目标扫描新增风险 |
| `resolvedRisks` | 完整查询下，目标扫描中已不再检出的旧风险 |
| `unresolvedRisks` | 查询不完整时，目标中未再次检出的旧风险 |
| `explanation` | 本次差异计算说明 |

### 审查与例外

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/scans/{scanId}/review` | 报告审查状态、绑定与并发版本 |
| `PUT` | `/scans/{scanId}/review` | 更新报告审查决定 |
| `GET` | `/scans/{scanId}/exceptions` | 当前扫描的例外列表 |
| `POST` | `/scans/{scanId}/exceptions` | 对一个风险条目申请限期例外 |
| `PUT` | `/exceptions/{exceptionId}` | 决定一条例外申请 |

提交报告审查前读取 `/review`，使用响应中的 `version` 作为 `expectedVersion`，并复制报告绑定字段：

```json
{
  "expectedVersion": 0,
  "sbomHash": "<sbomHash>",
  "reportHash": "<reportHash>",
  "policyVersion": 1,
  "decision": "APPROVED",
  "reason": "已核对风险与处置措施"
}
```

`decision` 取 `APPROVED` 或 `REJECTED`，`reason` 为 1–4,000 字符。`expectedVersion` 为当前审查记录的并发版本，与项目规则的 `policyVersion` 分别传递。审查针对最新扫描及当前规则版本的报告。

例外申请请求体包含 `findingId`、`reason`、`expiresAt`。例如，使用 PowerShell 生成两小时后到期的申请内容，`$findingId` 取自风险列表：

```powershell
$exceptionBody = @{
  findingId = $findingId
  reason = '已部署临时隔离措施'
  expiresAt = [DateTime]::UtcNow.AddHours(2).ToString('o')
} | ConvertTo-Json
```

`expiresAt` 使用未来 UTC 时间，且位于项目规则的 `exceptionDays` 上限内。申请由另一位 OWNER 或 REVIEWER 审批，请求体为：

```json
{"expectedVersion":0,"decision":"APPROVED","reason":"已验证隔离措施"}
```

例外决定针对有效期内、状态为 `PENDING` 的申请；其 `expectedVersion` 取自例外记录。报告与例外更新成功后递增各自的并发版本。

### 规则与门禁

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/policies` | 按版本倒序读取规则历史 |
| `POST` | `/projects/{projectId}/policies` | 创建新规则版本 |
| `GET` | `/releases/{releaseId}/gate` | 评估指定绑定的当前门禁 |
| `GET` | `/projects/{projectId}/audit` | 项目审计记录，游标分页 |

创建规则请求示例：

```json
{
  "expectedVersion": 1,
  "blockedSeverities": ["CRITICAL", "HIGH", "UNKNOWN"],
  "requireReview": true,
  "evidenceHours": 24,
  "exceptionDays": 7
}
```

这里的 `expectedVersion` 为项目当前规则版本。`blockedSeverities` 为非空集合，取值包括 `CRITICAL`、`HIGH`、`MODERATE`、`LOW`、`UNKNOWN`；`evidenceHours` 至少为 1，`exceptionDays` 为 1–7。成功后返回新规则对象。

门禁查询携带 `scanId` 与 `sbomHash`：

```text
GET /api/v1/releases/{releaseId}/gate?scanId={scanId}&sbomHash={sbomHash}
```

响应字段如下：

| 字段 | 含义 |
| --- | --- |
| `result` | `PASS`、`BLOCK` 或 `INDETERMINATE` |
| `reasons` | 评估结果对应的原因列表 |
| `binding` | `releaseId`、`sbomHash`、`scanId`、`reportHash`、`policyVersion` |
| `evaluatedAt` | 本次评估时间 |
| `validUntil` | 评估中的证据与例外截止时间；`PASS` 的有效截止 |

服务要求查询绑定对应当前版本的最新扫描与清单。CI 核对完整 `binding`，并在当前时间早于 `validUntil` 且结果为 `PASS` 时继续发布流程。决策优先级见[门禁计算](architecture.md#门禁计算)。

### CI 凭据

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/ci-credentials` | 读取凭据名称、创建时间与吊销状态 |
| `POST` | `/projects/{projectId}/ci-credentials` | 创建项目凭据 |
| `DELETE` | `/projects/{projectId}/ci-credentials/{credentialId}` | 吊销指定凭据 |

凭据由 OWNER 管理。创建请求体为 `{"name":"CI main"}`，成功返回 `201` 与 `id`、`token`。将创建响应的 token 保存到 CI secret；列表接口返回凭据元数据。吊销更新 `revokedAt`，后续 Bearer 请求按该状态校验。

## 分页与排序

项目、版本、组件、扫描、风险和审计列表使用游标分页，每页返回最多 50 条记录：

```json
{"items":[],"nextCursor":null}
```

有下一页时，`nextCursor` 为当前页最后一条记录的 ID，下次请求附加 `?cursor=<nextCursor>`。扫描列表按版本内递增的 `ordinal` 倒序，其他分页列表按 ID 字典序倒序。团队、成员、规则、例外和 CI 凭据接口返回数组。

## 错误响应

业务错误使用 `error` 字段，参数校验错误可在该字段返回错误列表。服务异常额外返回 `incident`，用于关联应用日志。

```json
{"error":"审查已被其他人修改，请刷新"}
```

```json
{"error":"服务处理失败","incident":"<incidentId>"}
```

| HTTP 状态 | 常见原因 | 处理方式 |
| --- | --- | --- |
| `400` | 字段校验、JSON、清单格式、操作人选择或业务参数错误 | 按响应修正字段及清单 |
| `401` | CI 凭据无效或已吊销 | 更新流水线使用的项目凭据 |
| `403` | 团队角色、项目范围、CSRF 或例外审批人校验失败 | 检查请求上下文、团队角色和 CSRF 信息 |
| `404` | 项目、版本、扫描、风险或例外不存在 | 从资源列表读取有效 ID |
| `409` | 标签内容冲突、幂等键冲突、旧绑定、并发版本或审批状态冲突 | 读取最新资源状态，更新绑定或并发版本后提交 |
| `413` | 上传清单超过 10 MB | 调整清单文件大小 |
| `429` | 每分钟扫描提交次数或项目活跃扫描达到配额 | 等待当前任务完成或进入下一计数窗口 |
| `500` | 服务处理异常 | 使用 `incident` 检索应用日志 |

回到[项目说明](../README.md)，或查看[架构与开发说明](architecture.md)。

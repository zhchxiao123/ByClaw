# ByClaw D0.6.0：保留前端、DSH 直连的数据路径调研

日期：2026-10-14。只读源码调研；未修改、构建或启动产品服务，未切换原工作区分支。

版本边界：本报告检查本地 `origin/D0.6.0` 对应提交 `bf6a9c54bf7456c29c2e7c19b6b9a13ed82de3bf`。远端刷新失败，因此不声明它是 GitHub 此刻的最新 tip。完整只读快照在 `/tmp/byclaw-direct-dsh-bf6a9c5`。以下所有 `文件:行号` 均针对该提交，而非原工作区 main。路径前缀简写：`J = byclaw-be/src/main/java/com/iwhalecloud/byai`。

## 结论

**保留 ByClaw 的页面与消息组件可行，但不能只把 WebSocket URL 改成 DSH。** 当前 Chat 有一套 ByClaw 事件协议、数字 ID、历史/运行快照和业务权限系统。建议在 DSH 的网络入口增加 ByClaw 兼容适配层：浏览器数据通道直接到适配后的 DSH；Java 提供账号、授权、会话元数据、模型/资源解析和连接票据；CubeSandbox 控制面负责实例唤醒、路由、租约与休眠。这个适配层不必通过 Java 转发逐 token 数据，也不需要 Redis Streams/NATS 消息中转。依据：`byclaw-fe/src/hooks/useSseSender/useSend.ts:69-81`、`chatStream.ts:62-133,135-188,230-233`、`byclaw-fe/src/hooks/useChat/index.ts:667-745`。

**可去掉的是 DSH Chat 的 MQ 传输链，不能因此直接删除 Redis 服务。** 当前 Redis 同时参与模型配置与会话覆盖、企业 Node 服务发现、授权流程状态、Skill 导入分布式锁、跨实例多端广播，并服务 OpenClaw/BY_SUPER/远程 ACP/DOC 等另一批工作流。这些须保留或独立迁移。依据见下方 Redis 分类表。

## 1. 当前消息路径与边界

当前普通浏览器链路：

```text
ByClaw React/Umi 页面
  -> 全局 WebSocket /byaiService/ws (LLM_MESSAGE)
  -> Java Netty ChatService
  -> AssistantChatService (权限/模型/业务上下文)
  -> RouteService -> GatewayClient / by-framework Redis
  -> BYCLAW_DSH_<userCode> 或 BYCLAW_EXE_<userCode> 等 Worker
  -> Redis Session Stream
  -> Java SessionStreamEventRouter / 快照与持久化投影
  -> CHAT_STREAM WebSocket -> FE 消息组件
```

逐段证据：

| 段 | 固定提交的源码证据 | 改造含义 |
|---|---|---|
| 浏览器连接 | `byclaw-fe/src/utils/websocket.ts:133-143,180-195` | 当前同域连接，query 含 beyond-token 和 scoped-delta-version；新路径需连接票据、代理地址和版本握手 |
| 全局事件消费 | `byclaw-fe/src/hooks/useGlobalChatRuntime.ts:38-60,86-130` | Chat 事件与 NOTIFICATION、TASK_PLAN_SNAPSHOT、SESSION_RUNTIME_STATUS 共享全局连接；拆 Chat 后不能丢通知 |
| 命令发出 | `byclaw-fe/src/hooks/useSseSender/useSend.ts:69-103` | 返回 promise 仅确认 WS 写出，cancel 本身仅本地关闭处理；真正 Stop 是其他命令 |
| Java 入口 | `J/state/domain/ws/handler/WebSocketHandler.java:114-151`；`.../ws/service/ChatService.java:81-122,163-186` | 服务端设定 senderId/senderName，接管额度、模型、Stop；直连必须承接这些服务端约束 |
| 请求准备 | `J/state/domain/chat/service/AssistantChatService.java:198-251` | session 权限、数字员工、群任务、个人模型/思考强度解析，不能让 FE 任意提交身份或资源 |
| Worker 路由 | `J/state/domain/chat/service/TargetAgentResolver.java:46-67,111-145` | HARNESS 是 DB 逻辑类型，运行时转换为 BYCLAW_DSH_userCode；保留资源语义但替换传输目标 |
| Gateway 发送 | `J/gateway/route/RouteService.java:223-296,745-821` | attachments、cwd、ASK_AGENT/RESUME、trace、answer ID、decorators、多 agent lane 已在这里加工；需抽出统一 turn preparation |
| Redis消费 | `J/state/domain/chat/service/SessionStreamManager.java:169-220,397-402` | 每 session listener、单活 lease、consumer group 和 sessionDataStream；新 DSH 路径不再使用这些消费者 |
| 结果投影 | `J/state/domain/chat/service/SessionStreamEventRouter.java:101-178,196-273` | 不只是转发：运行态、child session、后台结果、历史去重、终态快照都在这里 |

注意：`AbstractChatProcess.java:31-53` 对 `asyncResponse` / `sendByFrameworkMsgOnly` 提前返回，不应把注释的“同步完成后 storeMessage”当作所有 WS 模式的真实行为。迁移必须明确终态投影的负责人和事务提交点。

## 2. 保留 FE 所需的协议与恢复契约

FE 已有深度可复用的展示能力：`SSEMessageType` 中 text/thinkText、form/approval、toolCall、fileChanges/editDiff、askUserQuestions、compaction 等；对应 `util.ts` 将 JSON/tool 输出映射到消息块。这些不是 DSH 原生事件名的直接别名。证据：`byclaw-fe/src/constants/message.ts:9-60`、`byclaw-fe/src/hooks/useSseSender/util.ts:263-395`。

必须落实的映射：

| DSH/新网关能力（建议） | ByClaw 现有契约 | 建议处理 |
|---|---|---|
| 会话创建/标题 | createSession、initialization、sessionTitleUpdated | Java 元数据与 DSH Session UUID 的固定映射，生成一次并可重试 |
| 文本增量与终态 | answerStart/Delta/End、appStreamResponse | 保留当前消息块结构；正文结束与整轮终结分别表达 |
| 推理 | reasoningLogStart/Delta/End | 转为 thinkText；继续复用现有渲染 |
| 工具调用/编辑 | toolCall=3015、fileChanges=3016、editDiff=3017 | 固定 toolCallId/orderId 和生命周期；先定义兼容格式再接 FE |
| 问题/审批/恢复 | askUserQuestions、approvalForm、resumeMessageId/orderId | 请求票据需包含服务器签发的 pending action，不允许任意 resumeMessageId |
| 子会话/并行 | session、trace、laneId、turnId、agentId/name | 不能只依靠当前选中页面；保留全局运行态与 child hierarchy |
| 断线恢复 | runningStatus、runningSnapshot、snapshotStreamId | 替换为 DSH 快照 + journal cursor；读取历史和重新连接不能再次执行用户输入 |
| 已完成历史 | /assiman/getMessages 等 | 决定 DSH 原始日志为事实源、ByClaw SQL 为投影，或完整迁移成 DSH 查询适配；禁止两套可独立写入的正文事实源 |

证据：`chatStream.ts:62-188`；`util.ts:325-365`；`byclaw-fe/src/service/message.ts:9-34,83-110`；`useChat/index.ts:507-554,667-745`。

`compareStreamId()` 当前把 ID 按 `-` 拆为数字做大小比较，明显适配 Redis Stream 时间戳序列。不可塞入 DSH UUID 后继续沿用；建议增设 `runtimeEpoch + seq` 或明确 opaque cursor 比较/恢复规则，更新 live reducer 与 snapshot 水位线。证据：`chatStream.ts:191-207`；`useChat/index.ts:712-733`。

`websocket.ts:55-89,133-134` 已提供 Electron 注入本地 Chat WS、其他 HTTP 仍走 Java 的分离接缝，可作为“数据通道与控制面分离”的源码先例。但该提交不含实际 Electron 服务，因此只是 FE 注入能力，不能声称云端 DSH 直连已实现。`openClaw/openclawWebSocket.ts:231-241,319-336` 也已有直接 RPC chat.send/chat.history 客户端，但主 `useSend` 依然走统一 LLM_MESSAGE WS，不能把旁路客户端当成默认 Chat 路径。

## 3. Java 控制面必须保留的责任

1. **用户和企业边界**：WS 认证从 JWT 得到 LoginInfo；tenant switch 和每帧 enterprise 检查、membership 校验、session 订阅读权限均已存在。新票据应由这些校验导出 `(enterpriseId/userId/userCode/sessionId/resourceId/modelPolicy/runtimeEpoch)`，DSH 不信任浏览器声明。源码：`J/state/domain/ws/service/AuthService.java:56-96`；`.../handler/WebSocketHandler.java:188-261`。
2. **模型与额度**：月度 quota 当前在 ChatService；个人模型可见性、启用状态、Redis 配置、模型与思考强度覆盖在 SessionModelSelectionService。新 prepare-turn API 必须执行同等校验并服务器侧向 runtime 提供模型信息。源码：`ChatService.java:86-110`；`J/state/domain/chat/service/SessionModelSelectionService.java:103-170`；`AssistantChatService.java:235-239`。
3. **业务上下文**：资源占位符、group-chat-ref/cwd、request decorators、群组撤回屏障和任务 turn guard 影响实际执行。不要绕过它们后仅发送 chatContent。源码：`RouteService.java:230-246,745-821`；`AssistantChatService.java:213-246`。
4. **业务读写**：反馈、收藏、分享、group chat、消息任务状态均是平台 API；保留入口并处理 DSH 消息 ID 映射。源码：`byclaw-fe/src/service/message.ts:36-80`。
5. **企业分支**：企业 session 与企业 Node 的 generation、DB/runtime 身份校验已经是另一条路由。个人路径成功不能等价企业验收成功。源码：`J/manager/domain/tenant/TenantNodeClient.java:57-107`；`WebSocketHandler.java:235-260`。

安全修复建议（研究结论，未修改）：`AuthService.java:43-46` 当前日志直接记录解析出的 WS query，而 `maskSensitiveParams()` 在 `132-145` 定义却未用于该日志。新连接票据不要复用长期 token 的这种记录方式，部署前修正该入口的敏感参数日志。

## 4. Redis 哪些能从 DSH Chat 路径移除，哪些仍有依赖

| 用途 | 当前证据 | 本次可移除性 |
|---|---|---|
| DSH turn Worker 分发 | `RouteService.java:790-802`；`TargetAgentResolver.java:135-145` | 可以由 prepare-turn + DSH command 取代；现存 OpenClaw/BY_SUPER 不随之删除 |
| DSH token/event 回流 | `SessionStreamManager.java:169-220,397-402`；`SessionStreamEventRouter.java:83-178` | 可以由 runtime 直连流取代；快照/后台终态业务投影要有新机制 |
| Chat 跨 Java 实例 runtime/lease | `J/state/domain/chat/service/ChatRuntimeStateService.java:47-105` | DSH 迁移后改成 runtime ownership/fencing；旧运行路径暂留 |
| 多端广播 | `J/state/domain/ws/service/MultiDeviceBroadcastService.java:633-657` | 当前 PubSub 仍依赖 Redis；runtime 提供每 session 多订阅可替代 DSH Chat 的部分，但平台通知仍需审计 |
| 模型配置/override | `SessionModelSelectionService.java:87-95,125-170`；`byclaw-super/app/llm-provider/redis-llm-provider.ts:69-139` | 不属于消息队列，保留或独立搬迁到授权控制 API |
| 沙箱启动锁/忙心跳 | `J/gateway/sandbox/service/SandboxService.java:366-417`；`RunningStateRedisSubscriber.java:32-54,110-132` | Cube 生命周期应接管 DSH lease；旧 OpenClaw lifecycle 仍使用 Redis |
| 企业 Node 发现/授权 | `TenantNodeClient.java:57-107`；`J/manager/domain/connector/authorization/RedisAuthorizationSessionRepository.java:178-181` | 不在本次 Chat MQ 去除范围 |
| Skill 导入锁 | `J/common/config/SkillImportLockConfiguration.java:16-23` | 不在本次 Chat MQ 去除范围 |
| OpenClaw 收发 | `byclaw-exe/extensions/byai-channel/src/sdk-app.ts:397-422,457-515,726-764` | 仍为 GatewayWorker / WorkerRunner，不能顺手删除 SDK |
| 业务工具/DOC/远程 ACP | `byclaw-exe/extensions/baiying-enhance/src/executor/doc-gateway.ts:25-43,74-80`；`byclaw-exe/extensions/byclaw-acp-adapter/src/remote-claude-acp.ts:306-320` | 主 Chat 无 MQ 不代表调用这些工具时也无 MQ；要全量去除须另设计 executor transport |
| BY_SUPER 自身运行/交付 | `byclaw-super/app/worker/by-framework-delivery-state.ts:5-50`；`app/runtime/index.ts:112-139,359-376` | 独立 runtime 分支，保留范围需单独定义 |

这里没有深入 SDK 包的 wait-index/hash-slot 内部实现，因此不声称修改某个 key 即可消除所有 wait-index。确认的是 ByClaw Java GatewayClient 和 exe Worker/工具仍使用 by-framework SDK；SDK 内部机制由 harness/独立 SDK 研究补证。

## 5. CubeSandbox 不是 OpenSandboxClient 地址替换

当前 launch routing 用 resource workerAgentType 决定 openclaw/byclaw-code-agent/byclaw-dsh，用户沙箱 DB effective resourceId 常归一为 -1。源码：`J/gateway/sandbox/service/SandboxLaunchContextFactory.java:114-156`。

当前 lifecycle 的 idle cleanup 真实动作是 `doRemoveSandbox(record, AUTO_IDLE_TIMEOUT)`，租约概念是远端过期/本地 manual，不等于暂停恢复。源码：`J/gateway/sandbox/service/SandboxService.java:1395-1453`；`J/gateway/sandbox/model/SandboxLeasePolicy.java:6-26`。

已有可复用接口：`SandboxCommandExecutor` 提供 run/start/inspect/readOutput(cursor)/terminate；`SandboxIngressRuntimeSupport` 提供类型匹配、上游 URL 和请求定制。Cube 接入应扩展这些接口，不能把存量 fs/jobs/预览功能都捆到 Chat WS。源码：`J/gateway/sandbox/command/SandboxCommandExecutor.java:3-13`；`J/gateway/sandbox/service/ingress/SandboxIngressRuntimeSupport.java:6-45`。

文件下载也要迁移：`ChatFileArtifactApplicationService.java:131-146` 先验证 session、owner、path，再调用 `FsOperationApplicationService`；下载不能把浏览器 path 直接当沙箱任意文件访问。已有 artifact resolve 会复制不属于 session 的文件到会话命名空间，因此须保留 artifact 生命周期与 ID 映射。源码：同文件 `149-155`。Cube 的具体 checkpoint/restore/lease 保证由专门调研补齐，本文未重新验证真实 PVM。

## 6. 建议改动位置和迁移顺序（设计建议）

| 范围 | 改动接缝 | 目标 |
|---|---|---|
| FE transport | `utils/websocket.ts`、`useSseSender/useSend.ts`、`useGlobalChatRuntime.ts` | 抽 ChatTransport 接口，保留全局通知；DSH 连接由路由票据驱动，租户/会话切换关闭旧订阅 |
| FE 协议 | `chatStream.ts`、`util.ts`、`useChat/chatRuntime.ts` | 将 DSH snapshot/event 归一为现有渲染 DTO；换掉 Redis ID 比较；重试不再次发送 turn |
| FE recovery | `useChat/index.ts`、`service/message.ts` | history/runtime/snapshot 从统一适配 API 获取，仍保留平台业务读写 |
| Java turn preparation | `AssistantChatService`、`RouteService`、`TargetAgentResolver` | 抽取权限、model/resource/decorator/group guard 和 ID mapping；签发 scoped ticket；DSH 分支不调用 GatewayClient |
| Java projection | `SessionStreamEventRouter`、运行快照/任务投影服务 | 将纯投影逻辑与 Redis 消费器分离；runtime 可靠幂等回调/主动 journal reconcile 驱动 SQL 投影 |
| Cube runtime | sandbox command/fs/ingress/lifecycle 接口 | 处理 owner scope、runtimeEpoch、wake coalescing、lease、quiesce、checkpoint、resume，旧 provider 灰度保留 |
| 工具集成 | harness 业务插件，与 exe DOC/MCP/ACP 能力对应 | 明确 retained tools；先保留远程业务 Gateway，再逐个迁到授权 HTTP/MCP，无全量删除承诺 |

建议首个垂直切片：个人 HARNESS 账号 + 单 session + text/tool/reasoning + refresh/history/cancel + Cube 唤醒休眠，保持 OpenClaw/BY_SUPER/企业/group 分支原路由。随后交付附件/artifacts/skills/model/quota、child/parallel/jobs，再迁移企业/group/connector/定时后台入口。避免一次变更同时替换 UI、消息协议、history authority、全部 executor 与全部 sandbox provider。

必须验收的失败场景：双击重复 requestId、多标签同 session、切企业后旧连接残留、断线期间完成、冷启动并发、DSH restart 后 cursor/epoch 变化、Stop 与 terminal 竞态、工具未知副作用不重放、后台任务无浏览器仍投影、额度绕过、模型 secret 不下发浏览器、历史与 artifact 在沙箱睡眠时的行为。这里是验收设计，尚未实际执行。

## 7. 明确的研究边界

本报告提供指定提交的源码证据和设计推论。没有安装依赖、运行测试、调用真实模型、启动 OpenSandbox/CubeSandbox、验证 Grok 的实时产品行为，亦未完成新的 ByClaw -> Cube -> DSH 端到端验收。当前源码的旁路 OpenClaw WS、Electron 注入能力和历史恢复测试都不能证明新 DSH 直连部署已可用。

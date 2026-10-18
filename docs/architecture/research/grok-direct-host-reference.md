# Grok Bot 的直连 Host 模式：ByClaw / CubeSandbox 改造参考

日期：2026-10-14。方法：只读代码追踪；没有启动、安装、切换分支、注入故障或修改 Grok 源码。本文是迁移参考，不是 Grok 官方架构证明或 ByClaw 运行验收。

## 研究材料与边界

- Grok 重建仓库：`/Users/xiaozhongcheng/data/xiaozhongcheng2022/code/grok-bot-0.18-reconstructed`，当前 tracked HEAD `a9f633e09d49a85829b8236331b9e21f7e612634`。下方 `source/` 引用均属于此源码树，现场没有 tracked source 修改。
- 用户提供的 `docs/grok-bot-runtime-architecture.html` 是 untracked 分析材料，已读并与 tracked 源码交叉检查。其对服务器运行包的描述不是上述提交的事实；本次不把约 29 MB 的 `sand-host/host-main.cjs` 全量反编译结果作为正式验收。
- 仓库 README 明确它是非官方重建，新增了 inference router、Docker sandbox；`frontend/` 是部分重建，打包界面仍使用 hash 固定的原产品 renderer。因此桌面 router 实验不能等同原生远端 Host 行为。[S1]
- 按 ByClaw `AGENTS.md`，本设计文档保持未提交，没有 stage / commit。

## 最重要的结论

可以参考其 **控制面分配连接，消息直达有状态 Host** 的结构，去掉对话路径中的外部消息队列。但“直连”仍然有认证、路由和唤醒控制面：Grok renderer 不直接访问 Host，它通过本机 coordinator；coordinator 才连接 broker 返回的 gateway。[S2][S3]

对 Web ByClaw 推荐 `浏览器 → 同域认证入口 / 路由代理 → 用户对应的 DSH Host`。代理只做鉴权、租户范围校验、连接路由、流转发和唤醒，不持有第二套 turn 执行权；消息受理、历史、工具调用和取消由 DSH 决定。这仍符合“用户直连 DSH、无 MQ”的产品目标。真正 `浏览器 → PVM 原生端口` 需要另外实现 Web 身份令牌、Origin/CORS/CSRF、防泄漏、票据范围、过期和实例代次机制；现有 Grok gateway 明确拒绝带 `Origin` 的浏览器请求，并不是可直接复用的公开 Web API。[S4]

## 源码追踪的请求路径

1. `BrokeredHostConnector.connect()` 调 `ensureSandBox({})`；返回 `gatewayUrl`、`gatewayToken`、`networkToken`、VNC 地址，client 构造 gateway 描述符。账号 token 和 machine ID 从 broker 依赖获取，不在空 ensure 请求体中。[S2]
2. coordinator 经控制接口解析连接，`SandHostSupervisor` 合并当前代次的连接尝试；stream 仍活着就用旧连接，否则短 TTL health 探测，失效后再 resolve。[S3]
3. 请求通过 `POST {baseUrl}/api/{method}`；`sendPrompt` 返回 `{accepted:true}`，Host 缺省不等完整 turn。run loop 通过 Host 内部 `enqueueExclusiveRun` 排序，POST 断开不会等价于 turn 取消。[S5][S6]
4. 独立 `GET /events` 的 SSE 推送 transcript、agents、forever-box 等 channel。15 秒 heartbeat；client 有 15 秒 connect timeout、35 秒 stall watchdog、1–10 秒重连退避。[S7]
5. SSE connected 时 coordinator 重新 `listAgents`；Host replica 通过 `processEpoch + replicaKey + sequence` 表示进程内更新顺序，snapshot 带 `throughSequence`。[S8]

这里的 broker 是资源/凭证控制面，不是消息 broker；turn 的串行队列是 Host 内存中的并发管理，不是 Redis / NATS / Kafka 消息中间件。[S2][S6]

## 传输恢复不等于消息持久化

Grok SSE writer 只发 `data:`、retry 和 heartbeat；没有 SSE `id:`、`Last-Event-ID` 处理、持久 cursor 或断线事件补推。client 重新打开 `/events`，仅解析 data。**不能将其描绘为逐事件 exactly-once 或 durable event stream。** Snapshot 重同步与连接期间的 epoch/sequence 是另一种恢复设计。[S7][S8]

推荐 ByClaw 二选一，并写入协议：

- 首选：DSH 的持久 session journal 为权威，client 订阅后请求与 sequence 对齐的 snapshot，再合并 buffered events。缺序、epoch 变化、缓冲溢出都触发 snapshot resync；保留稳定 message / run / tool IDs。中途 text delta 可以通过完整内容快照收敛。
- 若业务要求所有工具输出、后台事件都逐条补读：在 DSH 权威日志内建立 durable event cursor，由 Host 自己按 cursor 查询；无需为了 cursor 再引入外部 MQ。只存在内存、或没有写 journal 的事件不能声称可恢复。

这些是对 ByClaw 的建议，不代表指定 DSH 分支已经具备能力。要特别检验先读 snapshot 后订阅的竞态、replay/live 合流的顺序、工具完成/错误终态和后台任务索引的持久性。

## accepted、nonce、ACK 的区别与不可照搬之处

Grok Host 的 `send-pipeline` 使用 clientNonce、inputDigest、in-flight coalescing 和 acceptance ledger；同 nonce 不同 digest 拒绝、已有记录返回重复受理结果。用户输入 echo 和 acceptance 记录在 dispatch 前进入持久状态，随后 enqueue turn，再 markAccepted。[S9]

但是这里不是严格持久受理证明：源码明确存在 DB 锁住导致 echo 非持久仍继续 send 的分支；acceptance ledger 用 temp+rename 写 JSON，写失败只记录 degraded 窗口而不把 send fail closed，也没有本文件的 fsync 保证。因此不能仅凭返回 `{accepted:true}` 推导掉电无丢失。[S10]

建议 ByClaw `202 Accepted` 的契约绑定到权威存储成功；幂等键至少包括 tenant/user/workspace/session/clientNonce，digest 覆盖正文、模型配置版本、附件和交互意图。未知提交结果先查 acceptanceStatus，禁止无条件换新 nonce 重新发送；工具副作用未知状态禁止自动重跑。持久化失败必须拒绝受理或明确返回非持久模式，不能仍显示“已发送”。

Grok 的 ACK obligation 表示“agent 欠用户可见回应”，有 send 时间、合并次数、redriveAttempts；`SendMessage` tool success 只说明 `onSendMessage` 返回，本段代码并未等待 renderer 收到或渠道送达回执。它们都不同于 HTTP 受理，也不同于消息队列 consumer ACK。[S11]

重建版 non-Cursor inference router 在 coordinator 内 `Map<agentId, Promise>` 排队后立即 accepted，而真正执行稍后才 append local JSON；local transcript 每 agent 最多 200 条，执行结果才写 assistant。该路径与远端 Host 的 acceptance ledger 不同，进程消失可失去尚未开始的内存队列。本次架构参考应采用 Host 路径，不采用这个 router 实验的受理语义。[S12]

## Host、工具沙箱、用户隔离与休眠

Grok `source/host/main.ts` 先拿 Host lock、创建 Host、启动扩展、最后开 gateway；可选择自己启动 exec-daemon 或用已存在 daemon。`LoopbackSandBox` 指向同机 exec-daemon，由 readiness ping 后构造工具 accessor。多 agent 的不同 window 仍共享同一盒子，不能以桌面窗口 owner token 代替 Linux/PVM 多租户隔离。[S13][S14]

Grok Host 数据有 `store.db` 和 `conversation-blobs.db`，SQLite WAL 需要专门 checkpoint；BoxStoreSync 捕获采用 agent DB bundle/manifest 和 per-agent capture queue。因此“把数据目录随便打 tar”不是正在写 DB 的一致性证明。[S15]

Host `/health` 忙碌不只看前台 turn：它包含 background shell、pending wake 和 drain revival，另区分仅等待 approval。现有 loopback `hibernate()` 仅清理 window cache，`runState()` 永远返回 running；tracked box-lifecycle 只有 run-state/image 查询和 recreate，**没有 Cube pause/resume、内存快照、闲置回收实现**。附带 HTML 的“断开后由后端休眠”是部署策略建议，不能据此直接关 Cube PVM。[S16]

对 CubeSandbox 的可迁移原则（设计建议）：

| 情形 | 控制面动作 | 持久/执行要求 |
|---|---|---|
| 首次 send、terminal、desktop、workspace file 操作 | 权限检查后 ensure；同 workspace 多 tab 合并一次启动 | 唤醒完成、DSH ready、持久状态恢复成功后接受执行 |
| SSE 断开/刷新 | 脱离一个 subscriber，保持 turn | 重连取 snapshot；不把客户端网络状态作为执行取消 |
| 全部 tab 离开但 turn / job / 上传仍在运行 | 保留 work lease | wait/heartbeat 续租；不能只看前台 isRunning |
| idle + 无 work lease | 进入 draining，拒绝新 admission；再次有请求取消 drain | checkpoint/flush 成功后允许 pause 或回收 |
| pause 后 resume | 同逻辑 workspace 重连，route epoch 更新 | 若保留内存可继续；若重建进程，旧工具句柄和审批/pty 需恢复或明确终止 |
| 请求落在休眠/恢复竞态 | single-flight ensure + generation fence | 不把已受理消息再执行一次；未知工具结果单独人工确认 |
| history / 下载既有产物 | 从持久投影/已发布存储读取，是否唤醒应明确策略 | 不因保存书签或页面 idle heartbeat 永久保活 |

可采用“一个用户/workspace 一台含 DSH+工具的 Cube”来最接近 Grok；优点是进程/文件/工具同位置且隔离直观，代价是每次冷唤醒包括 DSH、历史查询可能也要唤醒。也可保留常驻私有 DSH composition + 按需 B 工具 PVM，仍然不需要消息 MQ；但需明确 DSH→B 文件、进程和句柄的独立 lifecycle。哪个适合 ByClaw 需结合指定分支实际 sandbox adapter 和历史 API，不能仅凭 Grok 单盒布局决定。

## 安全和迁移验收必须补充

- 同域 gateway 对每个 session/文件/审批/取消做 tenant/user/workspace 授权；short-lived browser ticket 与后台 Cube/DSH admin token 分开，日志中不输出钥匙。
- 一个 logical workspace 只能有一个写入 DSH authority；Grok本机 PID lock 只能在单机检测，不是跨节点 writer fencing。Cube route epoch / lease owner 要做持久控制面约束。[S17]
- 断网中执行完成后恢复全内容；POST 已写入但响应丢失后查 nonce；DSH crash 后恢复终态；Cube idle 回收、cold restore、后台 shell/PTY/上传保活；双 tab 的 wake 和 cancel；账号切换及越权访问；checkpoint 失败不得 pause；未知副作用不得自动 replay。
- 不用 HTTP health、build pass 或单个页面截图替代上述运行验收。该研究只确认源码机制；没有验证真实 Grok 云 broker、真实 inference、沙箱热休眠、断电、一致性或 ByClaw 对接。

## 精确证据索引

所有 S 引用相对 Grok 仓库根；行号取上述 HEAD 的当前文件。HTML 引用另外标明 untracked。

| 编号 | 源码与行 | 支持的事实 |
|---|---|---|
| S1 | `README.md:5–25,38–64` | 非官方重建、router 增强、renderer 保留 |
| S2 | `source/electron-main/box/box-host-connector.ts:27–43,74–99,149–153` | broker ensure、descriptor、凭证角色 |
| S3 | `source/node-agent-coordinator/main.ts:90–94,144–153`; `gateway/host-supervisor.ts:93–110,135–160` | coordinator 控制连接、复用/health/single-flight |
| S4 | `source/host/gateway-server.ts:21–23,46–54` | bearer 检查、Origin 拒绝、API 路由 |
| S5 | `source/node-agent-coordinator/gateway/gateway-client.ts:246–265,348–385`; `source/host/host-gateway-api.ts:184–215` | POST 与 accept-return |
| S6 | `source/host/extensions/transcript/send-turn-dispatch.ts:148–185` | Host 内 enqueueExclusiveRun、accepted 和 detached turn |
| S7 | `source/host/gateway-server.ts:14,38–40`; `source/node-agent-coordinator/gateway/gateway-client.ts:38–44,397–477` | SSE heartbeat、退避、无 id/cursor/replay |
| S8 | `source/node-agent-coordinator/main.ts:119–141`; `source/host/extensions/transcript/replica-writer.ts:17–52` | reconnect roster、进程 epoch、snapshot throughSequence |
| S9 | `source/host/extensions/transcript/send-pipeline.ts:100–145,325–343`; `prompt-acceptance-ledger.ts:242–282` | nonce/digest、in-flight coalescing、ledger、ACK obligation |
| S10 | `source/host/extensions/transcript/send-pipeline.ts:320–324`; `prompt-acceptance-ledger.ts:382–415` | 非持久受理路径、ledger 写失败继续 |
| S11 | `source/host/extensions/transcript/sand-ack-obligation-store.ts:4–10,60–80`; `source/host/runner/tools/send-message-tool.ts:74–90` | ACK obligation 与工具 success 边界 |
| S12 | `source/node-agent-coordinator/inference-router.ts:59–77,121–143,169–184,212–228` | router 独立本地历史、内存排队早返回 |
| S13 | `source/host/main.ts:123–150,206–244` | Host/exec-daemon 启动边界 |
| S14 | `source/host/box/loopback-sand-box.ts:20–25,36–44` | 单机 endpoint、window、hibernate 缓存处理 |
| S15 | `source/host/extensions/session/session-paths.ts:9–16,27–29`; `source/host/storage/store-db.ts:3–15`; `source/host/extensions/box-store-sync/store-db-bundle-capture.ts:127–146`; `store-db-capture.ts:39–58` | DB authority 和一致 snapshot seam |
| S16 | `source/host/sand-host.ts:665–686`; `source/host/box/loopback-sand-box.ts:44`; `source/host/extensions/box-lifecycle/box-lifecycle-service.ts:1–2`; untracked `docs/grok-bot-runtime-architecture.html:593–607` | busy、休眠实现范围、HTML Web 建议 |
| S17 | `source/host/host-lock.ts:6–19` | 仅本机 PID lock/takeover，非分布式 fencing |

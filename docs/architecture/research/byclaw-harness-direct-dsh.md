# ByClaw Harness 直连 DSH 与 CubeSandbox 改造调研

日期：2026-10-14。本文是代码调研，不是运行验收；未启动、安装、部署或修改运行代码，未切换现有 checkout。文档按仓库规则保持未提交。

## 证据范围与结论

- Harness 指定分支本地快照：`origin/2026.9.8`，`1fd4c59e3a97f03e19f40bd32eb6e819d91f42ea`。远端 fetch 失败，分支最新 tip 未在线确认。下文 H 路径均相对此快照；分析使用 `/tmp/byclaw-harness-research-1fd4c59` 的 Git archive，不读取 main 的实现冒充指定分支。
- 上游 DSH 本地只读源码：`/Users/xiaozhongcheng/data/xiaozhongcheng2022/code/dsh/deepseek-harness`，clean HEAD `fb2c4b9e698e30edb738bca4cf0618587db7d203`，等于本地 `dsh-v0.1.5-rc.2` tag。下文 U 路径相对此源码。
- Harness 是插件与镜像组装仓库，核心 DSH 从基础镜像继承；默认构建引用上游 `dsh-v0.1.5-rc.2`，不是按 Harness 分支名字选择 DSH 版本。README 仍写旧兼容版本，部署应以镜像构建配置和实际包清单为准。[H1][H2]
- 上游该版本已有 HTTP Remote RPC、WebSocket 多路流、历史分页/补读、客户端序列校验/重连和 requestId 去重。可以保留 ByClaw UI，接入已有 DSH 传输能力，不必另写一套完整消息流服务。[U1][U2][U3]
- 不能把当前 ByClaw SSE 地址直接换成原生 DSH 地址即宣布完成。现有业务入口还负责编排身份、员工/专家组选择、模型、附件上下文、任务范围、交互问答与卡片投影，部分只在自定义 SessionRuntime 中发生。[H3][H4][H5]
- 按当前插件实现，第一阶段让同一用户的 DSH、工具、browserd/Chromium/noVNC 在一个私有 Cube 中，整体暂停/恢复，比马上拆成共享 DSH Host 与远程工具沙箱更贴近代码。整体暂停的内存保持、外部连接重建、计时器行为必须做 Cube 真实探针；冷启动/删除重建是另一种恢复协议。[H6][H7][U4]

## 现有消息入口与可复用模块

当前路径：`ByClaw Gateway/Redis → ByClawDshGatewayWorker → ByClawDshSessionRuntime.ask → DSH Agent → GatewayDataEmitter → Redis`。Worker 只是命令适配器，`ByClawDshCommandHandler` 已把命令分派与 Redis 生命周期分开，支持 AskAgent、Resume、Cancel；适合作为拆分起点。[H3]

`ByClawDshSessionRuntime` 已做以下业务工作，应保留语义，拆除传输耦合：[H4][H5]

| 能力 | 当前入口/数据 | 改造要求 |
|---|---|---|
| 外部会话到 DSH root 绑定 | `session-metadata.ts`；外部 session、template binding、createdAt、cwd | 保留不可变身份与 cwd 校验；不能仅凭浏览器提交的 dshSessionId 访问 |
| 员工、专家组、群任务 | `resolveInboundTarget`、`groupCoordination`、template runtime | 由 ByClaw BE 签发/查询权限与冻结任务范围，DSH 入口验证；浏览器不能伪造 coordinator/allowedAgentIds |
| 本地多 Agent | AgentTeams + DSH root/child session | 可继续在单个用户 Cube 内运行；保留 session lineage 和 child turn/run identity |
| 等待用户回答 | `ByClawQuestionBroker` + Resume + `dshInteraction` | 直连提供显式 interaction response；刷新重连应展示原卡，冷重启需持久化待答状态或明确失效 |
| 取消 | messageAgents/teamGate/Agent cancel | 取消要作为独立控制操作；关浏览器或断流不等于取消 |
| UI 卡片投影 | `byclaw-presentation.ts`、`protocol.ts` | 保留现有 contentType 与 eventId/scope/turn 关联；把 GatewayDataEmitter 依赖换成抽象输出端口或共享投影函数 |
| 插件业务扩展 | `byclaw/session-setup` | 继续在输入被接纳之前 await；群任务发布插件依赖此事件 |

可优先把 Integration 拆成资源/授权/模型、业务 SessionRuntime、Direct Remote ingress、Legacy Redis worker 四个组合模块。业务模型中的 `AskAgentCommand`/`AgentContext` 也要抽出可信请求类型，避免只换 emitter 后仍暗中依赖 worker cancellation、trace、execution status。[H3][H4]

### 原生入口已有部分员工支持，但不覆盖全部业务路径

`employee-selector` 将授权员工投影到 DSH 的正式 agent preset，binding 在 root 创建及 pre-step 时绑定员工并检查授权；这是保留原生能力的可复用桥，不是所有 ByClaw 业务任务的替代。`ByClawDshSessionRuntime.handleFor` 另外安装任务计划、ask_user_question、workspace policy、setupTodoExecution，处理专家组/任务范围与输入文件；原生 `session.create/prompt` 不会自动生成 `AskAgentCommand` 并调用这套代码。[H5][H8]

建议新增专用、鉴权后的 `byclaw` Remote namespace，复用上游 Typert Gateway 与 Remote mux。其 admission 先处理可信业务 setup，然后使用现有 Session/Agent 服务；原生 session follow/page/control 和共享投影层负责读与 UI 状态。具体 API 名字属于后续设计，不能当成当前已经存在的接口。

## 原生协议与恢复边界

上游入口是 HTTP `/api` RPC dispatch 与 WebSocket `/api/remote.mux`；HTTP 使用 `client-request`/`server-response` envelope，Remote method 根据 registry 生成，流通过 open/cancel 等 mux frame。应复用导出的 client 包与生成 Remote，不手写一个看起来相似的 SSE 协议。[U1][U5]

现存 Remote 方法包括 `session.list/search/create/selectModel/modelCatalog/rename/fork/prompt/updateQueue/cancel/page/follow/control`，以及独立 attachment/file-upload 能力。Prompt 使用业务 `requestId`（与 carrier rpcId 不同），历史 follow 按 durable Session address 获取 opening snapshot，再接连续 seq；page 用该 opening 的 throughSeq 取向后分页。[U2][U3][U6]

`@deepseek-ai/dsh-client-connection/client`、`@deepseek-ai/dsh-api-gateway/client`、`@deepseek-ai/dsh-api-session-controller/client` 有浏览器 client 导出；session client 中有 `SessionEventStream`、`createSessionControlStream`，源码不要求导入 DSH 原生 UI/React 组件。ByClaw Umi React 页面可在自己的状态层消费这些对象。包发布/打包、Cordis Client composition、同源反代 base path 和 registry 类型产物兼容性还需 spike；源码有导出不等于 ByClaw 当前构建已可直接打包。[U7]

必须分开以下承诺：

1. **接纳**：native prompt resolve Agent，检查 requestId 是否已在 inbox 或 user/message log 中，随后 followup/steer，并返回 accepted。这里没有显式等待 fsync 的 admission receipt；进程内排队但未入持久日志的输入，冷重启存在不确定性。[U3]
2. **持久输出**：JSONL backend append 完成时是 durable；通用 Persistence contract 要求显式 flush 才能统一承诺 crash survival。不能把观察到 session/event 或第一帧渲染等同于 durable append 已完成，更不能把 accepted=true 当成最终回答已存盘。[U4]
3. **实时预览**：assistant stream frame 是 process-local/cursorless；durable assistant/message 或 assistant/attempt 才能恢复规范输出。Client 使用 live frames 的索引/版本与 durable settlement 协调，重连开 snapshot、补 page 并拒绝 seq gap。[U2][U8]
4. **控制状态**：queue/jobs/projections 走独立 control snapshot stream，每代连接替换 baseline；不是把临时任务状态永久存在 WebSocket 中。native control 属于 Host 级服务，单个 Host 私有给一个用户是当前最简单边界；多用户共享 Host 不能直接广播整个 baseline。[U8]
5. **客户端断开**：follow 结束只释放监听器；prompt 只在 admission 开始前检查 carrier signal，Agent 后续执行不绑定 socket lifetime。显式 session.cancel 才调用 Agent cancel，且 native cancel 默认保留 pending inbox。需在 UI 区分“断线重连”“停止当前轮次”“移除排队消息”。[U9]
6. **冷重启**：Session 日志与 plugin storage 可恢复 root identity；`ByClawQuestionBroker.pending`、SessionRuntime queues/active/messageAgents、GenerationLease 都是进程本地。暂停保留内存与重建进程不能混为同一个验收用例。[H4][H9]

建议直连入口新增 durable request ledger（scope + requestId + payload digest + accepted status + journal position），同 requestId/同内容返回原接纳，改内容拒绝冲突；unknown side effect 的工具不能自动重放。具体能否只用现有 SessionPersistence.flush 与输入日志扩展完成，应通过 kill-before/after-ack 探针决定，暂不声称上游 requestId 去重已覆盖所有崩溃窗口。

## 权限与 Redis 去留

DSH Web auth 是启动 token → 持久签名 cookie exchange，cookie payload 是 authority/时间戳；没有 ByClaw tenant/user/workspace/role。Integration 可配置固定启动 token，也能关闭 API/WS token/cookie 校验但保留 Host/Origin。面向用户直连时必须由 ByClaw 登录权限换取单用户 Runtime ticket/代理绑定，不能把一个通用 token 或 auth=false 当成多租户授权。[H10][U10]

建议同源网关路径绑定平台用户 → runtime → epoch，先授权再 wake/route；所有 HTTP/WS、文件、noVNC 通道走同一 ownership 校验。网关只负责认证、路由、wake 和连接代理，消息不再经 worker queue。内部 DSH token 留在私有网络/受控网关，不在页面配置下发 admin key。ticket 过期、平台退出、权限撤销、Runtime epoch 变化要能关闭连接及拒绝旧控制命令。

**去掉消息队列不等于现在能删除 Redis。** Integration activate 必须 createRedis/ping；资源授权、员工组快照、模型/token解密、backend discovery、资源变化订阅、sandbox busy snapshot、Bash 运行环境和部分 build coordination 都依赖 Redis。模型 router 甚至要求 Redis model mode。拆分方案可以先保留 Redis 作业务缓存/授权配置，去掉聊天 Stream/Worker 路径；若最终要求部署完全无 Redis，再将这些边界替换为 BE API 或版本化配置和直接 lifecycle API。[H11][H12]

专家组还能派发非 Harness 的 BYCLAW_CODE/BYCLAW_EXE/企业 worker：`ByClawExternalWorkerAdapter` 调用 GatewayClient 并 xread 子 worker stream，不会因聊天直连自动消失。必须明确第一阶段是否保留这些 executor 的 legacy queue，或者另做 HTTP 任务接口；不允许静默改成 DSH worker 导致员工执行引擎变更。[H13]

## CubeSandbox 的具体适配面

当前运行镜像含 Node/Python/Java/办公工具/browserd/Chromium/Xvfb/x11vnc/noVNC/bycli。entrypoint 先由 root supervisor 启动私有浏览器与 DSH，再执行上游 CLI；浏览器通过 UNIX socket + supervisor FD secret 连接，不是简单外部浏览器 HTTP URL。默认 shared-novnc 在同一用户运行环境中共享 persistent Chromium profile；legacy novnc 提供 per-session desktop。转成 Cube 模板应保持这些程序的生命周期与 UID/GID、socket 权限、下载/截图目录、bycli扩展/daemon 授权链。[H6][H7]

现有 Bash provider 在原生 sandbox executor 上增加 RTK/OOM/build-slot与完整 parent environment forwarding，然后再叠加 Redis 用户环境。若改成常驻共享 Host +远程 Cube，不能直接保留“完整 Host env 转发”，否则把共享控制面的密钥带给工具；同用户私有 Cube 也需要以进程角色/凭证范围审计。这是具体代码边界，不是已有 Cube adapter。[H12]

| 生命周期动作 | 第一阶段处理 | 必须验证的事实 |
|---|---|---|
| 新建/唤醒 | BE/runtime gateway 合并并发 wake；ready 包括 DSH API、业务资源和 browserd，不只 VM RUNNING | 模板镜像与 base tag digest、插件 patch 生效、全部端口/代理可用 |
| 进入聊天 | 读 metadata 可在 BE；真正操作 DSH 或文件时确保 runtime ready | 打开历史是否需要 wake，UI 明确显示唤醒过程 |
| active Agent | run/child/queued/subprocess/job/browser/transfer 持有 controller lease | 多个会话共同计数，不能单凭一个会话 idle 休眠 |
| 用户等待 | 现有 busy publisher 认为 waitingInteraction busy | 初期阻止休眠；支持暂停待答后再放开；冷重启待答另做持久恢复 |
| 关闭页面 | 释放 viewing lease，不取消 Agent；网络断连不无限续租 | 后台任务继续、重新连接可补读、显式取消有效 |
| idle 暂停 | drain/gate admissions + flush +检查控制 leases，再 Cube pause | 权限poll/Redis socket/VNC/browser/bycli断线与超时重建；暂停计时器不误判过期 |
| 删除重建 | 初期不作为自动 idle 路径 | durable home、session/plugin storages、credentials、workspace、browser profile、任务文件、artifact 全部恢复；process handles作废/epoch fence |

现有 sandboxBusy 只是 process activity snapshot 的 Redis publish：聚合 running 或 waiting interaction，写失败只告警，不是强制 controller lease。GenerationLease 是资源 generation 的进程读写锁，不是 Cube 生存租约。需要新 RuntimeLeasePort，以有 owner、TTL、heartbeat、epoch/fencing 的 lease 取代“最近看到 busy=false 就删除容器”。尤其后台 shell 可能已经退出 Agent turn 仍在执行，单看 activeAgentCount 不能证明所有 OS 子进程静止。[H14][H9][H12]

## 最先完成的受控探针

1. 用固定版本插件镜像/实际模板启动一个私有 Cube，采集 runtime upstream commit、包清单、model reassembly capability、web API/Remote schema、UNIX socket readiness；确认基础镜像 digest，不能仅凭镜像 tag 或 README。
2. 保留 ByClaw 页面，接 native client transport：文本/思考/工具/子Agent/计划/文件diff/交互问答完整映射；一次断线重连不重复投影，历史 pagination 不漏不重。此探针先采用 deterministic model，随后真实商业模型。
3. 在 prompt admitted 前后、durable user/message 前后强杀进程，验证 requestId ledger；区分“没被接纳”“已存盘可继续”“工具结果未知需用户处理”。
4. browser执行、VNC查看、后台命令、问答waiting、两个session同时运行下测真实 Cube pause/resume；外部连接恢复不影响工具副作用，运行lease和sleep互斥。
5. 做平台用户A/B、跨runtime、过期ticket、旧epoch、员工授权撤销、群任务范围、文件path/noVNC隔离；上游 Host/Origin checks 不能替代业务授权。
6. 保留/替换 Redis依赖按能力分别切断验证：先只停 chat worker stream，确认模型/资源/业务回调仍正常；再选择完全无Redis改造范围。

上述全部是后续验收计划，本次只检查源码与已有测试断言。仓库包含 headless create+cold-resume deterministic probe，但未在本轮运行；该测试不含真实Cube、Remote网关、Redis/平台登录或浏览器VNC恢复。[H15]

## 源码索引

源码路径与行号均对应本文开头 pin；Harness 和上游是独立版本，不把主 checkout 当前 branch 作为证据。

- [H1] `Dockerfile.base:210-224`：上游仓库、tag、镜像label；`README.md:214-218`：旧兼容版本与现有探针说明。
- [H2] `Dockerfile:41-47,65-76,191`；`scripts/prepare-dsh-model-routing-runtime.mjs:13-15,17-62`：0.1.5-rc.2 runtime compatibility patch 和打包。
- [H3] `plugins/byclaw-integration/src/worker.ts:17-85`；`worker-runtime.ts:44-100`：session port、Redis worker分离点。
- [H4] `plugins/byclaw-integration/src/session-runtime.ts:1255-1382,1522-1567,1824-1990`：process queues/active、Resume/cancel、rootsetup、工具和冷resume。
- [H5] `plugins/byclaw-integration/src/index.ts:944-1001`；`session-extensions.ts:8-35`；`session-metadata.ts:41-48,77-174`：业务SessionRuntime、setup扩展、durable binding。
- [H6] `Dockerfile.base:56-90`；`Dockerfile:116-133,250-272,288-291`：工具链、browser/VNC/bycli环境、entrypoint。
- [H7] `docker-entrypoint.sh:4-20,55-91,103-127`；`plugins/dsh-browser-use/src/index.ts:540-548`：supervisor、persistent profile、socket/FD secret。
- [H8] `plugins/employee-selector/src/index.js:15-42`；`binding.js:4-21`：preset投影与root身份/pre-step授权。
- [H9] `plugins/byclaw-integration/src/protocol.ts:234-274`；`generation-lease.ts:42-48`：process-local question rendezvous、generation admission。
- [H10] `plugins/byclaw-integration/src/web-auth.ts:9-16,81-105,109-141`：固定token/关闭auth的实际范围。
- [H11] `plugins/byclaw-integration/src/index.ts:478-501,557-564,686-729,944-1001`；`resource-authorization.ts:65-78`：userCode、Redis模型/资源/登录授权和busy依赖。
- [H12] `plugins/dsh-bash-provider/src/index.ts:116-131,436-475`：后台任务guard限制、parent env、Redis environment。
- [H13] `plugins/byclaw-integration/src/group-worker.ts:18-23,87-96,98-145,155-169`：冻结执行引擎、Gateway派发与Redis xread。
- [H14] `plugins/byclaw-integration/src/sandbox-busy.ts:51-65,140-174`：activity推导、Redis publish与失败处理。
- [H15] `plugins/byclaw-integration/scripts/current-dsh-e2e.mjs:140-164,181-224,245-249`：已有测试范围，未本轮执行。
- [U1] `packages/api/gateway/src/index.ts:192-228`；`stream-protocol.ts:5-6`：`/api`与`/api/remote.mux`。
- [U2] `packages/api/session-controller/src/history.ts:77-117,119-230`；`src/client/transport.ts:1-30,109-130`：分页/follow、snapshot、sequence连续性。
- [U3] `packages/api/session-controller/src/commands.ts:302-375,584-594`：prompt接纳、requestId inbox/log dedup。
- [U4] `packages/session/session-persistence/src/handle.ts:86-109`；`packages/session/session-persistence-jsonl/src/storage.ts:183-207,318-320,503-522`：通用flush与JSONL durable append contract。
- [U5] `packages/client/connection/src/client/rpc.ts:35-59,108-118`：HTTP RPC envelope与target验证。
- [U6] `packages/api/session-controller/src/index.ts:233-264,324-410`；`types.ts:437-450`：Remote方法、page/follow参数。
- [U7] `packages/api/session-controller/src/client/index.ts:1-17`；`package.json:16-38`；`packages/api/gateway/package.json:16-24`；`packages/client/connection/package.json:16-24`：UI独立client exports。
- [U8] `packages/api/session-controller/README.md:29-33`；`src/history.ts:54-63,163-201`；`src/client/transport.ts:80-130`：cursorless预览、journal settlement和control baseline。
- [U9] `packages/api/session-controller/src/index.ts:339-349`；`commands.ts:493-510`；`history.ts:232-237`；`packages/api/gateway/src/stream-server.ts:136-137`：carrier取消与Agent取消分离。
- [U10] `packages/client/connection/src/browser-auth.ts:27-32,120-122,180-215`：authority cookie和persistent secret，不含平台用户scope。

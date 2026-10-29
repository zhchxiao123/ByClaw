# 工具沙箱制作规范

工具沙箱（B）是用户的命令真正执行的地方：DSH 宿主（A）上的每个用户上下文，经 SSH 把 bash、读写文件、
浏览器等工具调用转到该用户自己的 B。默认所有用户使用同一个 B 规格（`byclaw-dsh-tools`）；需要特殊环境的
用户（例如游戏引擎、专用 SDK、带许可证的商业软件）可以使用自制的 B 镜像，通过系统参数
`BYCLAW_TOOL_SANDBOX_BY_USER` 只分配给指定用户。

本文说明自制 B 必须满足的约定、如何注册和分配，以及上线前的验收清单。

## 1. 镜像

**必须基于与宿主配套的 tools 镜像构建**，在其上加自己的软件：

```dockerfile
ARG TOOLS_IMAGE=<与当前宿主 A 镜像配套的 tools 镜像>
FROM ${TOOLS_IMAGE}
USER root
RUN apt-get update && apt-get install -y --no-install-recommends <你的依赖> && rm -rf /var/lib/apt/lists/*
COPY ... /opt/<your-tool>/
```

- tools 镜像是从对应的 A 镜像构建出来的（`Dockerfile.tools` / `Dockerfile.tool_slim`），里面的 SSH helper 与 A
  按摘要校验。**不要从其他基础镜像重做**，也不要删改 `/workspace`、`/opt/byclaw/dsh-managed` 下的内容，
  否则 A 连不上 B。A 升级后，自制镜像要基于新的 tools 镜像重新构建。
- 用户命令以 `byclaw`（UID/GID 1001）运行，安装的文件要对该用户可读、可执行（`chmod -R a+rX`）。
- 系统是 tools 镜像的发行版（当前为 Debian 13）。只提供其他发行版安装包的软件，要先确认依赖能装上
  （用 `ldd` 检查主程序没有 `not found`，并在 `RUN` 中断言，让构建直接失败）。
- 给 agent 用的环境变量（例如 `MYTOOL_HOME`）除了 `ENV`，还要写进
  `/etc/byclaw-tool-env.session-env`（每行 `NAME=VALUE`）。SSH 会话从这个文件取环境，不读镜像的 `ENV`。
- 大文件（几 GB 的 SDK）单独放一个下载阶段，后续修改不必重新下载；体积大、多人共用、只读的安装目录，
  也可以不放进镜像，改由规格挂一个只读卷。

## 2. 启动

规格的 `startup.entrypoint` 默认是 `["/usr/local/bin/byclaw-dsh-entrypoint", "tools"]`，它启动 sshd、
WebSocket 桥和浏览器监督进程。需要常驻服务（桌面、许可证守护进程等）时，写一个自己的入口脚本：
先以 `byclaw` 用户在后台启动服务，**最后 `exec byclaw-dsh-entrypoint "$@"`**，并把规格的入口改成它：

```sh
#!/bin/sh
set -eu
su -s /bin/sh byclaw -c "HOME=/home/byclaw nohup /usr/local/bin/my-services >/dev/null 2>&1 &" || true
exec /usr/local/bin/byclaw-dsh-entrypoint "$@"
```

- 自己的服务启动失败不能阻止 `byclaw-dsh-entrypoint` 运行（上例中的 `|| true`），否则整个 B 不可用。
- 服务需要自行守护（退出后重启），B 不会替你拉起。

## 3. 端口

| instance | 端口 | 必需 | 说明 |
|---|---|---|---|
| `ws-bridge` | 8080 | 是 | A 经 WebSocket 桥连 sshd；规格 env 中的 `BYCLAW_TOOL_ENV_WS_PORT` 与之一致 |
| `ssh` | 22 | 视运行环境 | 能直连 TCP 的环境用；`BYCLAW_TOOL_ENV_PORT` 与之一致 |
| `vnc` | 任意 | 否 | 在会话的“远程电脑”抽屉里显示的桌面，见下文 |
| `bycli` | 19825 | 否 | 沿用默认规格即可 |

**桌面（`vnc`）**：前端在用户运行中的沙箱记录里找 `instanceEndpoints.vnc`，用 iframe 打开
`/v1/sandboxes/<id>/proxy/<port>/`。该端口要：

- 在根路径提供可直接打开的页面（noVNC 的 `vnc.html` 需要一个 `index.html` 跳转过去）；
- WebSocket 地址要从页面路径推算，因为页面挂在代理前缀下，例如
  `path = location.pathname 去掉文件名 + "websockify"`；
- x11vnc 只监听 `localhost`，对外只暴露 websockify；鉴权由代理负责。

一个沙箱只有一个 `vnc`。默认规格的 `vnc` 显示浏览器；自制镜像若把 `vnc` 给了自己的桌面，浏览器画面
就不再出现在抽屉里。

## 4. 目录与持久化

| 路径 | 持久 | 说明 |
|---|---|---|
| `/by` | 是，按用户 | 用户工作区，与宿主上看到的是同一份；项目、产物放这里 |
| `/home/byclaw` | 是，按用户 | 工具沙箱的 home（规格卷 `byclaw-${user_code}/tool-home`），配置、缓存、许可证放这里 |
| 其余 | 否 | B 回收或重建后丢失 |

B 空闲一段时间后会被释放，下次工具调用时重建，所以运行时状态必须写在上面两个目录里。agent 用
`cmd &` 留在 B 上的进程会让 B 保持忙碌，最长 `BYCLAW_TOOL_ENV_BUSY_PROCESS_MAX_MS`（默认 6 小时）。

## 5. 给 agent 的说明（Skill）

agent 不知道你的镜像里有什么。把用法写成一个 Skill，在启动脚本里复制到
`/home/byclaw/.dsh/skills/<name>/SKILL.md`：

```sh
su -s /bin/sh byclaw -c "mkdir -p ~/.dsh/skills/my-tool && cp -R /opt/my-tool/skill/. ~/.dsh/skills/my-tool/"
```

Skill 写清楚：什么时候用、命令行入口、典型流程、日志位置、常见错误。工具最好提供面向 agent 的命令行
（输出简短、错误信息可读、退出码有意义），而不是要求 agent 操作图形界面。

用户的上下文在 B 首次启动之前就已加载时，第一轮对话的 Skill 列表里还没有这个 Skill（agent 仍可直接读文件），
之后的会话正常。

## 6. 第三方软件的许可证

- 许可证文件、登录状态放在 `/home/byclaw` 下，B 重建后仍然有效。
- 许可证若绑定机器标识（例如 `/etc/machine-id`），在镜像里写死该标识，否则每次重建都被视为新机器。
- 需要定期联网续期的许可证，要有一个常驻进程在 B 运行期间负责续期；B 长期未运行时，要说明过期后
  如何恢复。
- 凭据不要写进镜像。按用户提供的放进该用户的 home；所有用户共用的，通过规格挂只读卷或规格 env 提供。
- 确认许可证条款允许以这种方式给多人使用。

## 7. 注册与分配

1. 在 `byai.sandbox_service_spec` 新增一行：复制 `byclaw-dsh-tools` 的 `spec_json`，改 `image`、需要时改
   `startup.entrypoint`、`ports`（`vnc`）和 `resourceLimits`；`enabled = 0`（工具沙箱不出现在登录启动的选择里），
   `display_name` 写人能看懂的名字。`env` 中的 `BYCLAW_TOOL_ENV_*` 和 `volumes` 保持不变。
2. 系统参数 `BYCLAW_TOOL_SANDBOX_BY_USER` 写 `userCode=service_key`，多条用逗号、分号或空白分隔，例如
   `u1=byclaw-dsh-unity,u2=byclaw-dsh-unity`。只对共享宿主上的用户（`BYCLAW_SHARED_DSH_HOST_USERS`）生效。
3. 生效时机：用户上下文启动时读取。改参数后，用户需要“重启助理”（或等宿主回收后再进入）。
4. 切换前先释放该用户正在运行的旧工具沙箱。新旧 B 共用同一个 home，旧 B 未释放时，新 B 的浏览器监督进程
   因 profile 被占用而退出（日志 `browser-supervisor-profile-in-use`）。

## 8. 验收清单

- [ ] 镜像基于当前 tools 镜像构建，主程序 `ldd` 无缺失。
- [ ] 指定用户发起一次需要执行命令的对话：B 以新规格启动（`docker ps` / 沙箱列表中是新镜像），agent 能执行命令。
- [ ] 其他用户仍使用默认工具沙箱。
- [ ] agent 能看到并按 Skill 使用你的工具，完成一次典型任务。
- [ ] 有桌面的：会话里能打开“远程电脑”，看到并能操作桌面。
- [ ] 让 B 空闲回收后再对话：B 重建，`/by` 和 home 中的数据、许可证仍然有效。
- [ ] 资源：典型任务在规格的 CPU/内存限制内完成，宿主机磁盘能容纳镜像。

## 附录：Unity 工具沙箱（样例）

一个按本规范制作的工具沙箱，镜像不在本仓库维护，要点供参考：

- 镜像：tools 镜像 + Unity Linux 编辑器（国内从 `download.unitychina.cn` 下载中国版，版本号带 `c1`）+ Unity Hub；
  按 GameCI `unityci/editor` 的做法补运行库、写死 `/etc/machine-id`、声卡指向 null；无 GPU 时用 Mesa 软件渲染
  （约 30 FPS@1280×720）。
- 启动脚本：启动游戏桌面（Xvfb + openbox + x11vnc + websockify，作为 `vnc`）和隐藏显示上的 Unity Hub
  （带用户的登录状态，负责个人版许可证续期），再 `exec byclaw-dsh-entrypoint`。
- 面向 agent 的命令：建项目、构建、运行、停止，以及一个读取游戏状态、点击、按键、截图的命令；
  游戏内置一个只监听本机的通信组件；Skill 说明整个流程。
- 许可证：个人版无法命令行或网页手动激活，需要在 B 的桌面里用 Hub 登录激活一次，许可证绑定写死的
  machine-id，之后放在用户 home 中复用。
- 踩过的坑：桌面上没有窗口焦点时 Unity 游戏默认暂停（构建时设 `runInBackground`）；新项目不含 UI 包；
  内置字体不含中文。

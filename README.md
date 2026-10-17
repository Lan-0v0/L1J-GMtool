# 龙潭 GM 台

本地网页 GM 台。只监听 `127.0.0.1`，读写 `x8` 库，并通过 **agent 通道**在游戏服进程内执行 GM 指令（不改客户端、不改服务端 jar）。

## 两种运行模式

| 模式 | 服务端位置 | 指令通道 |
|---|---|---|
| 本地 attach | 同一台 Windows | JVMTI attach 到本地 `com.lineage.Server`，加载 `GMAgent.jar` |
| 远端 tunnel-agent（推荐） | 云端 Linux | 服务端启动时 `-javaagent` 加载 GMAgent；GM 台经 SSH 隧道调 `127.0.0.1:17980/invoke` |

> 早期还有一条"往游戏窗口粘贴指令"的通道，已**删除**：游戏以管理员权限运行时 Windows UIPI 会丢弃模拟按键；提权后该客户端又把 `Ctrl+V` 当作自己的热键（弹设置窗口），而 `SendKeys` 打不出中文指令名。

## 启动

- **本地**：双击 `start-gmtool-local.bat`
- **远端**：双击 `start-gmtool-remote.bat` —— 自动修私钥 ACL、准备 `config\sql.properties`、建 SSH 隧道（`13306` 数据库 + `17980` agent）、拉取 agent 令牌、以 `-Dgmtool.agent=tunnel` 启动。端口已在监听则直接复用。

## 从 GitHub 克隆后直接用（误删/换机恢复）

仓库已包含运行所需的一切：`GMTool.jar`、`lib\`（MySQL 驱动）、`src\`、启动脚本、`config\sql.properties.remote`。

只需要补两样本机私密文件（都在 `.gitignore` 里，不会进仓库）：

| 放哪 | 内容 |
|---|---|
| `keys\L1J_CN.pem` | SSH 私钥（或用环境变量 `L1J_PEM` 指向任意位置） |
| `config\sql.properties` | 首次启动会自动由 `sql.properties.remote` 复制；要换库就改它 |

可选环境变量：

| 变量 | 作用 |
|---|---|
| `GMT_JAVA` | 指定 `javaw.exe`（默认找 `..\..\JDK11`、`JAVA_HOME`、`PATH`） |
| `L1J_PEM` | SSH 私钥路径 |
| `L1J_SSH_HOST` | 服务器地址（默认 `ubuntu@124.222.172.24`） |

> 服务器端需要装好 `GMAgent-remote`（见 `L1J-CN` 仓库同名目录），否则隧道 17980 不会有服务在听。


## 用法要点

- 顶栏多选角色。点芯片设为**操作者**。
- **移到此人处**：在线目标发 `.跟o 名字`；离线号进待办，下线后写对方**存档坐标**。
- 地图点击发 `.传送o x y 4`，飞的是操作者自己（agent 模式下指令在服务端执行，不需要游戏窗口）。
- 物品 `.do` 进操作者背包，`.给予o` 进仓库。不要给在线角色插 `character_items`。
- 新建物品/NPC 是写库，**重启游戏服**后才进内存。
- 实时刷 NPC 需要 agent 模式；窗口模式已移除。

## 安全

- 数据库密码不进网页、不另存。
- 远端 agent 只绑 `127.0.0.1`，必须带 `X-GM-Token`（`/opt/l1j/deploy/agent.token`，服务运行用户 `600`）；令牌文件读不到时 agent **fail-closed**，一律 403。
- 待办谁先离线谁执行，可取消。
- 审计在本目录 `data\audit.log`。

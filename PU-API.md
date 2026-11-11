# Pu 插件 API 使用说明

作者：**chenhuitony**

Pu 是启动器内置的插件 API 系统：把插件逻辑写成 YAML，启动器**先检查、后调用**。
不用写 Java、不用编译、不用重启，改完 YAML 下次启动即生效。

## 一、目录结构

```
servers/
└─ Pu/
   ├─ chenhuitony.yml              作者 chenhuitony 的 API
   └─ chenhuitony/                同一作者的多个 API 放子目录
      ├─ ops.yml
      └─ broken.yml
```

**核心规则：第一层名字就是作者名。**

| 路径 | 作者 |
|---|---|
| `Pu/chenhuitony.yml` | `chenhuitony` |
| `Pu/chenhuitony/ops.yml` | `chenhuitony` |
| `Pu/张三/any/deep/x.yml` | `张三` |

文件内还必须写 `author:`，且必须与位置一致，否则拒绝加载。
这样不同作者的 API 天然隔离，不会互相覆盖。

## 二、最小示例

`servers/Pu/chenhuitony.yml`：

```yaml
author: chenhuitony
api: welcome-kit
version: 1.0.0
description: 新玩家欢迎礼包

functions:
  - id: banner
    type: log
    message: "欢迎来到本服务器"

  - id: set-rule
    type: command
    requires: [banner]
    run: gamerule keep_inventory true
```

## 三、字段说明

### API 顶层

| 字段 | 必需 | 说明 |
|---|---|---|
| `author` | 是 | 作者名，必须与文件位置一致 |
| `api` | 是 | API 名称，同作者下唯一 |
| `version` | 否 | 版本号，默认 `1.0.0` |
| `description` | 否 | 描述 |
| `enabled` | 否 | `false` 可临时禁用，默认 `true` |

### 功能（functions）

每个功能必须有 `id` 和 `type`。`id` 在同一 API 内唯一。

| 字段 | 说明 |
|---|---|
| `id` | 功能 ID，API 内唯一 |
| `type` | 功能类型，见下表 |
| `requires` | 依赖的功能 ID 列表，会先执行 |

## 四、六种功能类型

| type | 作用 | 必需参数 | 可选参数 |
|---|---|---|---|
| `log` | 打印一条消息 | `message` | — |
| `command` | 通过 RCON 执行服务端指令 | `run` | — |
| `http` | 发起 HTTP 请求 | `url` | `method` `body` `expect` |
| `script` | 执行外部程序/脚本 | `run` | `cwd` |
| `download` | 下载文件到服务端目录 | `url` `to` | — |
| `java` | 反射调用静态方法 | `class` `methodName` | `args` |

### 1. log —— 打印消息

```yaml
- id: hello
  type: log
  message: "服务器已就绪"
```

输出：`[Pu:chenhuitony/welcome-kit] 服务器已就绪`

### 2. command —— 执行服务端指令

```yaml
- id: set-time
  type: command
  run: time set day
```

启动器会自动启用 RCON，等服务端出现 `Done` 后通过 RCON 发送指令，
并把服务端返回结果显示出来。

> **26.x 语法提醒**：游戏规则用下划线命名，如 `keep_inventory`，
> 旧版的 `keepInventory` 在 26.x 会报 `Incorrect argument`。

### 3. http —— 发网络请求

```yaml
- id: notify
  type: http
  url: https://example.com/webhook
  method: POST
  body: '{"text":"服务器启动了"}'
  expect: 200
```

返回码不等于 `expect` 时该功能失败。

### 4. script —— 跑外部程序

```yaml
- id: backup
  type: script
  run: 7z a backup.zip world
  cwd: .
```

脚本输出会转发到控制台。非 0 退出码视为失败。

### 5. download —— 下载文件

```yaml
- id: fetch-config
  type: download
  url: https://example.com/config.yml
  to: config/custom.yml
```

相对路径基于 `servers/`。

### 6. java —— 反射调用（最大自由度）

```yaml
- id: call-method
  type: java
  class: java.lang.System
  methodName: getProperty
  args: [java.version]
```

按参数个数匹配方法，参数会自动转换成目标类型
（String / int / long / boolean / double / float）。
可以调用**任何** classpath 上存在的类，包括启动器自己的类。

## 五、依赖与执行顺序

`requires` 保证功能按顺序执行：

```yaml
functions:
  - id: a
    type: log
    message: 第一步

  - id: b
    type: log
    requires: [a]
    message: 第二步

  - id: c
    type: log
    requires: [a, b]
    message: 第三步
```

启动器会自动做拓扑排序。检测到循环依赖会拒绝执行。

## 六、先检查、后调用

启动时先做**完整检查**，全部通过才执行：

| 检查项 | 说明 |
|---|---|
| 必需字段 | `author` / `api` / `functions` |
| 作者一致性 | 文件位置与 `author` 字段一致 |
| ID 唯一性 | 功能 `id` 不重复，API 名同作者内不重复 |
| 类型合法性 | `type` 必须是六种之一 |
| 参数完整性 | 各类型必需参数齐全 |
| 依赖可解析 | `requires` 指向存在的功能 |
| 循环依赖 | 检测并拒绝 |

任何一项不通过，**整个 API 跳过**，并逐条打印原因，不影响其它 API。

示例输出（故意写错的文件）：

```
[ERROR] API chenhuitony:broken-demo 检查未通过，已跳过：
[ERROR]   - 功能 bad-type 的类型无效：teleport（可用：[command, http, script, download, java, log]）
[ERROR]   - 功能 bad-http 缺少 url
[ERROR]   - 功能 bad-dep 依赖了不存在的功能：missing-function
```

正常 API 的输出：

```
[ OK ] API chenhuitony:welcome-kit 检查通过（4 个功能）
[INFO]   执行功能 chenhuitony:welcome-kit.banner [log]
[Pu:chenhuitony/welcome-kit] 欢迎来到本服务器，由 chenhuitony 提供支持
[ OK ]   指令已排入队列：gamerule keep_inventory true
[INFO] Pu API 汇总：检查 3 个，执行 1 个，跳过 2 个
[ OK ] 已启用 RCON（端口 25575），用于执行 Pu API 指令
[ OK ]   [RCON] gamerule keep_inventory true -> Game rule keep_inventory is now set to true
[ OK ]   [RCON] time set day -> Set minecraft:overworld to time marker minecraft:day
[ OK ]   [RCON] say 服务器已启动，祝大家游戏愉快！
```

## 七、关于 RCON

`command` 类型依赖 RCON。启动器会自动修改 `servers/server.properties`：

- `enable-rcon=true`
- `rcon.password=` 为空时自动生成
- 保留已有的 `rcon.port` 和密码

之所以用 RCON 而不是直接写 stdin：启动器必须把 stdin 交给真实控制台，
否则 Minecraft 的 JLine 会把命令解析坏。RCON 是官方支持的远程通道。

## 八、错误排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `author=xxx 与所在位置 yyy 不一致` | 文件名/目录名与 author 不同 | 改成一致 |
| `没有任何 functions` | functions 段为空 | 至少写一个功能 |
| `依赖了不存在的功能` | requires 写错 | 检查 ID 拼写 |
| `类型无效` | type 拼错 | 用六种之一 |
| `RCON 连接失败` | RCON 未启用或端口占用 | 检查 server.properties |
| `Incorrect argument for command` | 指令语法与游戏版本不符 | 查对应版本的指令语法 |

原始英文日志完整保留在 `servers/logs/latest.log`，便于对照排查。

# Minecraft 服务端启动器（Java）

作者：**chenhuitony**

自动下载官方服务端、启动、并把控制台日志**实时翻译成中文**。

支持两种服务端：

| 模式 | 参数 | 插件支持 |
|---|---|---|
| 原版 Vanilla | 无 | 不支持 |
| Paper | `--paper` | 支持，自动从 Modrinth 下载 |

## 特点

- 纯 JDK 实现，**零第三方依赖**
- 自动解析官方下载地址（Mojang / PaperMC 官方 API）
- 下载带进度条、断点续传、哈希校验（SHA1 / SHA256）
- 首次运行自动生成 `eula.txt`
- **插件管理**：`servers/plugins.txt` 写插件名，自动下载并安装
- 服务端日志**逐行实时翻译**，兼容原版与 Paper 两种日志格式
- 无关 JDK 警告自动过滤

## 环境要求

- JDK 17 或更高（本机验证于 JDK 25）
- 能访问 `piston-meta.mojang.com` / `fill.papermc.io` / `api.modrinth.com`

## 用法

### 方式一：直接用 jar（推荐）

```bat
start.bat                          :: 原版，最新正式版
start.bat --paper                  :: Paper，支持插件
start.bat --paper --version 1.21.8 :: Paper 指定版本
start.bat --memory 4G              :: 指定内存（默认 2G）
```

等同于 `java -jar mc-launcher.jar [参数]`。

### 方式二：源码运行

```bat
run.bat --paper
```

会自动编译源码后运行，适合改动代码时使用。

### 重新构建 jar

```bat
build.bat
```

产物是 `mc-launcher.jar`（约 30 KB，纯启动器，不含服务端）。

### 服务端 jar 的位置

启动器自身只有 30 KB，真正的服务端 jar 是运行时下载到 `servers/` 下的：

| 文件 | 来源 | 大小 |
|---|---|---|
| `servers/server.jar` | Mojang 官方 | 约 59 MB |
| `servers/paper.jar` | PaperMC 官方 | 约 53 MB |

两者按模式分别使用，互不干扰。这些大文件**不会**打进 `mc-launcher.jar`。

一键切换服务端类型，两者用不同的 jar（`server.jar` / `paper.jar`），互不干扰。

## 插件管理（仅 Paper）

支持 **三个插件平台**，在 `servers/plugins.txt` 中每行写一个插件：

```
luckperms                       自动探测（Modrinth → Hangar → Spiget）
modrinth:luckperms              强制 Modrinth（插件 slug）
hangar:William278/HuskHomes     强制 Hangar（格式：作者/项目）
spiget:34315                    强制 Spiget（SpigotMC 资源 ID）
luckperms@5.5.71                锁定版本（仅 Modrinth 生效）
```

### 三个来源的区别

| 来源 | 标识格式 | 覆盖范围 | 版本筛选 |
|---|---|---|---|
| **Modrinth** | 插件 slug（`luckperms`） | 最广，现代插件首选 | 按 loader + 游戏版本精确过滤 |
| **Hangar** | `作者/项目`（`William278/HuskHomes`） | PaperMC 官方平台 | 按平台版本列表过滤 |
| **Spiget** | 数字 ID（`34315`） | SpigotMC 全站资源 | 仅提示版本不符，仍会安装 |

### 怎么找到插件的标识

- **Modrinth**：插件页地址 `modrinth.com/plugin/luckperms` → 写 `luckperms`
- **Hangar**：项目页地址 `hangar.papermc.io/William278/HuskHomes` → 写 `William278/HuskHomes`
- **Spiget / SpigotMC**：资源页地址 `spigotmc.org/resources/vault.34315/` → 写 `34315`

### 行为说明

- `#` 开头是注释
- 不写前缀时，按 Modrinth → Hangar → Spiget 顺序自动尝试第一个成功的
- 自动探测会先看标识形态：纯数字只查 Spiget，带 `/` 只查 Hangar，其余先查 Modrinth
- 已存在且大小一致的文件会跳过，不重复下载
- 单个插件失败不影响其它插件，会单独报告原因
- Hangar 上部分项目只提供外链（externalUrl）而非直链，这类会自动跳过并尝试下一个来源

## Pu 插件 API（高自由度扩展）

除了下载第三方插件，还可以用 **Pu API** 自己写插件逻辑：YAML 描述、启动器先检查后调用、无需编译。

```
servers/Pu/chenhuitony.yml        作者 chenhuitony 的 API
servers/Pu/chenhuitony/ops.yml    同一作者的其它 API
```

六种功能类型：`log` / `command` / `http` / `script` / `download` / `java`。

```yaml
author: chenhuitony
api: welcome-kit
functions:
  - id: banner
    type: log
    message: "欢迎来到本服务器"
  - id: rule
    type: command
    requires: [banner]
    run: gamerule keep_inventory true
```

**完整说明见 [PU-API.md](PU-API.md)。**

## 目录结构

```
mc-launcher/
├─ mc-launcher.jar           启动器（可执行 jar，约 30 KB）
├─ start.bat                 用 jar 启动
├─ build.bat                 重新构建 jar
├─ run.bat                   源码方式启动（自动编译）
├─ src/main/java/com/mc/
│  ├─ Launcher.java          主流程（原版/Paper 分支）
│  ├─ ServerFetcher.java     Mojang 版本清单解析
│  ├─ PaperFetcher.java      PaperMC v3 API 解析
│  ├─ PluginManager.java     插件编排（多来源 + 自动探测）
│  ├─ PluginSource.java      插件来源统一接口
│  ├─ ModrinthSource.java    Modrinth 来源
│  ├─ HangarSource.java      Hangar 来源（PaperMC 官方）
│  ├─ SpigetSource.java      Spiget 来源（SpigotMC）
│  ├─ PluginApi.java         Pu API 数据模型
│  ├─ ApiLoader.java         Pu API 加载与校验
│  ├─ ApiExecutor.java       Pu API 检查与执行
│  ├─ RconClient.java        RCON 远程指令客户端
│  ├─ MiniYaml.java          极简 YAML 解析器
│  ├─ DownloadTask.java      断点续传下载器
│  ├─ Json.java              极简 JSON 解析器
│  ├─ Translator.java        日志 → 中文（原版 + Paper）
│  └─ Messages.java          中文输出 / 进度条 / 编码自适应
└─ servers/
   ├─ server.jar | paper.jar
   ├─ plugins.txt            插件列表
   ├─ plugins/               已安装插件
   ├─ eula.txt
   ├─ server.properties
   ├─ Pu/                     Pu 插件 API 目录（按作者分）
   └─ world/
```

## 为什么命令输入走真实控制台

启动器让子进程**继承真实控制台 stdin**（`Redirect.INHERIT`），而不是管道转发。
Minecraft 的 JLine 控制台在 Windows 下读到管道输入会把命令解析坏
（`Incorrect argument for command`）。所以请直接在控制台窗口输入指令，
不要用 `echo xxx | java ...` 这种重定向方式。

## 翻译覆盖范围

已覆盖原版与 Paper 的主要日志：启动流程、世界加载、区块保存、玩家加入/离开、
插件加载与启用、性能警告、常见错误等。插件自身的专有消息（如 LuckPerms 的 ASCII
logo 和部分内部日志）会保留英文，属于插件输出，不在翻译范围内。

原始英文日志始终完整保留在 `servers/logs/latest.log`。

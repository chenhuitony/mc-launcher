package com.mc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端日志实时翻译器。
 *
 * 同时支持两种日志前缀：
 *   原版： [19:44:05] [Server thread/INFO]: 正文
 *   Paper：[20:15:45 INFO]: 正文
 *
 * 规则表顺序至关重要：
 *   1. 带 [插件名]/[模块名] 前缀的具体规则放在最前；
 *   2. 无前缀的具体规则次之；
 *   3. 通用兜底 ^\[(.+?)\] (.+)$ 必须放在最后，否则会抢先吃掉具体规则。
 */
public final class Translator {

    private Translator() {}

    private static class Rule {
        final Pattern p;
        final String zh;
        Rule(String regex, String zh) { this.p = Pattern.compile(regex); this.zh = zh; }
    }

    /** 原版：[时间] [线程/级别]: 正文 */
    private static final Pattern PREFIX_VANILLA =
            Pattern.compile("^(\\[[^\\]]*\\]\\s*\\[[^\\]]*\\]:\\s*)(.*)$");
    /** Paper：[时间 级别]: 正文，级别可能带颜色代码 */
    private static final Pattern PREFIX_PAPER =
            Pattern.compile("^(\\[[^\\]]*?(?:INFO|WARN|ERROR|WARNING|DEBUG|TRACE|FATAL)[^\\]]*\\]:\\s*)(.*)$");

    private static final Rule[] RULES = {
        // ========== 一、带方括号前缀的具体规则（必须最前） ==========
        new Rule("^\\[bootstrap\\] Running Java (.+)$", "[bootstrap] 正在运行 Java $1"),
        new Rule("^\\[bootstrap\\] Loading Paper (.+?) for Minecraft (.+)$",
                "[bootstrap] 正在加载 Paper $1（Minecraft $2）"),
        new Rule("^\\[PluginInitializerManager\\] Initializing plugins\\.*\\.*$",
                "[插件初始化] 正在初始化插件..."),
        new Rule("^\\[PluginInitializerManager\\] Initialized (\\d+) plugins?$",
                "[插件初始化] 已初始化 $1 个插件"),
        new Rule("^\\[PluginInitializerManager\\] Bukkit plugins \\((\\d+)\\):$",
                "[插件初始化] Bukkit 插件（$1 个）："),
        new Rule("^\\[spark\\] This server bundles the spark profiler\\..*$",
                "[spark] 本服务端已内置 spark 性能分析器"),
        new Rule("^\\[spark\\] Starting background profiler\\.*\\.*$",
                "[spark] 正在启动后台性能分析器..."),
        new Rule("^\\[spark\\] The async-profiler engine is not supported.*$",
                "[spark] 当前系统不支持 async-profiler，改用内置 Java 引擎"),
        new Rule("^\\[MoonriseCommon\\] Paper is using (\\d+) worker threads, (\\d+) I/O threads$",
                "[Moonrise] Paper 使用 $1 个工作线程、$2 个 I/O 线程"),
        // 插件的具体行为
        new Rule("^\\[(.+?)\\] Loading server plugin (.+?) v?(\\S*)$",
                "[$1] 正在加载插件 $2 $3"),
        new Rule("^\\[(.+?)\\] Loading storage provider\\.*\\.* ?\\[(.+?)\\]$",
                "[$1] 正在加载存储后端 [$2]"),
        new Rule("^\\[(.+?)\\] Loading storage provider\\.*\\.*$", "[$1] 正在加载存储后端..."),
        new Rule("^\\[(.+?)\\] Loading configuration\\.*\\.*$", "[$1] 正在加载配置..."),
        new Rule("^\\[(.+?)\\] Enabling (.+?) v?(\\S*)$", "[$1] 正在启用 $2 $3"),
        new Rule("^\\[(.+?)\\] Enabling (.+?)$", "[$1] 正在启用 $2"),
        new Rule("^\\[(.+?)\\] Disabling (.+?)$", "[$1] 正在禁用 $2"),
        new Rule("^\\[(.+?)\\] Loaded (.+)$", "[$1] 已加载 $2"),

        // ========== 二、无前缀的具体规则 ==========
        // 启动器 / Paper 引导
        new Rule("^Starting net\\.minecraft\\.server\\.Main$", "正在启动服务端主程序"),
        new Rule("^Starting org\\.bukkit\\.craftbukkit\\.Main$", "正在启动 Paper 服务端主程序"),
        new Rule("^Unpacking (.+?) to (.+)$", "正在解压 $1 到 $2"),
        new Rule("^Loading Paper (.+?) for Minecraft (.+)$", "正在加载 Paper $1（Minecraft $2）"),
        new Rule("^This server is running Paper version (.+?) \\(Implementing API version (.+?)\\)$",
                "本服务端运行 Paper 版本 $1（API 版本 $2）"),
        new Rule("^Found new data pack (\\S+), loading it automatically$",
                "发现新数据包 $1，正在自动加载"),
        new Rule("^Paper: Using Java (\\w+) from Velocity\\.$", "Paper：使用 Java 实现的 $1"),
        new Rule("^View this and more helpful information here: (.+)$", "更多帮助信息请访问：$1"),
        new Rule("^Done preparing level \"(.+?)\" \\((.+?)\\)$", "世界 \"$1\" 准备完成（耗时 $2）"),
        new Rule("^Server Ping Player Sample Count: (\\d+)$", "服务器列表玩家样本数：$1"),
        new Rule("^Using (\\d+) threads for Netty based IO$", "Netty 网络层使用 $1 个线程"),
        new Rule("^Running delayed init tasks$", "正在执行延迟初始化任务"),

        // 生命周期
        new Rule("^Starting minecraft server version (.+)$", "正在启动 Minecraft 服务端，版本 $1"),
        new Rule("^Loading properties$", "正在加载配置文件"),
        new Rule("^Default game type: (\\w+)$", "默认游戏模式：$1"),
        new Rule("^Generating keypair$", "正在生成密钥对"),
        new Rule("^Starting Minecraft server on (.+)$", "正在启动 Minecraft 服务端，监听地址 $1"),
        new Rule("^Preparing level \"(.+)\"$", "正在准备世界 \"$1\""),
        new Rule("^Preparing start region for dimension (.+)$", "正在预生成出生点区域，维度 $1"),
        new Rule("^Selecting global world spawn\\.*\\.*$", "正在选择世界全局出生点..."),
        new Rule("^Loading (\\d+) persistent chunks for level '(.+?)'\\.*\\.*$",
                "正在为维度 '$2' 加载 $1 个常驻区块..."),
        new Rule("^Loading (\\d+) persistent chunks\\.*\\.*$", "正在加载 $1 个常驻区块..."),
        new Rule("^Prepared spawn area in (\\d+) ms$", "出生点区域准备完成，耗时 $1 毫秒"),
        new Rule("^Preparing spawn area: (\\d+)%$", "正在准备出生点区域：$1%"),
        new Rule("^Time elapsed: (\\d+) ms$", "耗时：$1 毫秒"),
        new Rule("^Done \\((.+)\\)! For help, type \"help\"$", "启动完成！用时 $1，输入 \"help\" 查看帮助"),
        new Rule("^No existing world data, creating new world$", "未找到已有世界数据，正在创建新世界"),
        new Rule("^Loaded (\\d+) advancements$", "已加载 $1 项进度"),
        new Rule("^Loaded (\\d+) recipes$", "已加载 $1 个配方"),
        new Rule("^Saving chunks for level '(.+)'/(.+)$", "正在保存区块：世界 '$1' / $2"),
        new Rule("^Saving players$", "正在保存玩家数据"),
        new Rule("^Saving worlds$", "正在保存世界"),
        new Rule("^Stopping the server$", "正在停止服务端"),
        new Rule("^Stopping server$", "正在停止服务端"),
        new Rule("^ThreadedAnvilChunkStorage: All dimensions are saved$", "所有维度区块已保存"),
        new Rule("^Saving and pausing game\\.*\\.*\\.*$", "正在保存并暂停游戏..."),
        new Rule("^Server empty for (\\d+) seconds, pausing$", "服务端空置 $1 秒，已自动暂停"),

        // 插件加载（无前缀形式）
        new Rule("^Loading server plugin (.+)$", "正在加载服务端插件 $1"),
        new Rule("^Loading server plugins$", "正在加载服务端插件"),
        new Rule("^Enabling (.+?) v?(\\S*)$", "正在启用插件 $1 $2"),
        new Rule("^Disabling (.+?) v?(\\S*)$", "正在禁用插件 $1 $2"),
        new Rule("^Disabling all plugins$", "正在禁用所有插件"),
        new Rule("^Bukkit plugins \\((\\d+)\\):$", "Bukkit 插件（$1 个）："),
        new Rule("^Server permissions file permissions\\.yml is empty; ignoring it$",
                "权限文件 permissions.yml 为空，已忽略"),
        new Rule("^Unknown/missing plugin (.+?) has been disabled$", "未知或缺失的插件 $1 已被禁用"),
        new Rule("^Could not load 'plugins\\\\(.+?)' in folder 'plugins'$",
                "无法从 plugins 目录加载插件 $1"),
        new Rule("^Plugin (.+?) has failed to load$", "插件 $1 加载失败"),
        new Rule("^Loaded (\\d+) plugin\\(s\\)$", "已加载 $1 个插件"),

        // 玩家事件
        new Rule("^(.+) joined the game$", "$1 加入了游戏"),
        new Rule("^(.+) left the game$", "$1 离开了游戏"),
        new Rule("^(.+) lost connection: (.+)$", "$1 断开连接：$2"),
        new Rule("^(.+) logged in with entity id (\\d+) at \\((.+)\\)$", "$1 已登录，实体 ID $2，坐标 ($3)"),
        new Rule("^Player (\\S+) has joined$", "玩家 $1 已加入"),
        new Rule("^Player (\\S+) has left$", "玩家 $1 已离开"),
        new Rule("^Opped (.+)$", "$1 已被设为管理员"),
        new Rule("^De-opped (.+)$", "$1 的管理员权限已被移除"),
        new Rule("^Banned player (.+)$", "已封禁玩家 $1"),
        new Rule("^Unbanned player (.+)$", "已解封玩家 $1"),

        // 警告与错误
        new Rule("^Failed to load properties from file: (.+)$", "加载配置文件失败：$1（首次运行会自动创建）"),
        new Rule("^Can't keep up! Is the server overloaded\\? Running (\\d+)ms or (\\d+) ticks behind$",
                "服务端跟不上！可能过载：已落后 $1 毫秒 / $2 tick"),
        new Rule("^System chat: (.*)$", "系统消息：$1"),

        // 环境与杂项
        new Rule("^Environment: Environment\\[(.+)\\]$", "运行环境：$1"),
        new Rule("^Using (\\d+) threads for worldgen$", "世界生成使用 $1 个线程"),
        new Rule("^Reloading ResourceManager: (.+)$", "正在重载资源管理器：$1"),
        new Rule("^Generating new spawn chunks for world$", "正在为新世界生成出生点区块"),

        // ========== 三、通用兜底：必须最后 ==========
        new Rule("^\\[(.+?)\\] (.+)$", "[$1] $2"),
    };

    /**
     * 翻译一行服务端日志；不需要显示的行返回 null。
     */
    public static String translate(String line) {
        // 无关的 JDK 原生访问 / 不安全 API 警告
        if (line.startsWith("WARNING: A restricted method")
                || line.startsWith("WARNING: java.lang.System::load")
                || line.startsWith("WARNING: Use --enable-native-access")
                || line.startsWith("WARNING: Restricted methods will be blocked")
                || line.startsWith("WARNING: A terminally deprecated method")
                || line.startsWith("WARNING: sun.misc.Unsafe::")
                || line.startsWith("WARNING: Please consider reporting")
                || line.startsWith("Picked up JAVA_TOOL_OPTIONS")) {
            return null;
        }

        Matcher mv = PREFIX_VANILLA.matcher(line);
        if (mv.matches()) {
            return mv.group(1) + translateBody(mv.group(2));
        }
        Matcher mp = PREFIX_PAPER.matcher(line);
        if (mp.matches()) {
            return mp.group(1) + translateBody(mp.group(2));
        }
        return translateBody(line);
    }

    private static String translateBody(String body) {
        if (body == null || body.isEmpty()) return body;
        for (Rule r : RULES) {
            Matcher m = r.p.matcher(body);
            if (m.matches()) {
                String out = r.zh;
                for (int i = 1; i <= m.groupCount(); i++) {
                    out = out.replace("$" + i, m.group(i) == null ? "" : m.group(i));
                }
                return out;
            }
        }
        return body;
    }
}
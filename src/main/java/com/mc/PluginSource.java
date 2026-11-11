package com.mc;

import java.net.URL;

/**
 * 插件来源的统一抽象。
 *
 * <p>不同插件平台（Modrinth / Hangar / Spiget）的 API 结构完全不同，
 * 但对启动器来说只需要三件事：插件叫什么、下载地址在哪、文件名是什么。
 * 所以用一个接口把它们统一起来。
 *
 * <p>插件名支持来源前缀：
 * <pre>
 *   luckperms              自动探测（Modrinth → Hangar → Spiget）
 *   modrinth:luckperms     强制走 Modrinth
 *   hangar:William278/HuskHomes   走 Hangar（格式 作者/项目）
 *   spiget:34315           走 Spiget（资源 ID）
 * </pre>
 */
public interface PluginSource {

    /** 来源名称，用于日志。 */
    String name();

    /**
     * 查询插件。
     *
     * @param id          来源内的插件标识
     * @param gameVersion 目标游戏版本，可为 null
     * @return 解析结果
     * @throws Exception 找不到或网络错误
     */
    PluginManager.Plugin resolve(String id, String gameVersion) throws Exception;

    /**
     * 判断这个标识是否看起来属于本来源（用于自动探测时的快速筛选）。
     * 例如 Spiget 只接受纯数字 ID。
     */
    default boolean accepts(String id) {
        return true;
    }

    /** 把字符串转成 URL，统一处理异常。 */
    static URL url(String s) {
        try {
            return new URL(s);
        } catch (Exception e) {
            throw new RuntimeException("无效的下载地址：" + s, e);
        }
    }
}
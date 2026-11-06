package com.shiro.classflow.ui.campus.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.shiro.classflow.MainActivity
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository

/**
 * 校园设备（饮水机 / 洗衣机 / 洗浴）的桌面快捷方式：**每台设备一个图标**，点一下就直用。
 *
 * 语义与吹风机的 `HairdryerShortcuts` 完全一致：
 * - 同一台设备复用同一个 shortcut ID（重复添加 = 更新图标 / 名字）；
 * - 快捷方式里带设备身份（kind + key + raw），落地还原成对应的直达 Destination，
 *   绝不把「去哪」写死在图标里；
 * - 预设图标：3 个设备轮廓图标（每类的默认）+ 吹风机那套 12 个彩色花色（想区分同类的多台设备时挑）。
 */
object CampusDeviceShortcuts {

    /** 预设图标（约一打，够把几台机器区分开）。 */
    data class IconPreset(val id: String, @DrawableRes val resId: Int)

    /** 每类设备的默认图标。 */
    fun defaultIconId(kind: CampusDeviceKind): String = when (kind) {
        CampusDeviceKind.WATER -> "water"
        CampusDeviceKind.WASHER -> "washer"
        CampusDeviceKind.SHOWER -> "shower"
    }

    /** 图标可选集：设备轮廓 + 彩色花色（三类设备通用一套花色）。 */
    val ICONS: List<IconPreset> = listOf(
        IconPreset("water", R.drawable.ic_device_water),
        IconPreset("washer", R.drawable.ic_device_washer),
        IconPreset("shower", R.drawable.ic_device_shower),
        IconPreset("drop", R.drawable.ic_hairdryer_drop),
        IconPreset("bolt", R.drawable.ic_hairdryer_bolt),
        IconPreset("flame", R.drawable.ic_hairdryer_flame),
        IconPreset("leaf", R.drawable.ic_hairdryer_leaf),
        IconPreset("star", R.drawable.ic_hairdryer_star),
        IconPreset("heart", R.drawable.ic_hairdryer_heart),
        IconPreset("cloud", R.drawable.ic_hairdryer_cloud),
        IconPreset("sun", R.drawable.ic_hairdryer_sun),
        IconPreset("moon", R.drawable.ic_hairdryer_moon),
        IconPreset("snow", R.drawable.ic_hairdryer_snow),
        IconPreset("gem", R.drawable.ic_hairdryer_gem),
    )

    @DrawableRes
    fun iconResOf(iconId: String?, kind: CampusDeviceKind): Int =
        ICONS.firstOrNull { it.id == iconId }?.resId
            ?: ICONS.firstOrNull { it.id == defaultIconId(kind) }?.resId
            ?: ICONS.first().resId

    /** shortcut ID 与设备一一对应：同一台设备重复添加就是更新同一个图标。 */
    fun shortcutIdOf(entry: CampusDeviceEntry): String =
        when (entry.kind) {
            CampusDeviceKind.WATER -> "water_${entry.key}"
            CampusDeviceKind.WASHER -> "washer_${entry.key}"
            CampusDeviceKind.SHOWER -> "shower_${entry.key}"
        }

    /**
     * 旧版本（`MainActivity` 还叫 `com.xingheyuzhuan.shiguangschedule.MainActivity` 时）
     * 钉到桌面的快捷方式，其 Intent 里写死的是旧组件名。搬包之后这些图标点下去会报
     * 「Activity class does not exist」。吹风机那套有 `HairdryerShortcuts.migrateLegacyIntents`
     * 在启动时修正，这里在「添加 / 更新快捷方式」时顺带对全部钉过的图标做同样修正
     * （校园设备的快捷方式数量少，不必占启动路径）。
     */
    fun migrateLegacyIntents(context: Context) {
        val stale = runCatching {
            ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC
            ).filter {
                it.intent.component?.className == LEGACY_ACTIVITY_CLASS &&
                    it.id.let { id -> id.startsWith("water_") || id.startsWith("washer_") || id.startsWith("shower_") }
            }
        }.getOrDefault(emptyList())
        if (stale.isEmpty()) return

        val updates = stale.map { info ->
            val migrated = Intent(info.intent).setClass(context, MainActivity::class.java)
            ShortcutInfoCompat.Builder(info)
                .setActivity(ComponentName(context, MainActivity::class.java))
                .setIntent(migrated)
                .build()
        }
        runCatching { ShortcutManagerCompat.updateShortcuts(context, updates) }
    }

    /** 当前桌面（Launcher）支不支持「请求添加图标」。 */
    fun isPinSupported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /** 「加到桌面」这件事的结果：每一种都要给用户一句不同的话，别一律说「失败」。 */
    enum class PinResult {
        /** 系统已受理（接下来由 Launcher 弹「是否添加」确认框）。 */
        REQUESTED,

        /** 桌面上已经有这台设备的图标了，顺手把图标 / 名字更新成这次选的。 */
        UPDATED,

        /** 桌面上已经有，且这次只是又点了一遍（图标没变）。 */
        ALREADY_PINNED,

        /** 当前 Launcher 不支持由应用发起添加快捷方式。 */
        UNSUPPORTED,

        /** 请求被系统 / Launcher 拒了（多半是快捷方式数量上限之类）。 */
        FAILED,
    }

    /**
     * 把某台设备加到桌面（已经加过的走「更新」，不再重复请求）。
     *
     * 为什么要分这么细：**同一个 ID 重复请求钉图标，系统会直接返回失败**
     * （实测 vivo 桌面就是这样，用户看到的现象是「已经加过一次，再加就提示失败」）。
     * 所以先查一遍这台设备是不是已经在桌面上：
     * - 已经在 → 用 `updateShortcuts` 更新图标 / 名字（能改就改，改不了就告诉用户「已经有了」）；
     * - 不在 → 正常请求钉图标。
     */
    fun addToHome(
        context: Context,
        entry: CampusDeviceEntry,
        label: String,
    ): PinResult {
        if (!isPinSupported(context)) return PinResult.UNSUPPORTED
        migrateLegacyIntents(context)
        val shortcut = buildShortcut(context, entry, label)

        if (isPinned(context, entry)) {
            val updated = runCatching {
                ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
            }.getOrDefault(false)
            return if (updated) PinResult.UPDATED else PinResult.ALREADY_PINNED
        }

        val requested = runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
        if (requested) return PinResult.REQUESTED

        // 有些桌面（含 vivo）对「已经在桌面上的 ID」直接回 false：再确认一次，
        // 免得把「已经有了」报成「添加失败」
        return if (isPinned(context, entry)) PinResult.ALREADY_PINNED else PinResult.FAILED
    }

    /**
     * 改完洗浴类型后刷新桌面快捷方式。
     *
     * 快捷方式 ID 是按 key 生成的，改类型换了 key 就等于换了 ID —— 但**桌面上那个图标的 ID
     * 改不了**（钉住的位置由 Launcher 持有）。所以这里用**旧 ID** 重新写一遍 Intent：
     * 图标留在原位，点下去却已经指向新的系统，不会拿旧系统去解析。
     */
    fun updateAfterSystemChange(
        context: Context,
        oldEntry: CampusDeviceEntry,
        newEntry: CampusDeviceEntry,
    ) {
        if (!isPinned(context, oldEntry)) return
        runCatching {
            val shortcut = buildShortcut(
                context = context,
                entry = newEntry,
                label = newEntry.displayTitle.take(12),
                idOverride = shortcutIdOf(oldEntry),
            )
            ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
        }
    }

    /** 这台设备是不是已经钉在桌面上了。 */
    fun isPinned(context: Context, entry: CampusDeviceEntry): Boolean = runCatching {
        val id = shortcutIdOf(entry)
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .any { it.id == id }
    }.getOrDefault(false)

    private fun buildShortcut(
        context: Context,
        entry: CampusDeviceEntry,
        label: String,
        /** 覆盖快捷方式 ID（改类型时要用旧 ID 覆盖桌面上的图标，见 [updateAfterSystemChange]）。 */
        idOverride: String? = null,
    ): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = when (entry.kind) {
                CampusDeviceKind.WATER -> MainActivity.ACTION_WATER_DEVICE
                CampusDeviceKind.WASHER -> MainActivity.ACTION_WASHER_DEVICE
                CampusDeviceKind.SHOWER -> MainActivity.ACTION_SHOWER_DEVICE
            }
            putExtra(MainActivity.EXTRA_DEVICE_KIND, entry.kind.name)
            putExtra(MainActivity.EXTRA_DEVICE_KEY, entry.key)
            putExtra(MainActivity.EXTRA_DEVICE_RAW, entry.raw)
            entry.system?.let { putExtra(MainActivity.EXTRA_DEVICE_SYSTEM, it) }
            entry.port?.let { putExtra(MainActivity.EXTRA_DEVICE_PORT, it) }
            // 同一台设备始终复用同一个快捷方式（不会越点越多）
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return ShortcutInfoCompat.Builder(context, idOverride ?: shortcutIdOf(entry))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconResOf(entry.iconId, entry.kind)))
            .setIntent(intent)
            .build()
    }

    /** 从快捷方式 Intent 的 extras 还原记录条目（入口分发用，字段缺失返回 null）。 */
    fun entryFromIntent(
        kind: CampusDeviceKind,
        key: String?,
        raw: String?,
        system: String?,
        port: String?,
    ): CampusDeviceEntry? {
        if (key.isNullOrBlank()) return null
        return CampusDeviceEntry(
            kind = kind,
            key = key,
            raw = raw.orEmpty(),
            system = system,
            port = port,
        )
    }
}

/** 搬包前 `MainActivity` 的全限定名；只用于迁移历史快捷方式。 */
private const val LEGACY_ACTIVITY_CLASS = "com.xingheyuzhuan.shiguangschedule.MainActivity"

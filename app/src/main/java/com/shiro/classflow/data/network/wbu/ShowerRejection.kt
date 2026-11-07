package com.shiro.classflow.data.network.wbu

/**
 * 一卡通平台对用水请求的**业务拒绝**归类。
 *
 * 这类响应是「走到了服务端、服务端明确说不」，和网络失败完全不同：
 * - `ShowerApiException` 继承 `IOException`，过去被 `WbuFailureDetector` 判成 `Unreachable`，
 *   页面显示「网络连接失败，请检查网络后重试」—— 用户照提示去查网络，永远修不好；
 * - 真正的下一步是：余额不足 → 去充值；设备在使用中 → 接管那一单；
 *   设备不匹配 → 换设备；其它 → 原样转述服务端的话。
 *
 * 判据只认服务端原话的**关键短语**（`余额不足` / `使用中` / `不匹配`）。匹配不上一律 [OTHER]：
 * 宁可原样显示服务端文案，也不要按猜测编造原因。
 */
enum class ShowerRejection {
    /** 余额不足（2-3 栋开单要预扣 5.00 元，实测服务端原文「卡内余额不足」）。 */
    INSUFFICIENT_BALANCE,

    /** 设备正在被别人 / 自己另一单占用（原文「设备在使用中」）。 */
    DEVICE_IN_USE,

    /** 设备与缴费项不匹配（原文「设备不匹配」）。 */
    DEVICE_MISMATCH,

    /** 其它业务拒绝：原样显示服务端文案。 */
    OTHER
}

fun classifyShowerRejection(message: String?): ShowerRejection {
    val text = message.orEmpty()
    return when {
        text.contains("余额不足") -> ShowerRejection.INSUFFICIENT_BALANCE
        text.contains("使用中") -> ShowerRejection.DEVICE_IN_USE
        text.contains("不匹配") -> ShowerRejection.DEVICE_MISMATCH
        else -> ShowerRejection.OTHER
    }
}

/**
 * `consumption` 返回 `code=200` 时，`data` 里的 `status` / `message` 是不是在说「这一单没开成」。
 *
 * 一卡通这套接口有两条拒绝路径：`code != 200`（`post` 直接抛 [WbuShowerWaterClient.ShowerApiException]）
 * 和 `code == 200` 但 `data.status` 明确不是「用水中（1）」并附了一句 `message`。过去后者只被
 * `Log.i` 打出来，界面转一下就回到「开始用水」——用户以为按钮没反应，其实是服务端拒绝了。
 *
 * **只把「明确的非 1 + 有话说」当作可疑**：`status` 缺失（服务端没这个字段）仍按成功处理，
 * 免得把正常的开单判成失败。判成可疑之后也不是直接报错，而是按服务端状态复核（见
 * `ShowerWaterViewModel.startWater`）：状态真的在用就照常显示，只有确实没开成才把这句话显示出来。
 */
fun showerStartRefused(status: Int?, message: String?): Boolean =
    status != null && status != 1 && !message.isNullOrBlank()

package com.shiro.classflow.data.network.wbu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 手动输入洗浴设备码的解析（设置 → 校园服务 → 洗浴 →「手动输入机号」）。
 *
 * 为什么**不自动判型**：2-3 栋的 `getDevicesType` 实测并不校验设备是否存在 —— 拿 1 栋机号
 * `10101`、甚至 `99999999` 去问，它照样回 `devicesType=101`（淋浴）。它回答的是「这个缴费项是
 * 淋浴」，不是「这台设备存在」。所以「两侧都试、谁先成功用谁」必然被 2-3 栋抢走，等于编造答案。
 *
 * 现在的分工：
 * - 输入里**已经写明系统**的（`/s/` 深链、`机号$#$端口`、洗浴码载体链接、1 栋扫码裸码）→ [Result.Pinned]；
 * - 只有数字（可带端口）→ [Result.Ambiguous]，由用户在对话框里勾选是 1 栋还是 2-3 栋，
 *   [Result.Ambiguous.suggestedSystem] 只作为默认勾选（按位数：≤5 位像 1 栋机号、≥6 位像水表设备号）。
 *
 * 真正的裁决仍然交给服务端：1 栋 `CheckKsPos` 是严格正证（出错会明确说「没有这个机号」）；
 * 2-3 栋只能由页面开单时才知道设备是否存在。界面文案据此区分，不假装 2-3 栋也校验过。
 */
object ShowerManualCode {

    /** 归一后的设备身份。 */
    data class Candidate(
        /** [CampusShowerLink.SYSTEM_YKT] 或 [CampusShowerLink.SYSTEM_LIFE]。 */
        val system: String,
        /** 1 栋为归一后的机号（去前导零）；2-3 栋为水表设备号。 */
        val code: String,
        /** 多路控水器端口（仅 2-3 栋可带）。 */
        val port: String? = null
    )

    sealed interface Result {

        /** 输入本身已经限定系统，用户无需（也不能）勾选。 */
        data class Pinned(val candidate: Candidate) : Result

        /** 只有数字（可带端口）：系统由用户勾选。 */
        data class Ambiguous(
            /** 原样保留的数字串（2-3 栋设备号可能带前导零）。 */
            val digits: String,
            val port: String?,
            /** 按位数推测的系统。**仅供提示**：2-3 栋接口不校验设备，绝不能据此自动提交。 */
            val suggestedSystem: String
        ) : Result

        /** 空输入、超长或完全认不出。 */
        data object Unparsed : Result
    }

    /** 设备号长度上限（与 [CampusShowerEntryResolver.buildLifeServiceRaw] 一致）。 */
    private const val MAX_CODE_LENGTH = 256

    /** 端口位数上限（同上）。 */
    private const val MAX_PORT_DIGITS = 8

    /** 1 栋机号上限（5 位，与 `CampusShowerLink` / 页面 `getPosNo` 一致）。 */
    private const val MAX_POSNO = 99999

    /** `设备号/端口` 或 `设备号 端口`。 */
    private val NUMBER_WITH_PORT = Regex("^(\\d+)[/\\s]+(\\d+)$")

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_CODE_LENGTH) return Result.Unparsed

        // 1. /s/{系统}/{设备号}[/{端口}] 深链：系统写在路径里
        CampusShowerLink.parse(text)?.let { direct ->
            return Result.Pinned(
                when (direct) {
                    is CampusShowerLink.Direct.Ykt ->
                        Candidate(CampusShowerLink.SYSTEM_YKT, direct.posno)

                    is CampusShowerLink.Direct.Life ->
                        Candidate(CampusShowerLink.SYSTEM_LIFE, direct.imei, direct.port)
                }
            )
        }

        // 2. 2-3 栋贴纸原文：imei$#$port（首段原样当设备号，与页面 handleImei 一致）
        if (text.contains(ShowerQrLink.IMEI_PORT_SEPARATOR)) {
            val parts = text.split(ShowerQrLink.IMEI_PORT_SEPARATOR)
            if (parts.size > 2) return Result.Unparsed
            val code = parts.first().trim()
            val port = parts.getOrNull(1).orEmpty().trim()
            if (!isValidCode(code) || !isValidPort(port)) return Result.Unparsed
            return Result.Pinned(
                Candidate(CampusShowerLink.SYSTEM_LIFE, code, port.ifEmpty { null })
            )
        }

        // 3. 洗浴码载体链接：只放行白名单域名，其余网址不猜（避免把普通链接当设备码）
        text.toHttpUrlOrNull()?.let { url ->
            if (!ShowerQrLink.isShowerLinkHost(url.host)) return Result.Unparsed
            // 注意：网址里的 `:50040` 是主机端口，不是控水器端口（那是 `$#$` 后面的数字）
            val id = url.queryParameter("id").orEmpty().trim()
            if (!isValidCode(id)) return Result.Unparsed
            return Result.Pinned(Candidate(CampusShowerLink.SYSTEM_LIFE, id))
        }

        // 4. 纯数字：系统交给用户勾选
        if (text.all { it.isDigit() }) {
            if (text.trimStart('0').isEmpty()) return Result.Unparsed
            return Result.Ambiguous(text, null, suggestForDigits(text))
        }

        // 5. 设备号 + 端口：1 栋没有端口概念，所以默认就是 2-3 栋
        NUMBER_WITH_PORT.matchEntire(text)?.let { match ->
            val digits = match.groupValues[1]
            val port = match.groupValues[2]
            if (!isValidPort(port)) return Result.Unparsed
            return Result.Ambiguous(digits, port, suggestForDigits(digits))
        }

        // 6. 13 位以上含非数字的裸码：1 栋扫码码（第 8~12 位是机号）
        (ShowerQrLink.parse(text) as? ShowerQrLink.Result.YktXyyy)?.let { bare ->
            return Result.Pinned(Candidate(CampusShowerLink.SYSTEM_YKT, bare.posno))
        }

        return Result.Unparsed
    }

    /**
     * 把一组「设备号 + 端口」按某个系统归一化；这个系统收不下这串输入时返回 null。
     *
     * 手动输入的勾选（[resolve]）与记录卡片上的「改类型」共用这一处规则 ——
     * 两处对「1 栋最多 5 位、不带端口」的口径必须完全一致，否则会造出
     * 「改得进去但用不了」的记录。
     */
    fun normalize(system: String, code: String, port: String?): Candidate? {
        val digits = code.trim()
        if (digits.isEmpty() || digits.length > MAX_CODE_LENGTH) return null
        return when (system) {
            CampusShowerLink.SYSTEM_YKT -> {
                // 1 栋机号：最多 5 位、无端口、无前导零口径（与 /s/y/ 深链一致）
                if (!port.isNullOrBlank()) return null
                if (!digits.all { it.isDigit() }) return null
                val normalized = digits.trimStart('0')
                if (normalized.isEmpty() || normalized.length > 5) return null
                val value = normalized.toIntOrNull() ?: return null
                if (value <= 0 || value > MAX_POSNO) return null
                Candidate(CampusShowerLink.SYSTEM_YKT, value.toString())
            }

            CampusShowerLink.SYSTEM_LIFE -> {
                if (!isValidCode(digits)) return null
                val cleanPort = port?.trim()?.takeIf { it.isNotEmpty() }
                if (!isValidPort(cleanPort.orEmpty())) return null
                Candidate(CampusShowerLink.SYSTEM_LIFE, digits, cleanPort)
            }

            else -> null
        }
    }

    /**
     * 把 [Result.Ambiguous] 按用户勾选的系统落成候选；这个系统收不下这串数字时返回 null。
     *
     * 「收不下」的例子：勾了 1 栋却输了 8 位数字（1 栋机号最多 5 位）、勾了 1 栋却带了端口。
     * 这种情况对话框会把确定按钮置灰并说明原因，而不是把不可能的输入送去撞接口。
     */
    fun resolve(ambiguous: Result.Ambiguous, system: String): Candidate? =
        normalize(system, ambiguous.digits, ambiguous.port)

    /** 默认勾选：1 栋机号最多 5 位，更长的数字只可能是 2-3 栋的水表设备号。 */
    private fun suggestForDigits(digits: String): String {
        val normalized = digits.trimStart('0')
        return if (normalized.isNotEmpty() && normalized.length <= 5) {
            CampusShowerLink.SYSTEM_YKT
        } else {
            CampusShowerLink.SYSTEM_LIFE
        }
    }

    private fun isValidCode(code: String): Boolean =
        code.isNotEmpty() && code.length <= MAX_CODE_LENGTH &&
            code.none { it.isWhitespace() || it == '$' || it == '#' }

    private fun isValidPort(port: String): Boolean =
        port.isEmpty() || (port.length <= MAX_PORT_DIGITS && port.all { it.isDigit() })
}

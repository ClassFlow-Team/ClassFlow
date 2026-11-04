package com.shiro.classflow.data.network.wbu

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import okhttp3.Dns

/**
 * 直连通道下，用**学校 DNS** 解析校园域名。
 *
 * ## 为什么需要它
 *
 * 真机实测（2026-11，`校园网 + Clash`）：
 *
 * | 目标 | 结果 |
 * |---|---|
 * | `172.16.90.162`（认证页，裸 IP） | 200 / 20ms |
 * | `172.16.87.5:81`（一卡通，裸 IP） | 200 / 0.8s |
 * | `yktyd.wbu.edu.cn:81`（域名） | **000 / 5s 超时**，对端 `28.0.0.158` |
 *
 * 裸 IP 能通、域名不通。原因不是「校园域名被劫持到海外」（那条早期判断已证伪），
 * 而是 **Clash 的 fake-IP 只替换「经系统 resolver 的域名解析」**：
 *
 * - WiFi DNS `192.168.123.1` 手工查 `yktyd` 明明返回 `172.16.87.5`；
 * - 但 App 走 `InetAddress.getAllByName` 拿到的是 `28.0.0.158`；
 * - `28.0.0.158` 没有可用的出站映射 → TCP 直接超时。
 *
 * 而**裸 UDP 53 查询学校 DNS 不被劫持**（实测以 App 自己的 UID 也通，返回真实 `172.16.87.5`）。
 *
 * ## 前置条件（缺一不可）
 *
 * 1. 用户没关「使用学校 DNS 解析校园域名」开关；
 * 2. 本次走的是**直连通道**（`useVpn = false`）；
 * 3. 裸 IP 探测确认**能到校内**（见 [WbuNetworkProbe.probeCampusReachable]）；
 * 4. 域名属于 `*.wbu.edu.cn`；
 * 5. 学校 DNS 真的返回了**私网**地址。
 *
 * 任一不满足 → 原样交给系统 resolver，行为与没有本类时完全一致。
 *
 * ## 为什么必须排除 WebVPN 通道
 *
 * WebVPN 模式下 `webvpn.wbu.edu.cn` **必须**解析成 fake-IP 才能进隧道。
 * 若此时用学校 DNS 拿到公网地址 `183.95.36.3`，请求会绕过代理的隧道规则 → WebVPN 直接不可用。
 * 这条红线由 [CampusDnsPolicy.shouldUseSchoolDns] 钉住，并有单测覆盖。
 *
 * ## 与「上次撤回」的关系
 *
 * 曾以「fake-IP 是代理把域名交给正确出站规则的唯一手段」为由整体删除 DNS 层。
 * 该理由已被实测证伪：`28.0.0.158` 是死路（TCP 000），真正能用的是裸 IP。
 * 所以问题不在「改不改 DNS」，而在**前置条件**：旧版「发现 fake-IP 就无条件改」，
 * 现在改成「探测确认能到校内、且只在直连通道，才改」。
 */
internal object CampusDnsPolicy {

    /** 学校权威 DNS（实测可 ping、UDP 53 可查、不被 fake-IP 劫持）。 */
    const val SCHOOL_DNS_PRIMARY = "218.199.208.8"

    /**
     * 兜底用的域名：`dns.wbu.edu.cn` 本身解析到 [SCHOOL_DNS_PRIMARY]。
     *
     * 只在学校换了 IP 时有用 —— 用系统解析拿它，拿不到就算了（不递归、不纠缠）。
     */
    const val SCHOOL_DNS_HOSTNAME = "dns.wbu.edu.cn"

    /**
     * 只有校园域名才走学校 DNS。
     *
     * 注意 `wbu.edu.cn.evil.com` 这一类必须判 false：`endsWith(".wbu.edu.cn")` 天然防住，
     * 但要同时接受裸的 `wbu.edu.cn`。
     */
    fun isCampusHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return false
        return normalized == "wbu.edu.cn" || normalized.endsWith(".wbu.edu.cn")
    }

    /**
     * 学校 DNS 的答案能不能用。
     *
     * **只接受私网地址。** 学校 DNS 若返回公网映射（`183.95.36.17` 等），说明这次查询
     * 落在了校外 / 异常链路上；而那批公网映射实测是死的（只有 `webvpn` 活着），
     * 用它们只会把一次本可避免的失败换成一个更慢的失败。
     *
     * 同时天然排除 fake-IP（`28/8`、`198.18/15`、`240/4`）—— 那些不是私网段。
     */
    fun isUsableCampusAnswer(ip: String): Boolean = isPrivateAddress(ip)

    /** RFC1918 私网段：`10/8`、`172.16/12`、`192.168/16`。 */
    fun isPrivateAddress(raw: String): Boolean {
        val octets = raw.trim().split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }

    /**
     * 五项前置条件全满足才启用学校 DNS。
     *
     * @param enabled 「使用学校 DNS 解析校园域名」开关；关掉后本类**什么都不做**。
     * @param useVpn 本次是否走 WebVPN。为 `true` 时恒 false（红线，见类注释）。
     * @param campusReachable 裸 IP 探测是否确认能到校内。
     */
    fun shouldUseSchoolDns(
        host: String,
        useVpn: Boolean,
        campusReachable: Boolean,
        enabled: Boolean
    ): Boolean {
        if (!enabled) return false
        if (useVpn) return false
        if (!campusReachable) return false
        return isCampusHost(host)
    }
}

/**
 * 最小的 UDP DNS 客户端：只查 A 记录。
 *
 * 刻意不复用 OkHttp —— 它本身就是给 OkHttp 的 [Dns] 用的，共用会形成循环依赖。
 */
internal object SchoolDns {

    private const val TAG = "CampusDns"

    /**
     * 查询超时。
     *
     * 实测校内往返 20–60ms；校外不可达时约 5s。取 1200ms 是为了「校外别太慢」与
     * 「校内绝不误杀」之间的平衡 —— 慢于此仍会回退系统解析，只是这一次请求多等了 1.2s。
     */
    const val DEFAULT_TIMEOUT_MS = 1200L

    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val RCODE_NO_ERROR = 0

    /**
     * 查询 [host] 的 A 记录。
     *
     * 依次尝试 [SchoolDnsServers.resolve] 给出的服务器（主用固定 IP，兜底 `dns.wbu.edu.cn`）。
     *
     * @return 私网地址列表；全部查不到 / 超时 / 应答异常 / 只有公网地址 → 空列表（调用方回退系统解析）。
     *
     * **失败不缓存**（缓存是调用方的事，且失败结论不该被记住）：一次抖动若被记住，
     * 整个会话都会退回 fake-IP，反而制造一个更难查的问题。
     */
    fun resolveA(
        host: String,
        servers: List<String> = SchoolDnsServers.resolve(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): List<String> {
        if (!CampusDnsPolicy.isCampusHost(host)) return emptyList()
        for (server in servers) {
            val addresses = query(host, server, timeoutMs)
            if (addresses.isNotEmpty()) return addresses
        }
        return emptyList()
    }

    private fun query(host: String, server: String, timeoutMs: Long): List<String> {
        val query = buildQuery(host) ?: return emptyList()
        val reply = runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                val address = InetAddress.getByName(server)
                socket.send(DatagramPacket(query, query.size, address, 53))
                val buffer = ByteArray(1500)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                buffer.copyOf(packet.length)
            }
        }.getOrElse { e ->
            Log.i(TAG, "学校 DNS 查询失败 $host @$server: ${e.javaClass.simpleName}")
            return emptyList()
        }
        return parseAnswer(reply, query.size)
    }

    /** 构造一个 A 记录的查询报文（id 固定，不影响正确性）。 */
    internal fun buildQuery(host: String): ByteArray? {
        val labels = host.trim().trimEnd('.').split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
        val out = ArrayList<Byte>(64)
        // header: id=0x1234, flags=0x0100 (RD), qd=1, an=0, ns=0, ar=0
        out += listOf<Byte>(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        labels.forEach { label ->
            out += label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).forEach { out += it }
        }
        out += 0.toByte()
        // qtype=A, qclass=IN
        out += listOf<Byte>(0x00, TYPE_A.toByte(), 0x00, CLASS_IN.toByte())
        return out.toByteArray()
    }

    /**
     * 解析应答，取所有 A 记录，并只保留私网地址。
     *
     * 处理两种真实形态：CNAME 链（跳过，继续找 A）、以及应答里混着公网地址（丢弃）。
     *
     * 刻意**不打日志**：保持纯函数，才能在 JVM 单测里直接跑（`android.util.Log` 未 mock）。
     */
    internal fun parseAnswer(reply: ByteArray, questionLength: Int): List<String> {
        if (reply.size < 12) return emptyList()
        val rcode = reply[3].toInt() and 0x0F
        if (rcode != RCODE_NO_ERROR) return emptyList()
        val answerCount = ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)
        if (answerCount == 0) return emptyList()

        // 从问题之后开始跳（问题区末尾就是 qtype+qclass，共 4 字节）
        var offset = questionLength
        val result = ArrayList<String>(answerCount)
        repeat(answerCount) {
            val nameEnd = skipName(reply, offset) ?: return result
            offset = nameEnd
            if (offset + 10 > reply.size) return result
            val type = ((reply[offset].toInt() and 0xFF) shl 8) or (reply[offset + 1].toInt() and 0xFF)
            val rdLength = ((reply[offset + 8].toInt() and 0xFF) shl 8) or (reply[offset + 9].toInt() and 0xFF)
            val rdStart = offset + 10
            if (rdStart + rdLength > reply.size) return result
            if (type == TYPE_A && rdLength == 4) {
                val ip = "${reply[rdStart].toInt() and 0xFF}." +
                    "${reply[rdStart + 1].toInt() and 0xFF}." +
                    "${reply[rdStart + 2].toInt() and 0xFF}." +
                    "${reply[rdStart + 3].toInt() and 0xFF}"
                if (CampusDnsPolicy.isUsableCampusAnswer(ip)) result += ip
            }
            offset = rdStart + rdLength
        }
        return result
    }

    /**
     * 跳过一个域名（可能是标签序列，也可能是压缩指针）。
     *
     * @return 名字**之后**的偏移；越界返回 null。
     */
    private fun skipName(data: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                // 压缩指针：两个字节，名字到此结束
                length and 0xC0 == 0xC0 -> return if (offset + 2 <= data.size) offset + 2 else null
                else -> offset += 1 + length
            }
        }
        return null
    }
}

/**
 * OkHttp 的 [Dns] 实现：满足前置条件时用学校 DNS，否则原样交给系统。
 *
 * @param system 系统 resolver（通常 `Dns.SYSTEM`）。
 * @param useVpn 本次通道；`true` 时不装本类（由 [WbuCampusNetwork.install] 保证），
 *   这里再兜一层，防止被误装进 WebVPN 客户端。
 * @param enabled 开关的**实时**求值器 —— 故意用 lambda 而不是 Boolean：
 *   若在建 client 时快照，用户关掉开关后必须杀进程才恢复，那会变成另一个坑。
 * @param campusReachable 校内可达性的**实时**求值器（内部有 60s 缓存）。
 */
internal class CampusDns(
    private val system: Dns = Dns.SYSTEM,
    private val useVpn: Boolean = false,
    private val enabled: () -> Boolean,
    private val campusReachable: () -> Boolean = { false }
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (!CampusDnsPolicy.shouldUseSchoolDns(
                host = hostname,
                useVpn = useVpn,
                campusReachable = campusReachableSafely(),
                enabled = enabledSafely()
            )
        ) {
            return system.lookup(hostname)
        }

        val addresses = SchoolDns.resolveA(hostname)
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

        if (addresses.isEmpty()) {
            Log.i(TAG, "学校 DNS 未给出可用地址，$hostname 回退系统解析")
            return system.lookup(hostname)
        }
        Log.i(TAG, "学校 DNS 解析 $hostname → ${addresses.joinToString { it.hostAddress.orEmpty() }}")
        return addresses
    }

    private fun enabledSafely(): Boolean = runCatching { enabled() }.getOrDefault(false)

    private fun campusReachableSafely(): Boolean = runCatching { campusReachable() }.getOrDefault(false)

    private companion object {
        const val TAG = "CampusDns"
    }
}

/**
 * 学校 DNS 的地址列表。
 *
 * **只用固定 IP `218.199.208.8`。**
 *
 * 曾打算把 `dns.wbu.edu.cn` 作为「学校换了 IP」的兜底，实测否掉了这条路：
 *
 * - 系统解析 `dns.wbu.edu.cn` → Clash fake-IP `28.0.0.5`（不是真实地址）；
 * - 拿 `28.0.0.5` 当 DNS 服务器查询，返回的是**公网映射** `183.95.36.17`
 *   （即 Clash 自己的解析结果，不是学校权威答案）；
 * - 而真实答案应该是 `218.199.208.8`。
 *
 * 也就是说：用系统解析去拿兜底地址，拿到的正是「被 fake-IP 污染的那一份」，
 * 兜底本身会变成新的故障点（还更隐蔽：它"能用"，但给的是死地址）。
 *
 * 所以宁可只写死一个 IP。它失效时表现为「回退系统解析」，即回到修复前的状态（有提示、可改用 WebVPN），
 * 不会更差；而加一个假兜底会让问题变成「有时能解析出公网死地址」。
 */
internal object SchoolDnsServers {

    fun resolve(): List<String> = listOf(CampusDnsPolicy.SCHOOL_DNS_PRIMARY)

    /** 便于诊断：学校 DNS 是否真的能查（不缓存，仅用于设置页 / 日志）。 */
    fun probe(host: String = CampusDnsPolicy.SCHOOL_DNS_HOSTNAME): Boolean =
        runCatching { SchoolDns.resolveA(host, resolve(), timeoutMs = 1500).isNotEmpty() }
            .getOrDefault(false)
}

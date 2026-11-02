package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response

/**
 * 「这次请求到底走到了哪里」的轻量记录，给设置页的「复制网络诊断」用。
 *
 * 为什么需要它：`ids.wbu.edu.cn` 在校内 DNS 下解析到内网 `172.18.0.123`（**不经过云 WAF**），
 * 在公网 / 蜂窝 DNS 下解析到 WAF 的 CDN 节点。同样一句「打水失败」，这两种情况的排查方向完全相反，
 * 而界面上看不出来。记下对端 IP，就能一眼分清是「校内直连失败」还是「过 WAF 失败」。
 *
 * 只记 host + path，**不记 query**（里面可能带 token / 票据）。
 */
object WbuNetworkDiagnostics {

    private const val MAX_RECORDS = 40

    private data class Record(
        val at: Long,
        val host: String,
        val path: String,
        val peerIp: String?,
        val code: Int?,
        val error: String?
    )

    /** 一次调用可能连多个 IP（重试 / 多地址），只留最后一个连上的。 */
    private val peerByCall = ConcurrentHashMap<Call, String>()

    private val records = ArrayDeque<Record>()

    /** 挂到 `OkHttpClient.Builder` 上；只记录，不改任何请求行为。 */
    val listener: EventListener = object : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            inetSocketAddress.address?.hostAddress?.let { peerByCall[call] = it }
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            add(call, peerByCall.remove(call), response.code, null)
        }

        override fun callFailed(call: Call, ioe: IOException) {
            add(call, peerByCall.remove(call), null, ioe::class.java.simpleName)
        }
    }

    private fun add(call: Call, peerIp: String?, code: Int?, error: String?) {
        val url = call.request().url
        val record = Record(System.currentTimeMillis(), url.host, url.encodedPath, peerIp, code, error)
        synchronized(records) {
            records.addLast(record)
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
    }

    /** 对端 IP 属于哪条路。判据只按私网段，不猜域名 —— 域名解析结果会随地而异。 */
    fun describePath(peerIp: String?): String = when {
        peerIp.isNullOrBlank() -> "未知（没连上）"
        isPrivateAddress(peerIp) -> "校内直连（未过云 WAF）"
        else -> "公网（可能经过云 WAF）"
    }

    /** `10/8`、`172.16/12`、`192.168/16` 都算内网。 */
    fun isPrivateAddress(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (!ip.startsWith("172.")) return false
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }

    /** 生成可复制的文本；最近一条在最上面。 */
    fun report(): String {
        val snapshot = synchronized(records) { records.toList() }
        if (snapshot.isEmpty()) return "暂无网络记录"

        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        return buildString {
            append("网络诊断（最近 ").append(snapshot.size).append(" 条；只记 host+path，不含参数）\n")
            for (record in snapshot.asReversed()) {
                append(fmt.format(Date(record.at))).append(' ')
                append(record.host).append(record.path)
                append(" → ")
                append(record.code?.toString() ?: "错误 ${record.error ?: "?"}")
                append(" @ ").append(record.peerIp ?: "-")
                append(" [").append(describePath(record.peerIp)).append("]\n")
            }
        }
    }

    fun clear() {
        synchronized(records) { records.clear() }
    }
}

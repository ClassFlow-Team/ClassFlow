package com.shiro.classflow.data.network.wbu

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 用证书自带的 AIA（Authority Information Access）地址把**缺失的中间证书**补上。
 *
 * ## 为什么需要它
 *
 * 实测（2026-11，校内直连）：`opac.wbu.edu.cn` 的 TLS 握手**只下发叶子证书** `*.wbu.edu.cn`，
 * 签发者 `WoTrus OV SSL CA` 那张中间证书没有一起发。浏览器照常能打开，因为 Chrome 会读叶子证书里的
 * `caIssuers` 地址、自己把中间证书下载回来补链；**OkHttp 不会做这件事**，于是系统校验直接失败
 * （`unable to get local issuer certificate` / `ssl_verify_result=20`）。
 *
 * 这个差异造成了一个很难查的现象：同一个地址「浏览器能进、App 进不去」，而 App 侧只能看到一句
 * `SSLHandshakeException`，被上层归类成「网络不通」→ 界面显示「当前不在校园网」，
 * 明明 DNS 解析到了校内 `172.21.1.5`、教务和统一认证也都好好的。
 *
 * ## 为什么这是安全的
 *
 * 补全之后仍然交给**系统默认的 TrustManager** 重新校验整条链 —— 只是把「下载中间证书」这一步
 * 代做了，信任判定一个字都没放宽。下载地址来自叶子证书自己声明的 AIA 扩展，而那张叶子证书
 * 必然已经过签名验证（因为它就是要进链的那张）。
 *
 * 只在**校验失败时**才发起网络请求，校验能过的站点（绝大多数）完全不受影响。
 */
internal object WbuAiaChainCompleter {

    private const val TAG = "WbuAiaChain"

    /** `id-pe-authorityInfoAccess`。 */
    private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

    /**
     * 最多补几张。
     *
     * 正常只缺一张中间证书；留到 3 是为了容忍「中间证书本身也是由另一张中间证书签发」的层级
     * （部分国内 CA 会这样）。再多就说明不是补链能解决的问题，直接放弃、把原异常抛回去。
     */
    private const val MAX_CHAIN_EXTENSIONS = 3

    /** 专用于下载中间证书：短超时，且**不挂**业务 client（避免递归回自己的 TrustManager）。 */
    private val downloader: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 尝试补全 [chain] 并用 [delegate] 重新校验。
     *
     * @return 校验通过的完整链；补不出来 / 补完仍然不通过 → null（调用方应抛出原始异常）。
     */
    fun completeAndVerify(
        delegate: X509TrustManager,
        chain: Array<X509Certificate>,
        authType: String
    ): Array<X509Certificate>? {
        var current = chain
        repeat(MAX_CHAIN_EXTENSIONS) {
            val last = current.lastOrNull() ?: return null
            val uri = caIssuersUri(last) ?: return null
            val issuer = downloadCertificate(uri) ?: return null
            // 已经在这条链里（或服务端换了一张同样的证书）：再补也补不出新东西
            if (current.any { it.encoded.contentEquals(issuer.encoded) }) return null

            current = current + issuer
            // 补一张就试一次：大多数情况第一步就成功，不必把整条链补完
            if (runCatching { delegate.checkServerTrusted(current, authType) }.isSuccess) {
                Log.i(
                    TAG,
                    "证书链已补全（${chain.size} → ${current.size}），叶子证书：" +
                        chain.firstOrNull()?.subjectX500Principal?.name
                )
                return current
            }
        }
        return null
    }

    /**
     * 从证书的 AIA 扩展里取第一张中间证书的 `caIssuers` 地址。
     *
     * 直接按 DER 结构扫 `[6] IA5String`（`GeneralName ::= uniformResourceIdentifier [6] IA5String`，
     * 隐含标签 `0x86`），而不是把二进制硬转成字符串再正则 —— 后者会把后面 TLV 的标签字节
     * 当成 URL 的一部分（那些字节不少是可打印 ASCII），拼出一个根本不存在的地址。
     */
    internal fun caIssuersUri(cert: X509Certificate): String? {
        val raw = runCatching { cert.getExtensionValue(AIA_OID) }.getOrNull() ?: return null
        return uriFromAiaExtension(raw)
    }

    /** `id-ad-caIssuers`：`1.3.6.1.5.5.7.48.2`。**必须按它匹配**，不能取第一个 URL。 */
    private val CA_ISSUERS_OID = byteArrayOf(
        0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02
    )

    /**
     * 从证书的 AIA 扩展里取中间证书的 `caIssuers` 地址。
     *
     * **不能简单取第一个 URL** —— 实测这张证书的 AIA 里 OCSP 排在前面：
     * ```
     * OCSP      - URI: http://wotrus-ovca.ocsp-certum.com
     * CA Issuers - URI: http://repository.certum.pl/wotrus-ovca.cer
     * ```
     * 取错的话会去下载一个「OCSP 响应器地址」，那不是证书，补链必然失败。
     * 所以按 `AccessDescription` 的结构遍历：每个条目是 `SEQUENCE { accessMethod OID, accessLocation [6] IA5String }`，
     * 只接受 `accessMethod == id-ad-caIssuers` 的那一条。
     *
     * 解析按 DER 结构走，不把二进制硬转成字符串再正则 —— 后者会把后面 TLV 的标签字节
     * 当成 URL 的一部分，拼出一个根本不存在的地址。
     */
    internal fun uriFromAiaExtension(raw: ByteArray): String? {
        // 跳过外层 OCTET STRING，找到里面的 SEQUENCE
        var i = 0
        while (i < raw.size - 1) {
            if (raw[i] != 0x30.toByte()) {
                i++
                continue
            }
            val seqInfo = readLength(raw, i + 1)
            if (seqInfo == null) { i++; continue }
            val seqLen = seqInfo.first
            val seqStart = seqInfo.second
            if (seqLen <= 0 || seqStart + seqLen > raw.size) {
                i++
                continue
            }
            val end = seqStart + seqLen
            // 遍历 SEQUENCE 里的每个 AccessDescription
            var p = seqStart
            while (p < end - 1) {
                if (raw[p] != 0x30.toByte()) { p++; continue }
                val descInfo = readLength(raw, p + 1) ?: break
                val descLen = descInfo.first
                val descStart = descInfo.second
                if (descLen <= 0 || descStart + descLen > end) break
                val descEnd = descStart + descLen

                // accessMethod OID 必须正好是 id-ad-caIssuers
                val isCaIssuers = descStart + CA_ISSUERS_OID.size <= descEnd &&
                    CA_ISSUERS_OID.indices.all { raw[descStart + it] == CA_ISSUERS_OID[it] }
                if (isCaIssuers) {
                    val uri = readGeneralNameUri(raw, descStart + CA_ISSUERS_OID.size, descEnd)
                    if (uri != null) return uri
                }
                p = descEnd
            }
            i = end
        }
        return null
    }

    /** 读 DER 长度字段，返回 (长度, 内容起始下标)。 */
    private fun readLength(raw: ByteArray, offset: Int): Pair<Int, Int>? {        if (offset >= raw.size) return null
        var length = raw[offset].toInt() and 0xFF
        var cursor = offset + 1
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            if (count !in 1..2 || cursor + count > raw.size) return null
            length = 0
            for (k in 0 until count) {
                length = (length shl 8) or (raw[cursor + k].toInt() and 0xFF)
            }
            cursor += count
        }
        return length to cursor
    }

    /** 在 [start, end) 之间找一个 `[6] IA5String`（隐含标签 `0x86`）并解出 URL。 */
    private fun readGeneralNameUri(raw: ByteArray, start: Int, end: Int): String? {
        var i = start
        while (i < end - 1) {
            if ((raw[i].toInt() and 0xFF) == 0x86) {
                val nameInfo = readLength(raw, i + 1) ?: return null
                val length = nameInfo.first
                val contentStart = nameInfo.second
                if (length in 1..2048 && contentStart + length <= end) {
                    val uri = String(raw, contentStart, length, Charsets.US_ASCII)
                    if (uri.startsWith("http", ignoreCase = true)) return uri
                }
            }
            i++
        }
        return null
    }

    /** 下载并解析中间证书；`caIssuers` 可能是裸 DER，也可能是 PKCS#7 包。 */
    private fun downloadCertificate(uri: String): X509Certificate? = runCatching {
        val request = Request.Builder().url(uri).get().build()
        downloader.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            parseCertificate(bytes)
        }
    }.onFailure {
        Log.w(TAG, "下载中间证书失败：$uri（${it.javaClass.simpleName}）")
    }.getOrNull()

    private fun parseCertificate(bytes: ByteArray): X509Certificate? {
        val factory = CertificateFactory.getInstance("X.509")
        // 先按单张 DER 证书解析（绝大多数 CA 返回的就是这个）
        runCatching {
            (factory.generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate)?.let { return it }
        }
        // 再按 PKCS#7 / 证书链解析
        runCatching {
            factory.generateCertificates(ByteArrayInputStream(bytes))
                .filterIsInstance<X509Certificate>()
                .firstOrNull()
                ?.let { return it }
        }
        return null
    }
}

/**
 * 带 AIA 补链能力的 TrustManager：先用系统默认策略校验，失败才尝试补一张中间证书再校验。
 *
 * 包一层而不是重写判定逻辑，是为了保证**信任结论永远来自系统 TrustManager** —— 这里唯一的
 * 额外动作是「把服务端漏发的中间证书下载回来」，不做任何放宽。
 */
internal class WbuChainCompletingTrustManager(
    private val delegate: X509TrustManager
) : X509ExtendedTrustManager() {

    /** 先按系统策略校验；只有「链不完整 / 不受信」才尝试补中间证书。 */
    private fun serverTrusted(chain: Array<X509Certificate>, authType: String, extra: Any?) {
        val extended = delegate as? X509ExtendedTrustManager
        try {
            when (extra) {
                null -> delegate.checkServerTrusted(chain, authType)
                is Socket -> extended?.checkServerTrusted(chain, authType, extra)
                    ?: delegate.checkServerTrusted(chain, authType)
                is SSLEngine -> extended?.checkServerTrusted(chain, authType, extra)
                    ?: delegate.checkServerTrusted(chain, authType)
                else -> delegate.checkServerTrusted(chain, authType)
            }
        } catch (e: CertificateException) {
            // 补不出完整链就把原始异常原样抛出：异常类型与文案保持不变，
            // 上层照旧归类成「证书有问题」，不会因为这一层包装而变成「网络不通」。
            if (WbuAiaChainCompleter.completeAndVerify(delegate, chain, authType) == null) throw e
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        serverTrusted(chain, authType, null)

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket
    ) = serverTrusted(chain, authType, socket)

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine
    ) = serverTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket
    ) = (delegate as? X509ExtendedTrustManager)
        ?.checkClientTrusted(chain, authType, socket)
        ?: delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine
    ) = (delegate as? X509ExtendedTrustManager)
        ?.checkClientTrusted(chain, authType, engine)
        ?: delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

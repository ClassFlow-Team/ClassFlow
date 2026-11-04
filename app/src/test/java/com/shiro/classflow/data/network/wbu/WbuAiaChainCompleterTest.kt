package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 证书 AIA 地址解析单测。
 *
 * 钉住的事故（2026-11 实测）：`opac.wbu.edu.cn` 的 TLS 握手**只下发叶子证书**，
 * 签发者 `WoTrus OV SSL CA` 的中间证书没一起发。Chrome 会按叶子证书的 AIA `caIssuers`
 * 地址自己补链，OkHttp 不会 —— 于是「浏览器能打开图书馆、App 报连不上（还显示成不在校园网）」。
 *
 * 补链的关键第一步是从叶子证书里读出那个下载地址。这段逻辑按 DER 结构扫 `[6] IA5String`
 * （标签 `0x86`），不能把二进制硬转字符串再正则 —— 那样会把后续 TLV 的标签字节
 * 当成 URL 的一部分，拼出一个不存在的地址。
 */
class WbuAiaChainCompleterTest {

    /**
     * 按 ASN.1 组一个最小的 AIA 扩展值：
     * `OCTET STRING { SEQUENCE { SEQUENCE { accessMethod OID, accessLocation [6] IA5String } } }`。
     *
     * 注意是**两层** SEQUENCE —— 外层包住所有 `AccessDescription`，每个条目自己也是一层 SEQUENCE。
     * 漏掉外层就会变成一个「把 OID 直接摊在 SEQUENCE 里」的畸形结构，真实证书不长这样。
     */
    private fun aiaExtension(uri: String): ByteArray {
        val accessDescription = aiaAccessDescription(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02),
            uri
        )
        val seq = byteArrayOf(0x30) + derLength(accessDescription.size) + accessDescription
        return byteArrayOf(0x04) + derLength(seq.size) + seq
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }

    @Test
    fun extractsCaIssuersUriFromRealWorldExtension() {
        // 实测：证书里的 CA Issuers 就是这条
        val uri = "http://repository.certum.pl/wotrus-ovca.cer"
        assertEquals(uri, WbuAiaChainCompleter.uriFromAiaExtension(aiaExtension(uri)))
    }

    @Test
    fun extractsHttpsUri() {
        val uri = "https://cacerts.example.com/intermediate.crt"
        assertEquals(uri, WbuAiaChainCompleter.uriFromAiaExtension(aiaExtension(uri)))
    }

    @Test
    fun returnsNullWhenExtensionHasNoUri() {
        // 只有 OCSP 条目（id-ad-ocsp）没有 caIssuers：必须返回 null，
        // 绝不能把 OCSP 响应器地址当成证书下载地址 —— 实测这张证书的 AIA 里 OCSP 排在前面。
        val ocspOnly = aiaExtensionForOid(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01),
            "http://wotrus-ovca.ocsp-certum.com"
        )
        assertNull(WbuAiaChainCompleter.uriFromAiaExtension(ocspOnly))
    }

    @Test
    fun ocspEntryBeforeCaIssuersIsSkipped() {
        // 关键回归：真实证书就是这个顺序 —— OCSP 在前、CA Issuers 在后。
        // 只取「第一个 URL」会拿到 OCSP 地址，补链必然失败。
        val ocsp = aiaAccessDescription(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01),
            "http://wotrus-ovca.ocsp-certum.com"
        )
        val caIssuers = aiaAccessDescription(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02),
            "http://repository.certum.pl/wotrus-ovca.cer"
        )
        val seq = byteArrayOf(0x30) + derLength(ocsp.size + caIssuers.size) + ocsp + caIssuers
        val extension = byteArrayOf(0x04) + derLength(seq.size) + seq
        assertEquals(
            "http://repository.certum.pl/wotrus-ovca.cer",
            WbuAiaChainCompleter.uriFromAiaExtension(extension)
        )
    }

    private fun aiaAccessDescription(oid: ByteArray, uri: String): ByteArray {
        val uriBytes = uri.toByteArray(Charsets.US_ASCII)
        val generalName = byteArrayOf(0x86.toByte()) + derLength(uriBytes.size) + uriBytes
        return byteArrayOf(0x30) + derLength(oid.size + generalName.size) + oid + generalName
    }

    private fun aiaExtensionForOid(oid: ByteArray, uri: String): ByteArray {
        val desc = aiaAccessDescription(oid, uri)
        val seq = byteArrayOf(0x30) + derLength(desc.size) + desc
        return byteArrayOf(0x04) + derLength(seq.size) + seq
    }

    @Test
    fun returnsNullForGarbage() {
        assertNull(WbuAiaChainCompleter.uriFromAiaExtension(ByteArray(0)))
        assertNull(WbuAiaChainCompleter.uriFromAiaExtension(byteArrayOf(0x04, 0x00)))
    }

    @Test
    fun doesNotBuildUriFromTrailingTlvBytes() {
        // 关键回归：单纯把二进制转成字符串再正则，会把后面的标签字节也吸进 URL。
        // 这里在 URI 之后追加一段二进制，解析结果必须仍然只到 URI 结尾。
        val uri = "http://repository.certum.pl/wotrus-ovca.cer"
        val payload = aiaExtension(uri) + byteArrayOf(0x30, 0x03, 0x86.toByte(), 0x01, 0x00)
        assertEquals(uri, WbuAiaChainCompleter.uriFromAiaExtension(payload))
    }

    @Test
    fun skipsNonCaIssuersEntries() {
        // 真实证书的顺序就是「OCSP 在前、CA Issuers 在后」；
        // 解析器必须跳过 OCSP 那条，只认 id-ad-caIssuers。
        val ocsp = aiaAccessDescription(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01),
            "http://wotrus-ovca.ocsp-certum.com"
        )
        val caIssuers = aiaAccessDescription(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02),
            "http://repository.certum.pl/wotrus-ovca.cer"
        )
        val seq = byteArrayOf(0x30) + derLength(ocsp.size + caIssuers.size) + ocsp + caIssuers
        val extension = byteArrayOf(0x04) + derLength(seq.size) + seq
        assertEquals(
            "http://repository.certum.pl/wotrus-ovca.cer",
            WbuAiaChainCompleter.uriFromAiaExtension(extension)
        )
    }
}

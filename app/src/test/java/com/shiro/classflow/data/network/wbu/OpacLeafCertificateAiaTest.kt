package com.shiro.classflow.data.network.wbu

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实证书**验证 AIA 地址解析。
 *
 * 钉住的事故（2026-11 实测）：`opac.wbu.edu.cn` 的 TLS 握手只下发叶子证书，
 * 签发者 `WoTrus OV SSL CA` 的中间证书没一起发，于是 OkHttp 校验失败
 * （`unable to get local issuer certificate`），而 Chrome 会自己按 AIA 补链 ——
 * 「浏览器能进、App 报不在校园网」。
 *
 * 这份 DER 是从校内那台服务器上取的叶子证书原件（`openssl s_client` + `x509 -outform der`）。
 * 用它跑解析器，能保证「真实 AIA 扩展结构」这条路是通的，而不是只在自造的字节上跑得通。
 */
class OpacLeafCertificateAiaTest {

    private fun loadLeaf(): X509Certificate {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("opac_leaf.der")) {
            "缺少测试资源 opac_leaf.der"
        }.use { it.readBytes() }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    @Test
    fun leafCertificateIsTheRealOpacCertificate() {
        val leaf = loadLeaf()
        assertTrue(
            "叶子证书应覆盖 *.wbu.edu.cn，实际：${leaf.subjectX500Principal.name}",
            leaf.subjectX500Principal.name.contains("wbu.edu.cn", ignoreCase = true)
        )
    }

    @Test
    fun caIssuersUriIsExtractedFromTheRealCertificate() {
        // 解析器必须在真实扩展结构上给出可下载的地址，补链才可能成功
        val uri = WbuAiaChainCompleter.caIssuersUri(loadLeaf())
        assertNotNull("真实叶子证书里应该能读到 caIssuers 地址", uri)
        assertEquals("http://repository.certum.pl/wotrus-ovca.cer", uri)
    }
}

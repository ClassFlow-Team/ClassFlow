package com.shiro.classflow.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.BuildConfig

/**
 * `classflow://` 自定义协议的规范化。
 *
 * 自定义 scheme 不依赖域名验证与签名证书，适合 GitHub 自建包；路径与现有 https 路由保持一致：
 *
 * ```text
 * classflow://w/{cd}          -> https://<hub>/w/{cd}
 * classflow://wm/{uuid}       -> https://<hub>/wm/{uuid}
 * classflow://hd/{cd}         -> https://<hub>/hd/{cd}
 * classflow://s/y/{机号}       -> https://<hub>/s/y/{机号}
 * classflow://url/{code}#{p}  -> https://<hub>/url/{code}#{p}
 * classflow://u/#{p}          -> https://<hub>/u/#{p}
 * classflow:///w/{cd}         -> https://<hub>/w/{cd}
 * ```
 *
 * 规范化后直接交给 [CampusLinkRouter] / [UjingQrLink] / [com.shiro.classflow.data.network.link.LinkHubUrl]，
 * 不复制第二套解析逻辑。
 */
object ClassFlowScheme {

    const val SCHEME = "classflow"

    /** 只允许进入现有路由的命名空间；更细的格式由各路由自己校验。 */
    private val ALLOWED_FIRST_SEGMENTS = setOf("w", "wm", "hd", "s", "url", "u")

    /** 是否为 `classflow:` 协议（大小写不敏感）。 */
    fun isClassFlowUri(raw: String): Boolean {
        val trimmed = raw.trim()
        val schemeEnd = trimmed.indexOf(':')
        return schemeEnd > 0 && trimmed.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)
    }

    /**
     * 把 `classflow://...` 规范化为现有 https 路由可消费的字符串。
     *
     * 非 `classflow:`、非法 authority（userinfo / 端口）、未知路径首段一律返回 null。
     */
    fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        val schemeEnd = trimmed.indexOf(':')
        if (schemeEnd <= 0) return null
        if (!trimmed.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return null

        var rest = trimmed.substring(schemeEnd + 1)
        // 必须是 // 形式；单斜杠 classflow:/w/... 不接受
        if (!rest.startsWith("//")) return null
        rest = rest.substring(2)

        // 先拆 fragment / query，保证原样保留
        val hashIndex = rest.indexOf('#')
        val fragment = if (hashIndex >= 0) rest.substring(hashIndex + 1) else null
        val beforeHash = if (hashIndex >= 0) rest.substring(0, hashIndex) else rest

        val queryIndex = beforeHash.indexOf('?')
        val query = if (queryIndex >= 0) beforeHash.substring(queryIndex + 1) else null
        val authorityAndPath = if (queryIndex >= 0) beforeHash.substring(0, queryIndex) else beforeHash

        val path = when {
            // classflow:///w/...：host 为空，后面整体就是路径
            authorityAndPath.startsWith("/") -> authorityAndPath

            else -> {
                val slashIndex = authorityAndPath.indexOf('/')
                val hostPart = if (slashIndex >= 0) {
                    authorityAndPath.substring(0, slashIndex)
                } else {
                    authorityAndPath
                }
                // 拒绝 userinfo 与端口
                if (hostPart.contains('@') || hostPart.contains(':')) return null
                val tail = if (slashIndex >= 0) authorityAndPath.substring(slashIndex) else ""
                "/$hostPart$tail"
            }
        }

        if (!path.startsWith("/") || path.startsWith("//")) return null

        val withoutLeadingSlash = path.substring(1)
        val firstSegment = withoutLeadingSlash.substringBefore('/')
        if (firstSegment.lowercase() !in ALLOWED_FIRST_SEGMENTS) return null

        // 路径首段统一小写（设备码 / code 保持原样）
        val normalizedPath = "/" + firstSegment.lowercase() + withoutLeadingSlash.substring(firstSegment.length)

        return buildString {
            append("https://").append(BuildConfig.LINK_HUB_HOST).append(normalizedPath)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
    }
}

package com.shiro.classflow.ui.webapp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * 网页应用容器里与「微信 / 统一身份认证中转」有关的纯函数集合。
 *
 * 背景（实测，见移动门户研究报告）：
 * 1. 有些目标只有微信客户端里才走得通 —— 打开它是微信自己的「请在微信客户端打开链接」报错页
 *    （`open.weixin.qq.com/connect/oauth2/authorize?...`、企业微信 `open.work.weixin.qq.com/...`），
 *    客户端无法代替微信完成授权，只能把这一页换成我们自己的说明页（提示 + 复制链接 + 返回）；
 * 2. 另有一类**看起来要微信、其实不必**：统一身份认证的企业微信中转
 *    `ids.wbu.edu.cn/authserver/weixinQYLogin.do?qyId=…&success=…`，
 *    它的落点本身就是一个 CAS 登录地址（`success=…/authserver/login?service=<业务系统>`），
 *    也就是说「先微信、后统一认证」——我们手里有 `CASTGC`，直接替它把后半段走完即可，
 *    完全没有必要去微信转一圈；
 * 3. 网页自己跳到 CAS 登录页（`/authserver/login`）也属于正常流程：那是业务系统在要票据，
 *    而不是我们的会话失效了（票据桥见 `WebAppScreen` 与 `WebAppDefinition.casTicketBridge`）。
 *
 * 这里只做 URL 的识别与解析，不碰网络、不碰 WebView，便于单测覆盖。
 */
object WebAppWechat {

    /** 微信开放平台的 OAuth 授权页宿主。 */
    private const val WECHAT_OPEN_HOST = "open.weixin.qq.com"

    /** 企业微信（微信工作台）授权页宿主。 */
    private const val WECHAT_WORK_HOST = "open.work.weixin.qq.com"

    /** 统一身份认证的企业微信中转接口路径。 */
    private const val WEIXIN_QY_LOGIN_PATH = "/authserver/weixinQYLogin.do"

    /**
     * 是不是「必须微信客户端」的授权页（`open.weixin.qq.com` 的 `connect` 路径下、
     * `open.work.weixin.qq.com` 全域）。
     *
     * `open.weixin.qq.com` 上并非所有路径都是授权入口（还有下载页、帮助页之类），
     * 因此只拦 `/connect/` 下的 `oauth2/authorize`、`qrconnect` 等同族路径。
     */
    fun isWechatAuthorizeUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase()
        val path = parsed.encodedPath.lowercase()
        return when (host) {
            WECHAT_OPEN_HOST -> path.startsWith("/connect/")
            WECHAT_WORK_HOST -> true
            else -> false
        }
    }

    /**
     * 是不是统一身份认证的企业微信中转（`…/authserver/weixinQYLogin.do?…`）。
     *
     * 只有宿主是统一身份认证时才认；
     * 认出来之后由 [parseSuccessTarget] 决定是「跳过微信」还是「弹统一页」。
     */
    fun isWeixinQyLoginUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!isIdsHost(parsed.host)) return false
        return parsed.encodedPath.startsWith(WEIXIN_QY_LOGIN_PATH)
    }

    /**
     * 是不是统一身份认证 / WebVPN 门户的登录页。
     *
     * 与既有行为一致：`/authserver/login`（CAS）与 `/por/login`（WebVPN 门户）都算。
     */
    fun isCasLoginUrl(url: String): Boolean =
        url.contains("/authserver/login") || url.contains("/por/login")

    /**
     * 解析企业微信中转的 `success` 参数：中转完成后要落到的地址。
     *
     * 实测形态（`success` **未编码**、且是最后一个参数，值里自带 `?service=…`）：
     * ```
     * …/authserver/weixinQYLogin.do?qyId=wx5a34af927450044e&success=http://ids.wbu.edu.cn/authserver/login?service=http://zxst.wbu.edu.cn:6060/Web/CASDefault
     * ```
     * 因为值里带裸 `?` 与可能的裸 `&`，这里**取 `success=` 之后的整段**，不按 `&` 切；
     * 同时兼容被 URL 编码（甚至二次编码）的写法，解码到能看出 `://` 为止。
     *
     * @return 落地地址；没有 `success` 参数时返回 null（调用方改弹微信统一页）。
     */
    fun parseSuccessTarget(url: String): String? = paramValue(url, "success")

    /**
     * 从 CAS 登录地址里取 `service` 参数（业务系统登记的核销地址）。
     *
     * @return 解码后的业务系统地址；没有该参数时返回 null。
     */
    fun casServiceOf(url: String): String? = paramValue(url, "service")

    /**
     * 统一页「复制链接」的复制内容。
     *
     * 优先级：**当前停留的正经页面**（非 `open.weixin.qq.com` 那类微信自己的页面）→ 被拦下来的地址。
     * 理由是用户要的往往是「回微信里打开那个应用」，而门户里的应用入口
     * （如 `yktyd.wbu.edu.cn/wechat/url/redirectJkbh.html?jkbh=…`）复制出去在微信里能直接走通，
     * 复制那条 `…/connect/oauth2/authorize?…` 反而多半因为会话绑定而失效。
     */
    fun pickCopyUrl(pageUrl: String?, blockedUrl: String): String {
        val page = pageUrl?.trim().orEmpty()
        // 只认正经网页（http/https）：`about:blank`、`data:` 这类复制出去没有意义
        val usable = page.isNotBlank() && !isWechatAuthorizeUrl(page) && page.toHttpUrlOrNull() != null
        return if (usable) page else blockedUrl
    }

    /** 取 URL 的宿主（解析不出来时返回 null），供统一页显示「目标站点」。 */
    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }

    /**
     * 便宜的前置判断：`shouldInterceptRequest` 会对每个子资源（图片 / JS / CSS）都过一遍，
     * 先用字面量快速筛掉绝大多数请求，再交给 [isWechatAuthorizeUrl] / [isWeixinQyLoginUrl] 精确判定。
     */
    fun looksLikeWechatGate(url: String): Boolean {
        if (!url.contains("weixin", ignoreCase = true)) return false
        return url.contains("/connect/", ignoreCase = true) ||
            url.contains("open.work.weixin", ignoreCase = true) ||
            url.contains("weixinQYLogin", ignoreCase = true)
    }

    /** 统一身份认证的宿主（含 WebVPN 代理子域，如 `ids-wbu-edu-cn.webvpn.wbu.edu.cn`）。 */
    private fun isIdsHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "ids.wbu.edu.cn" || lower.startsWith("ids-wbu-edu-cn.") || lower.startsWith("ids.")
    }

    /**
     * 取参数值，兼容「原样 URL」与「URL 编码」两种写法：
     * ```
     * ?service=http://ehall.wbu.edu.cn/x/index.do       → 原样，整段就是值
     * ?service=http%3A%2F%2Fehall.wbu.edu.cn%2Fx%2F...  → 编码，解码后是值
     * ```
     * 原样写法里值本身可能还带着 `?a=b&c=d`，所以**不能**无脑按 `&` 切。
     */
    private fun paramValue(url: String, name: String): String? {
        val marker = "$name="
        val index = url.indexOf(marker, ignoreCase = true)
        if (index < 0) return null

        val tail = url.substring(index + marker.length).trim()
        if (tail.isEmpty()) return null

        // 1) 原样 URL：整段就是值（去掉可能的 fragment 与首尾空白）
        if (tail.contains("://")) return tail.substringBefore('#').trim().takeIf { it.isNotBlank() }

        // 2) 编码过（最多叠三层编码）：解到能看出 `://` 为止
        var decoded = tail
        repeat(3) {
            val next = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
            if (next == decoded) return@repeat
            decoded = next
            if (decoded.contains("://")) {
                return decoded.substringBefore('#').trim().takeIf { it.isNotBlank() }
            }
        }

        // 3) 不是绝对地址（相对路径之类）：按 `&` 切出第一段
        return decoded.substringBefore('&').substringBefore('#').trim().takeIf { it.isNotBlank() }
    }
}

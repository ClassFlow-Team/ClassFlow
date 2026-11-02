package com.xingheyuzhuan.shiguangschedule.data.model.wbu

import androidx.annotation.StringRes
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 网页应用标识枚举。
 */
enum class WebAppId {
    /** 图书馆座位预约系统 (jsq-v)。 */
    LIBRARY_SEAT,

    /** 一卡通移动服务平台（慧新e校）。 */
    CAMPUS_CARD,

    /** 移动门户（ydmh，uni-app 单页应用）：校内办事与各业务系统的聚合入口。 */
    MOBILE_PORTAL
}

/**
 * 启动模式：决定进入容器前如何用统一身份认证把登录态办妥。
 */
enum class WebAppLaunchMode {
    /**
     * 既有：向业务 SSO 核销 ticket，取回 JWT，再以 `?token=` 打开前端页面（图书馆座位预约）。
     */
    CAS_TOKEN,

    /**
     * 新增：只把 CAS 的 Service Ticket 交给页面，由业务端自己核销并下发会话 Cookie（门户类）。
     *
     * 与 [CAS_TOKEN] 的区别是**不在客户端核销票据**：票据留在 WebView 里核销，
     * 会话 Cookie 就落进 WebView 自己的 Cookie 罐，页面后续的跳转与 XHR 全都带着它。
     */
    CAS_SERVICE_TICKET
}

/**
 * 网页应用元数据定义。
 *
 * 适用于以统一身份认证 (CAS) 为主、且页面具有自有完整导航与标题栏的校园全屏网页系统。
 */
data class WebAppDefinition(
    val id: WebAppId,
    @StringRes val titleRes: Int,
    /** 公网真实域名/Origin，如 `https://libseat.wbu.edu.cn`。 */
    val realOrigin: String,
    /** 目标主机的 Host 字段，如 `libseat.wbu.edu.cn`。 */
    val targetHost: String,
    /** 前端网页应用根路径，如 `/jsq-v/`。 */
    val appPath: String,
    /** CAS SSO 入口路径，用于在换取 token 前向业务 SSO 服务初始化会话（如关联 session），可为 null。 */
    val remSsoLoginPath: String? = null,
    /** 业务端在 CAS 注册的真实 Service 回调地址，CAS 在验证通过后回跳到此接口核销 ticket 并签发 JWT。 */
    val casServiceUrl: String,
    /** 默认的首页 Hash 路由，如 `#/main/index`。 */
    val defaultHash: String = "",
    /** 用于判定是否处于主页的 Hash 标记前缀集合。在这些页面触发系统返回键将直接退出容器，而不是在内部回退。 */
    val homeHashMarkers: List<String> = emptyList(),
    /**
     * 平台主页的绝对地址。当页面被第三方 H5（如 U净）接管后，返回键将回到此地址，
     * 避免在其 OAuth 回调链上「回退 → 被重新跳转」形成死循环。
     */
    val homeUrl: String? = null,
    /** 默认在 WebVPN 代理时是否包含 `-s` 单机后缀。 */
    val vpnSingleSuffix: Boolean = true,
    /** 是否属于外网直连服务（无需校园网环境，也不经由 WebVPN）。 */
    val directOnly: Boolean = false,
    /** 启动模式，见 [WebAppLaunchMode]。 */
    val launchMode: WebAppLaunchMode = WebAppLaunchMode.CAS_TOKEN,
    /**
     * 允许「CAS 票据桥」：WebView 内跳向统一身份认证登录页时，不再一味当作「会话失效」，
     * 而是先用本机 `CASTGC` 换一张指向目标业务系统的票据、把 CAS 回跳地址交给 WebView。
     *
     * 门户里的应用（ehall / 新生入馆教育 …）都由 Apache `MOD_AUTH_CAS` 保护，页面自己跳 CAS
     * 登录页是**正常流程**：换票落地后业务端会下发自己的会话，用户不必再登录一次。
     */
    val casTicketBridge: Boolean = false,
    /**
     * 把页面里的 `window.open(url)` 改成当前窗口跳转。
     *
     * 门户（uni-app）用 `window.open` 打开各业务应用；Android WebView 默认不支持多窗口，
     * 新窗口请求可能被直接丢掉（表现为点应用没反应），而且就算真开了新窗口，
     * 新窗口里的跳转也不会经过本容器的拦截规则（微信页、CAS 票据桥都会失效）。
     * 因此在把跳转交还给页面之外，还得让它落回主框架导航。
     */
    val shimWindowOpen: Boolean = false,
    /**
     * 「首页」判定补一条：本应用**自己站点**上 hash 为空（或就是 `#/`）时也算处于首页。
     *
     * hash 路由的单页应用刚加载完正是这个状态（实测门户停在 `…/index.html#/`，标题就是「首页」），
     * 此时按返回键应当退出容器，而不是退回到换票那一跳的地址再被跳一次。
     *
     * 只对本应用自己的宿主生效：门户里打开的业务系统（ehall / 入馆教育 …）是站外页面，
     * 它们的 hash 空不空与「门户首页」无关，按返回键仍应照常回退（见 `isAtHomeRoute`）。
     */
    val blankHashIsHome: Boolean = false
)

/**
 * 网页应用目录注册表。
 */
object WebAppCatalog {
    val LIBRARY_SEAT = WebAppDefinition(
        id = WebAppId.LIBRARY_SEAT,
        titleRes = R.string.item_library_seat,
        realOrigin = "https://libseat.wbu.edu.cn",
        targetHost = "libseat.wbu.edu.cn",
        appPath = "/jsq-v/",
        remSsoLoginPath = "/rem/static/sso/login",
        casServiceUrl = "https://libseat.wbu.edu.cn/rem/static/sso/webOAuthRed",
        defaultHash = "#/main/index",
        homeHashMarkers = listOf("#/main/index", "#/main/home"),
        vpnSingleSuffix = true,
        directOnly = false
    )

    val CAMPUS_CARD = WebAppDefinition(
        id = WebAppId.CAMPUS_CARD,
        titleRes = R.string.service_campus_card,
        realOrigin = "http://yktfwpt.wbu.edu.cn",
        targetHost = "yktfwpt.wbu.edu.cn",
        appPath = "/plat/",
        remSsoLoginPath = null,
        casServiceUrl = "http://yktfwpt.wbu.edu.cn/berserker-auth/cas/oauth2url",
        defaultHash = "",
        homeHashMarkers = listOf("shouyeUser", "/plat/shouyeUser"),
        homeUrl = "http://yktfwpt.wbu.edu.cn/plat/shouyeUser",
        vpnSingleSuffix = false,
        directOnly = true
    )

    /**
     * 移动门户（`https://ydmh.wbu.edu.cn/ydmh/`，uni-app 单页应用）。
     *
     * 与其它网页应用不同的三点：
     * 1. 登录 service 固定为 `https://ydmh.wbu.edu.cn/ydmh/login/cas`，核销后门户下发自己的
     *    `SESSION` Cookie（不签发 JWT），所以走 [WebAppLaunchMode.CAS_SERVICE_TICKET]；
     * 2. 门户里的应用由 `window.open` 打开，且大多是外部系统（ehall / zxst …），
     *    它们自己跳 CAS 登录页属于正常流程，交给 [casTicketBridge] 就地换票；
     * 3. 页面路由是 hash 路由（首页 `#/pages/home/index`），返回键据此判定是否退出容器。
     */
    val MOBILE_PORTAL = WebAppDefinition(
        id = WebAppId.MOBILE_PORTAL,
        titleRes = R.string.item_mobile_portal,
        realOrigin = "https://ydmh.wbu.edu.cn",
        targetHost = "ydmh.wbu.edu.cn",
        appPath = "/ydmh/",
        remSsoLoginPath = null,
        casServiceUrl = "https://ydmh.wbu.edu.cn/ydmh/login/cas",
        defaultHash = "#/pages/home/index",
        homeHashMarkers = listOf("/pages/home/index"),
        homeUrl = "https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html#/pages/home/index",
        vpnSingleSuffix = true,
        directOnly = false,
        launchMode = WebAppLaunchMode.CAS_SERVICE_TICKET,
        casTicketBridge = true,
        shimWindowOpen = true,
        blankHashIsHome = true
    )

    private val catalog = listOf(LIBRARY_SEAT, CAMPUS_CARD, MOBILE_PORTAL).associateBy { it.id }

    fun find(id: WebAppId): WebAppDefinition? = catalog[id]

    fun findByIdString(idStr: String): WebAppDefinition? =
        runCatching { WebAppId.valueOf(idStr) }.getOrNull()?.let { find(it) }
}

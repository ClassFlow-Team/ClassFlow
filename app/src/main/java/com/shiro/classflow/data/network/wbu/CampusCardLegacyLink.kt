package com.shiro.classflow.data.network.wbu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 旧一卡通链接要改道到哪里。
 */
sealed interface CampusCardRedirect {
    /** 付款码：直接进 App 原生付款码页（[com.xingheyuzhuan.shiguangschedule.Destination.CampusCardPayCode]）。 */
    data object NativePayCode : CampusCardRedirect

    /**
     * 进新一卡通平台（慧新E校）的某个应用页。
     *
     * @param appCode 平台应用编码（运行时用它解析应用深链，见 [WbuCampusCardClient.resolveDeepLink]）；
     * @param deepLinkFallback 解析不出深链时用的固定深链（`CampusCardLegacyLink` 里写死的经验值）。
     *        两者都为 null = 平台首页（`/plat/?synjones-auth=…`）。
     */
    data class PlatformApp(
        val appCode: String?,
        val deepLinkFallback: String?
    ) : CampusCardRedirect
}

/**
 * 旧「一卡通微信服务」(yktyd.wbu.edu.cn 的 wechat 路径下) → 新一卡通平台（慧新E校 yktfwpt.wbu.edu.cn）的改道规则。
 *
 * 背景：门户里的 7 个一卡通入口全部指向旧微信服务，点开必然 302 到企业微信 OAuth
 * （`appid=wx5a34af927450044e&agentid=1000011&state=wechat_portal`，回调回 `yktyd` 自身，客户端无法伪造），
 * 在 App 里走不通（只能弹「需要微信授权」统一页）；而新平台（`WebAppId.CAMPUS_CARD`）
 * 已经支持 CAS 免密换票，于是在 WebView 导航阶段就把这些链接改道过去。
 *
 * 各规则「会跳到哪」（括号内为备选目标，默认不使用）：
 *  - `/wechat/pay/code.html`                   付款码  → 原生付款码页 `Destination.CampusCardPayCode`
 *                                                        （备选：平台网页 `/plat/?name=pay`，`appCode=pay-code`，`bh=12`）
 *  - `/wechat/ChZhController/ChongZhiurl.html` 充值    → `/campus-card/?name=cardRecharge&feeitemid=410&hasinstallConfigForm=1`
 *                                                        （`appCode=card-recharge`，`bh=27`；`410`=卡片充值）
 *  - 同一链接（门户「余额查询」也用它）          余额    → 同样落账户充值页（顶部即余额）
 *                                                        （备选：`/campus-card/?name=balanceInquiry`，`appCode=balance`，`bh=1`）
 *  - `/wechat/service/modify.html`             密码修改 → `/campus-card/?name=cardSetPwd`
 *                                                        （`appCode=change-password`，`bh=6`）
 *  - `/wechat/waterpay/water.html`             电费    → `/charge/feeitem/toAppitem?feeitemid=412&hasinstallConfigForm=1`
 *                                                        （`appCode=electricity`，`bh=60`；`412`=全校宿舍电费）※
 *  - `/wechat/elecpay/queryelec.html`          水费    → `/charge/feeitem/toAppitem?feeitemid=413&hasinstallConfigForm=1`
 *                                                        （`appCode=water`，`bh=61`；`413`=北区1-8栋/南区ABD栋/马影河1栋）※
 *  - `/wechat/networkpay/network.html`         网费    → `/charge/feeitem/toAppitem?feeitemid=468&hasinstallConfigForm=1`
 *                                                        （`appCode=internet`，`bh=54`；`468`=校园网网费）
 *  - 其它 `yktyd.wbu.edu.cn` 旧链接                 未知    → 平台首页 `/plat/?synjones-auth=…`
 *                                                        （兜底：别把人丢在微信报错页上）
 *
 * ※ 电费/水费两条：旧路径与门户标签疑似互换（`waterpay` 在门户里标为「电费」、`elecpay` 标为「水费」），
 *   上表按**门户标签**对齐新平台；真机确认相反时交换这两行即可。
 *
 * ※ 充值/余额两条：门户「一卡通余额查询」与「一卡通充值」用的是同一个地址，客户端无法从 URL 分辨，
 *   因此两条都落账户充值页（页面上方就是余额）；若要改落余额页，把 [parse] 里那一行换成
 *   `balance` + `/campus-card/?name=balanceInquiry` 即可。
 *
 * 深链拼接：`http://yktfwpt.wbu.edu.cn` + 应用 `website` +（`&`|`?`）`synjones-auth=<access_token>`；
 * 运行时按 `appCode` 调 `/berserker-app/app/getAllApps?platType=h5&userType=user`（带令牌）解析 `website`，
 * 解析失败用上面的固定深链兜底，再失败落平台首页。
 */
object CampusCardLegacyLink {

    /** 旧一卡通微信服务宿主。 */
    private const val LEGACY_HOST = "yktyd.wbu.edu.cn"

    /** 旧一卡通微信服务走 WebVPN 时的代理宿主前缀/后缀（`yktyd-wbu-edu-cn.webvpn.wbu.edu.cn`）。 */
    private const val LEGACY_VPN_HOST_PREFIX = "yktyd-"
    private const val VPN_HOST_SUFFIX = ".webvpn.wbu.edu.cn"

    /** 触发改道的链接必须落在旧一卡通服务上。 */
    fun isLegacyUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == LEGACY_HOST ||
            (host.startsWith(LEGACY_VPN_HOST_PREFIX) && host.endsWith(VPN_HOST_SUFFIX))
    }

    /**
     * 旧一卡通链接 → 新平台落点。
     *
     * @return 不是旧一卡通链接时返回 null（调用方照常放行）。
     */
    fun parse(url: String): CampusCardRedirect? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!isLegacyUrl(url)) return null
        val path = parsed.encodedPath.lowercase()

        return when {
            // 付款码：原生页出码更快、也不依赖 WebView 里的会话
            path.startsWith("/wechat/pay/code.html") -> CampusCardRedirect.NativePayCode

            // 充值（门户「余额查询」用同一个地址，一并落这里，页面顶部就是余额）
            path.startsWith("/wechat/chzhcontroller/chongzhiurl.html") -> CampusCardRedirect.PlatformApp(
                appCode = "card-recharge",
                deepLinkFallback = "/campus-card/?name=cardRecharge&feeitemid=410&hasinstallConfigForm=1"
            )

            path.startsWith("/wechat/service/modify.html") -> CampusCardRedirect.PlatformApp(
                appCode = "change-password",
                deepLinkFallback = "/campus-card/?name=cardSetPwd"
            )

            // 电费（门户标签；旧模块名叫 waterpay，真机确认相反时与下一条对调）
            path.startsWith("/wechat/waterpay/water.html") -> CampusCardRedirect.PlatformApp(
                appCode = "electricity",
                deepLinkFallback = "/charge/feeitem/toAppitem?feeitemid=412&hasinstallConfigForm=1"
            )

            // 水费（门户标签）
            path.startsWith("/wechat/elecpay/queryelec.html") -> CampusCardRedirect.PlatformApp(
                appCode = "water",
                deepLinkFallback = "/charge/feeitem/toAppitem?feeitemid=413&hasinstallConfigForm=1"
            )

            // 网费
            path.startsWith("/wechat/networkpay/network.html") -> CampusCardRedirect.PlatformApp(
                appCode = "internet",
                deepLinkFallback = "/charge/feeitem/toAppitem?feeitemid=468&hasinstallConfigForm=1"
            )

            // 其余旧一卡通链接（如企业微信中转的 /wechat/url/redirectJkbh.html）：落平台首页
            else -> CampusCardRedirect.PlatformApp(appCode = null, deepLinkFallback = null)
        }
    }
}

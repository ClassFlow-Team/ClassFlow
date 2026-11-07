package com.shiro.classflow.ui.webapp

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.WebAppCatalog
import com.shiro.classflow.data.model.wbu.WebAppDefinition
import com.shiro.classflow.data.model.wbu.WebAppId
import com.shiro.classflow.data.model.wbu.WebAppLaunchMode
import com.shiro.classflow.data.network.wbu.AccessFailure
import com.shiro.classflow.data.network.wbu.WbuAuthTransport
import com.shiro.classflow.data.network.wbu.WbuCampusCardClient
import com.shiro.classflow.data.network.wbu.WbuNetworkProbe
import com.shiro.classflow.data.network.wbu.WbuSessionExpiredException
import com.shiro.classflow.data.network.wbu.WbuSyncEngine
import com.shiro.classflow.data.network.wbu.WbuWebAppClient
import com.shiro.classflow.data.network.wbu.needsRelogin
import com.shiro.classflow.data.network.wbu.TwfidState
import com.shiro.classflow.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.shiro.classflow.data.network.wbu.resolveCampusUseVpn
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import com.xingheyuzhuan.shiguangschedule.ui.components.userFacingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 网页应用加载阶段。
 */
sealed class WebAppStage {
    /** 正在检测当前网络通道与校园网环境。 */
    object ProbingNetwork : WebAppStage()

    /** 处于非校园网环境，等待用户选择：继续直连访问、或临时启用 WebVPN。 */
    object OffCampusChoice : WebAppStage()

    /** 正在向统一认证 (CAS) 与 SSO 服务申请加载 Token。 */
    object LoadingToken : WebAppStage()

    /** Token 已成功就绪，WebView 正在全屏渲染目标页面。 */
    data class ContentReady(
        val url: String,
        /** 平台令牌（一卡通 / 图书馆座位预约用）；门户类应用只换票据不换令牌，因此为 null。 */
        val token: String?,
        val useVpn: Boolean
    ) : WebAppStage()

    /** 遇到错误。 */
    data class Error(val message: String) : WebAppStage()
}

/**
 * 「需要微信授权」统一页的入参。
 *
 * 门户里有些目标只在微信客户端里走得通（微信自己的授权页在 App 里就是一个报错页），
 * 客户端代替不了这一步 —— 于是盖一层统一页，把「复制链接 + 返回」交给用户。
 */
data class WechatGate(
    /** 被拦下来的地址（微信自己的授权页 / 企业微信中转）。 */
    val blockedUrl: String,
    /** 「复制链接」复制的内容（优先级见 [WebAppWechat.pickCopyUrl]）。 */
    val copyUrl: String
)

data class WebAppUiState(
    val stage: WebAppStage = WebAppStage.ProbingNetwork,
    val definition: WebAppDefinition? = null,
    val probeStatusText: String = "",
    val needLogin: Boolean = false,
    val requireVpnForLogin: Boolean = false,
    val temporaryUseVpn: Boolean = false,
    val isRetrying: Boolean = false,
    /** 非空时容器上盖一层「需要微信授权」统一页；返回键优先关它（不退出容器）。 */
    val wechatGate: WechatGate? = null,
    /** CAS 票据桥刚换到的落地地址（一次性：WebView 加载后立即清空）。 */
    val casBridgeUrl: String? = null
)

class WebAppViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "WebAppViewModel"
    }

    private val _uiState = MutableStateFlow(WebAppUiState())
    val uiState: StateFlow<WebAppUiState> = _uiState.asStateFlow()

    private var currentAppId: String? = null
    private var overrideTargetUrl: String? = null

    /** 一卡通平台应用编码（旧一卡通链接改道时带进来），见 [WbuCampusCardClient.resolveDeepLink]。 */
    private var campusCardAppCode: String? = null

    /** 平台应用深链的固定兜底（应用表拿不到时用），见 [CampusCardLegacyLink.PlatformApp.deepLinkFallback]。 */
    private var campusCardDeepLinkFallback: String? = null

    /**
     * 票据桥最近一次换票的目标与**连续**次数。
     *
     * 同一目标连着换票还被踢回登录页，说明业务端根本不认我们换来的票（配置对不上 / 服务端拒绝），
     * 再换下去就是「加载 → 跳 CAS → 再换 → 再加载」的死循环；到第 3 次就放弃桥接、
     * 交回既有自救（静默登录 → 登录面板）。
     */
    private var lastBridgeService: String? = null
    private var lastBridgeRepeats = 0

    /** 本次流程上一次静默登录的失败原因（null = 成功或还没试过）。 */
    private var lastSilentLoginFailure: AccessFailure? = null

    /**
     * 本次流程是否已经尝试过「用保存的密码静默登录」。
     *
     * 静默登录失败后如果又走到需要登录的分支，就直接弹 Sheet —— 否则会反复重试成环。
     * 每次 [start] / [retry] 重置。
     */
    private var silentLoginAttempted = false

    /**
     * 本次流程是否已经为「网页被踢回 CAS 登录页」自救过一次。
     *
     * 自救会重新换票并重载页面；页面若又被踢回 CAS，说明凭据链本身解决不了，直接弹登录面板 ——
     * 否则「重载 → 跳 CAS → 重载」会成环。每次 [start] / [retry] 重置。
     */
    private var webRecoveryAttempted = false

    /**
     * 启动加载流程：
     * 1. 寻找配置定义；
     * 2. 检查 WebVPN 与校园网；
     * 3. 决定网络策略；
     * 4. 换取 Token（或票据）并打开。
     *
     * @param campusCardAppCode 一卡通平台应用编码（旧一卡通链接改道时带进来）：进平台后直接落到该应用页；
     * @param campusCardDeepLinkFallback 应用表解析不出来时的固定深链兜底。
     */
    fun start(
        appId: String,
        initialTargetUrl: String? = null,
        campusCardAppCode: String? = null,
        campusCardDeepLinkFallback: String? = null
    ) {
        currentAppId = appId
        overrideTargetUrl = initialTargetUrl
        this.campusCardAppCode = campusCardAppCode
        this.campusCardDeepLinkFallback = campusCardDeepLinkFallback
        silentLoginAttempted = false
        lastSilentLoginFailure = null
        webRecoveryAttempted = false
        lastBridgeService = null
        lastBridgeRepeats = 0
        val def = WebAppCatalog.findByIdString(appId)
        if (def == null) {
            _uiState.update { it.copy(stage = WebAppStage.Error(getApplication<Application>().getString(R.string.err_unknown_web_app, appId))) }
            return
        }

        _uiState.update {
            it.copy(
                definition = def,
                // 直连应用（如一卡通）不做校园网检测，直接进入凭据加载阶段
                stage = if (def.directOnly) WebAppStage.LoadingToken else WebAppStage.ProbingNetwork,
                probeStatusText = getApplication<Application>().getString(R.string.status_probing_campus_network),
                needLogin = false
            )
        }

        evaluateNetworkAndProceed(def)
    }

    private fun evaluateNetworkAndProceed(def: WebAppDefinition) {
        viewModelScope.launch {
            val app = getApplication<Application>()

            // 0. 若定义为 directOnly（如一卡通移动服务平台），无需检测校园网或 WebVPN，直接直连加载
            if (def.directOnly) {
                proceedWithChannel(def, useVpn = false)
                return@launch
            }

            val savedUseVpn = WbuSyncEngine.getSavedUseVpn(app)

            if (!savedUseVpn) {
                // 1. 未开启 WebVPN：先探针校园网
                _uiState.update {
                    it.copy(
                        stage = WebAppStage.ProbingNetwork,
                        probeStatusText = app.getString(R.string.status_probing_campus_network)
                    )
                }
                val onCampus = WbuNetworkProbe.probeForCampusFlow(app)
                if (onCampus) {
                    // 在校内，直接以校园网直连加载
                    proceedWithChannel(def, useVpn = false)
                } else {
                    // 非校内，停在选择界面询问：继续直连还是临时使用 WebVPN
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.OffCampusChoice,
                            temporaryUseVpn = false
                        )
                    }
                }
            } else {
                // 2. 开启了 WebVPN：但「自动校园网探测」开着时先快速探一次 ——
                //    人就在校园网里就别绕 WebVPN 了（校园网里走代理更慢，也更容易掉线）。
                //    探测关闭 / 「不检测校园网环境」开着时这一步不会探测，结果就是继续用 WebVPN。
                if (!resolveCampusUseVpn(app, savedUseVpn = true)) {
                    proceedWithChannel(def, useVpn = false)
                    return@launch
                }
                // 2.1 不在校园网：核验当前 TWFID 是否仍有效（只读探活，不消耗登录尝试）
                val twfid = WbuAuthTransport.getTwfid(app)
                val transport = WbuAuthTransport.getShared(app, true)
                transport.restoreCookieStore()
                val vpnClient = WebVpnClient(transport)
                val twfidState = if (twfid.isNotBlank()) {
                    val state = runCatching { vpnClient.probeTwfid(twfid) }.getOrDefault(TwfidState.UNKNOWN)
                    if (state == TwfidState.NOT_AUTHENTICATED) {
                        // 服务端明确说这个槽位没认证：清掉本地凭据
                        WbuAuthTransport.clearTwfid(app)
                        vpnClient.removeTwfidCookie()
                    }
                    state
                } else TwfidState.NOT_AUTHENTICATED

                if (twfidState == TwfidState.VALID) {
                    vpnClient.injectTwfid(twfid)
                    // WebVPN 门禁有效，直接通过 WebVPN 代理通道加载
                    proceedWithChannel(def, useVpn = true)
                } else {
                    // WebVPN 失效，检测校园网
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.ProbingNetwork,
                            probeStatusText = app.getString(R.string.status_probing_campus_after_vpn_fail)
                        )
                    }
                    val onCampus = WbuNetworkProbe.refresh()
                    if (onCampus) {
                        // 虽然配置了 VPN 但在校内，可直接以校园网直连进入
                        proceedWithChannel(def, useVpn = false)
                    } else {
                        // 不在校园网且 VPN 失效，切换为离校选择状态并直接弹登录 Sheet 登录 WebVPN
                        _uiState.update {
                            it.copy(
                                stage = WebAppStage.OffCampusChoice,
                                needLogin = true,
                                requireVpnForLogin = true,
                                temporaryUseVpn = true
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 用户在离校探测浮层中选择「直接继续（直连）」。
     */
    fun chooseContinueDirect() {
        val def = _uiState.value.definition ?: return
        _uiState.update { it.copy(temporaryUseVpn = false, requireVpnForLogin = false) }
        proceedWithChannel(def, useVpn = false)
    }

    /**
     * 用户在离校探测浮层中选择「临时使用 WebVPN」。
     */
    fun chooseUseVpnTemporarily() {
        val def = _uiState.value.definition ?: return
        _uiState.update { it.copy(temporaryUseVpn = true, requireVpnForLogin = true) }
        proceedWithChannel(def, useVpn = true)
    }

    /**
     * 进入指定通道（直连或 WebVPN）提取 Token 并生成启动 URL。
     */
    private fun proceedWithChannel(def: WebAppDefinition, useVpn: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val transport = WbuAuthTransport.getShared(app, useVpn)
            transport.restoreCookieStore()

            // 已确定通道，进入凭据加载阶段（避免继续显示校园网探测浮层）
            _uiState.update { it.copy(stage = WebAppStage.LoadingToken) }

            // 1. 若使用 WebVPN，必须首先验证 TWFID 是否有效；失效时先用保存的密码静默恢复门禁，失败才弹窗
            if (useVpn) {
                val twfid = WbuAuthTransport.getTwfid(app)
                if (twfid.isBlank()) {
                    // 内容本身必须走 WebVPN：静默重登也得经 WebVPN，才能把门禁 TWFID 建起来
                    val failure = trySilentUnifiedAuthLogin(forceWebVpn = true)
                    if (failure == null) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    settleSilentLoginFailure(failure, requireVpnForLogin = true, keepChoiceOverlay = true)
                    return@launch
                }

                val vpnClient = WebVpnClient(transport)
                val twfidState = runCatching { vpnClient.probeTwfid(twfid) }.getOrDefault(TwfidState.UNKNOWN)
                if (twfidState != TwfidState.VALID) {
                    if (twfidState == TwfidState.NOT_AUTHENTICATED) {
                        // 只有服务端明确说未认证才清本地槽位；探活失败（网络抖动）时保留
                        WbuAuthTransport.clearTwfid(app)
                        vpnClient.removeTwfidCookie()
                    }
                    val failure = trySilentUnifiedAuthLogin(forceWebVpn = true)
                    if (failure == null) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    settleSilentLoginFailure(failure, requireVpnForLogin = true, keepChoiceOverlay = true)
                    return@launch
                }
                vpnClient.injectTwfid(twfid)
            }

            // 2. 检查本地是否有统一身份认证凭证 CASTGC。
            //    没有也不立刻弹登录 Sheet：优先用保存的账号密码静默登录一次（与 /w/ U净出水、扫一扫入口一致），
            //    成功就直接继续换票；只有没保存密码 / 静默登录失败才弹 Sheet。
            //
            //    一卡通平台不设这个前提：它有自己的平台令牌（access_token 有效就直接用，过期用
            //    refresh_token 续期，两者都不顶号），手里那份还能用就不该先把用户拦到统一认证前
            //    —— 凭据页也正是按令牌判「一卡通有效」，若这里另要 CASTGC，就会出现「一卡通有效却弹统一认证」。
            //    真换不出令牌时 [WbuCampusCardClient.ensureValidAccessToken] 会抛 [WbuSessionExpiredException]，
            //    由下面的 catch 走同一套静默登录 / 登录面板，结果与旧行为一致，只是不再白白多要一次统一认证。
            val cardTokenFirst = def.id == WebAppId.CAMPUS_CARD
            val hasUnifiedSession = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
            if (!cardTokenFirst && !hasUnifiedSession) {
                val failure = trySilentUnifiedAuthLogin()
                if (failure != null) {
                    settleSilentLoginFailure(failure, requireVpnForLogin = useVpn)
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    stage = WebAppStage.LoadingToken,
                    temporaryUseVpn = useVpn,
                    needLogin = false
                )
            }

            try {
                // 特判：一卡通移动服务平台 (CAMPUS_CARD)
                if (def.id == WebAppId.CAMPUS_CARD) {
                    val cardClient = WbuCampusCardClient(app, useVpn = false)
                    val token = cardClient.ensureValidAccessToken()
                    val launchUrl = resolveCampusCardLaunchUrl(cardClient, token)
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.ContentReady(
                                url = launchUrl,
                                token = token,
                                useVpn = false
                            ),
                            needLogin = false
                        )
                    }
                    return@launch
                }

                // 门户类（移动门户）：只换票据、不在客户端核销 —— 票据交给 WebView，
                // 由门户自己核销并下发 SESSION 会话（见 WebAppLaunchMode.CAS_SERVICE_TICKET）
                if (def.launchMode == WebAppLaunchMode.CAS_SERVICE_TICKET) {
                    val client = WbuWebAppClient(app, useVpn = useVpn)
                    val ticketUrl = client.fetchCasServiceTicketUrl(def)
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.ContentReady(
                                url = ticketUrl,
                                token = null,
                                useVpn = useVpn
                            ),
                            needLogin = false
                        )
                    }
                    return@launch
                }

                val client = WbuWebAppClient(app, useVpn = useVpn)
                val token = client.fetchCasCallbackToken(def)
                val launchUrl = client.buildLaunchUrl(def, token)

                _uiState.update {
                    it.copy(
                        stage = WebAppStage.ContentReady(
                            url = launchUrl,
                            token = token,
                            useVpn = useVpn
                        ),
                        needLogin = false
                    )
                }
            } catch (e: WbuSessionExpiredException) {
                // CASTGC / WebVPN 门禁过期：先用保存的密码静默重登一次，失败才弹登录 Sheet。
                // forceWebVpn = useVpn：内容在隧道里时，重登也必须走隧道 —— 走公网会撞 WAF
                // 挡板页，被归成「内容不符预期」，用户看到一句完全无关的话，且永远修不好。
                val failure = trySilentUnifiedAuthLogin(forceWebVpn = useVpn)
                if (failure == null) {
                    proceedWithChannel(def, useVpn)
                    return@launch
                }
                settleSilentLoginFailure(failure, requireVpnForLogin = useVpn)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (e is WbuSessionExpiredException ||
                    msg.contains("失效") || msg.contains("过期") || msg.contains("登录") || msg.contains("WebVPN") || msg.contains("门禁") ||
                    msg.contains("expired", ignoreCase = true) || msg.contains("log in", ignoreCase = true) || msg.contains("login", ignoreCase = true)
                ) {
                    val failure = trySilentUnifiedAuthLogin(forceWebVpn = useVpn)
                    if (failure == null) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    settleSilentLoginFailure(failure, requireVpnForLogin = useVpn)
                } else {
                    val app = getApplication<Application>()
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.Error(userFacingMessage(app, e) ?: app.getString(R.string.err_load_page_credential_failed))
                        )
                    }
                }
            }
        }
    }

    /**
     * 一卡通进容器时要落的地址，优先级：
     * 外部深链（[overrideTargetUrl]，如 U净 / 洗浴直达）→ 平台应用深链（旧链接改道带进来的）
     * → 固定深链兜底 → 平台首页。
     *
     * 平台应用深链按 `appCode` 运行时解析（学校改配置也不失效），拿不到应用表才用写死的兜底。
     */
    private suspend fun resolveCampusCardLaunchUrl(client: WbuCampusCardClient, token: String): String {
        overrideTargetUrl?.let { return it }
        campusCardAppCode?.takeIf { it.isNotBlank() }?.let { code ->
            client.resolveDeepLink(token, code)?.let { return it }
        }
        campusCardDeepLinkFallback?.takeIf { it.isNotBlank() }?.let { fallback ->
            return client.buildDeepLinkUrl(token, fallback)
        }
        return client.buildLaunchUrl(token)
    }

    /**
     * 用保存的账号密码静默登录统一认证。
     *
     * 顺序固定：**先复用手头还有效的会话** —— 一卡通是它自己的平台令牌
     * （`ensureValidAccessToken`：access_token → refresh_token → 才轮到 CASTGC 换票），
     * 其余应用是 CASTGC（上面的 `hasUnifiedSession` 检查与换票逻辑直接命中）；
     * 只有这些凭证都缺失 / 失效时才走到这里；这里再失败（没保存密码、用户取消、需要滑块）
     * 才把 `needLogin` 交给 UI 弹登录 Sheet —— 与 `/w/` U净出水、扫一扫等入口保持同一套行为。
     *
     * 是否经 WebVPN：默认只由「统一认证经过 WebVPN」决定（[forceWebVpn] = false，即没开就完全不牵扯
     * WebVPN）；只有在内容本身必须走 WebVPN（TWFID 门禁失效要重建）时才强制为 true。
     * 需要门禁密码时优先用本地保存的，没保存就弹小窗让用户补 WebVPN 密码 + 短信验证码，补不上才回落 Sheet。
     *
     * 每次进入（[start] / [retry]）只尝试一次（[silentLoginAttempted]）：重试成环只会白烧服务端失败次数。
     * 失败原因按 [AccessFailure] 结构化返回，调用方据此决定是弹 Sheet 还是只给一条可重试的错误。
     */
    private suspend fun trySilentUnifiedAuthLogin(forceWebVpn: Boolean = false): AccessFailure? {
        // 本次流程已经试过：原样回报上次的原因，不再发起第二次登录
        if (silentLoginAttempted) return lastSilentLoginFailure
        silentLoginAttempted = true

        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                stage = WebAppStage.LoadingToken,
                probeStatusText = app.getString(R.string.status_login_saved_credentials)
            )
        }
        // 弹窗（未保存的统一认证密码 / WebVPN 门禁密码 / 短信验证码 / 图形校验）统一走进程级小窗。
        // onlyWithSavedPassword = true：本机没存密码就直接回落到登录 Sheet，保持这条路径的老行为
        val failure = silentUnifiedAuthLogin(
            context = app,
            flowTag = "WEBAPP_AUTO_AUTH",
            viaWebVpn = forceWebVpn || WbuAuthTransport.getIdsViaWebVpn(app),
            onlyWithSavedPassword = true
        )
        lastSilentLoginFailure = failure
        return failure
    }

    /**
     * 静默登录失败后的收尾。
     *
     * **只有**「会话失效 / 凭据被拒」才把用户请去登录 Sheet；用户取消小窗、网络不通、服务端异常
     * 只留一条可重试的错误 —— 以前这里任何失败都会弹 Sheet，用户在小窗上点个取消也会被顶一脸登录框。
     *
     * @param keepChoiceOverlay 从「内容必须走 WebVPN」的分支出来的失败：保留离校通道选择浮层，
     *                          别把用户直接推进登录页。
     */
    private fun settleSilentLoginFailure(
        failure: AccessFailure,
        requireVpnForLogin: Boolean,
        keepChoiceOverlay: Boolean = false
    ) {
        if (failure.needsRelogin) {
            val vpn = requireVpnForLogin || keepChoiceOverlay
            _uiState.update {
                it.copy(
                    stage = if (keepChoiceOverlay) WebAppStage.OffCampusChoice else it.stage,
                    needLogin = true,
                    requireVpnForLogin = vpn,
                    temporaryUseVpn = vpn
                )
            }
            return
        }
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = WebAppStage.Error(
                    accessFailureText(app, failure)
                        ?: app.getString(R.string.err_need_unified_auth_session)
                )
            )
        }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        val def = _uiState.value.definition
        if (def != null) {
            proceedWithChannel(def, useVpn = _uiState.value.temporaryUseVpn || _uiState.value.requireVpnForLogin)
        }
    }

    /**
     * 网页容器里的页面被踢回统一认证登录页（`/authserver/login` / `/por/login`）时的自愈入口。
     *
     * 跳到 CAS 只说明「这一页手里的会话失效了」，并不等于「必须让用户登录统一认证」——
     * 本机往往还留着能用的凭据（一卡通平台令牌 / `refresh_token`，或保存的账号密码），
     * 所以先按各应用自己的凭据链把页面救回来：
     * - 一卡通：换一份新的平台令牌，仍停在用户原来那个平台内页（见 [recoverCampusCard]）；
     * - 其余网页应用：重新换一次 CAS 票再进，与进容器同一条路。
     *
     * 救不回来才把 `needLogin` 交给 UI 弹登录面板；每次进入容器只自救一次（[webRecoveryAttempted]）。
     */
    fun onWebAuthRedirect(pageUrl: String?) {
        val def = _uiState.value.definition ?: return
        if (webRecoveryAttempted) {
            // 刚救过又被踢回 CAS：凭据链解决不了，交给登录面板
            _uiState.update { it.copy(needLogin = true) }
            return
        }
        webRecoveryAttempted = true
        // 情况已经变了（页面确实被踢回 CAS 了），这次自救允许再静默登录一次
        silentLoginAttempted = false
        lastSilentLoginFailure = null
        Log.i(TAG, "网页被踢回统一认证登录页，先按本机凭据自救一次：page=$pageUrl")

        if (def.id == WebAppId.CAMPUS_CARD) {
            viewModelScope.launch { recoverCampusCard(pageUrl) }
            return
        }
        proceedWithChannel(def, useVpn = _uiState.value.temporaryUseVpn || _uiState.value.requireVpnForLogin)
    }

    /**
     * 拦到了「需要微信授权」的目标：盖统一页（提示 + 复制链接 + 返回）。
     *
     * @param blockedUrl 被拦下来的地址（微信授权页 / 企业微信中转）；
     * @param pageUrl 拦下来时页面上正停留的那一页 —— 复制内容优先用它（见 [WebAppWechat.pickCopyUrl]）。
     */
    fun onWechatBlocked(blockedUrl: String, pageUrl: String?) {
        val copyUrl = WebAppWechat.pickCopyUrl(pageUrl, blockedUrl)
        Log.i(TAG, "拦到需要微信授权的目标，改弹统一页：blocked=$blockedUrl copy=$copyUrl")
        _uiState.update { it.copy(wechatGate = WechatGate(blockedUrl = blockedUrl, copyUrl = copyUrl)) }
    }

    /** 关掉「需要微信授权」统一页（返回按钮 / 返回键；WebView 的历史回退由容器做）。 */
    fun dismissWechatGate() {
        _uiState.update { it.copy(wechatGate = null) }
    }

    /**
     * CAS 票据桥：用本机 `CASTGC` 给 [serviceUrl] 换一张票据，把 CAS 回跳地址交给 WebView 加载。
     *
     * 门户里的应用（ehall / 新生入馆教育 …）自己跳统一认证登录页是**正常流程** ——
     * 它们要的是「一张能证明身份的票据」，不是「让用户再登录一次」，
     * 所以这里就地换票，用户完全无感（见 [WebAppDefinition.casTicketBridge]）。
     *
     * @param blockedUrl 非空表示这次拦截来自「企业微信中转」（`weixinQYLogin.do?success=…`）：
     *        换票失败时不弹登录面板，而是回落到微信统一页 —— 那条链接复制到微信里仍然走得通。
     */
    fun requestCasTicket(serviceUrl: String, blockedUrl: String? = null) {
        val def = _uiState.value.definition ?: return
        if (serviceUrl.isBlank()) {
            onWebAuthRedirect(null)
            return
        }

        // 同一目标连续换票 > 2 次：业务端不认这份票，别再成环（见 [lastBridgeRepeats]）
        if (serviceUrl == lastBridgeService) {
            lastBridgeRepeats++
        } else {
            lastBridgeService = serviceUrl
            lastBridgeRepeats = 1
        }
        if (lastBridgeRepeats > 2) {
            Log.w(TAG, "票据桥同一目标已连续换票 $lastBridgeRepeats 次，放弃桥接：$serviceUrl")
            if (blockedUrl != null) onWechatBlocked(blockedUrl, null) else onWebAuthRedirect(null)
            return
        }

        viewModelScope.launch {
            val useVpn = _uiState.value.temporaryUseVpn || _uiState.value.requireVpnForLogin
            val landing = runCatching {
                // 跨站目标（ehall / zxst …）不做 WebVPN 宿主改写：它们不在本应用的代理范围内，
                // 按现状原样加载（见实施计划 §7 风险表）
                WbuWebAppClient(getApplication(), useVpn = useVpn)
                    .resolveCasServiceRedirect(serviceUrl, proxyHostOf = null)
            }.onFailure {
                Log.w(TAG, "CAS 票据桥换票失败：service=$serviceUrl", it)
            }.getOrNull()

            if (!landing.isNullOrBlank()) {
                Log.i(TAG, "CAS 票据桥换到落地地址，交给 WebView：$landing")
                _uiState.update { it.copy(casBridgeUrl = landing, needLogin = false) }
                return@launch
            }
            if (blockedUrl != null) {
                onWechatBlocked(blockedUrl, null)
            } else {
                onWebAuthRedirect(null)
            }
        }
    }

    /** 票据桥的落地地址已经被 WebView 加载，清掉这份一次性的状态。 */
    fun onCasBridgeUrlConsumed() {
        _uiState.update { it.copy(casBridgeUrl = null) }
    }

    /**
     * 一卡通自救：换一份新的平台令牌，重新加载平台页。
     *
     * 顺序与进容器时一致：`access_token` → `refresh_token`（两者都不顶号、都不需要用户在场）
     * → 静默登录补统一认证会话后再换票；全都不行才是真要用户登录。
     * 当前停在平台的哪个内页就回哪一页，不在平台内页（第三方子应用 / 深链票据页）就回平台入口。
     */
    private suspend fun recoverCampusCard(pageUrl: String?) {
        val app = getApplication<Application>()
        val cardClient = WbuCampusCardClient(app, useVpn = false)
        _uiState.update { it.copy(stage = WebAppStage.LoadingToken, needLogin = false) }

        val token = when (val first = fetchCardToken(cardClient)) {
            is CardTokenResult.Ready -> first.token
            // 其它失败（网络等）已经落到错误页，不要再往下走
            CardTokenResult.Failed -> return
            CardTokenResult.NeedsUnifiedAuth -> {
                val failure = trySilentUnifiedAuthLogin()
                if (failure != null) {
                    settleSilentLoginFailure(failure, requireVpnForLogin = false)
                    return
                }
                when (val second = fetchCardToken(cardClient)) {
                    is CardTokenResult.Ready -> second.token
                    else -> {
                        // 统一认证会话到手了却仍换不出令牌：只能请用户手动重登一次
                        _uiState.update { it.copy(needLogin = true) }
                        return
                    }
                }
            }
        }

        val launchUrl = cardClient.buildLaunchUrl(token, campusCardPathOf(pageUrl))
        Log.i(TAG, "一卡通自救成功，重新加载：$launchUrl")
        _uiState.update {
            it.copy(
                stage = WebAppStage.ContentReady(url = launchUrl, token = token, useVpn = false),
                needLogin = false
            )
        }
    }

    /** 取一份可用的一卡通平台令牌；失败原因按「需要登录」与「其它」分开。 */
    private suspend fun fetchCardToken(cardClient: WbuCampusCardClient): CardTokenResult = try {
        CardTokenResult.Ready(cardClient.ensureValidAccessToken())
    } catch (e: CancellationException) {
        throw e
    } catch (e: WbuSessionExpiredException) {
        Log.i(TAG, "一卡通令牌不可用，需要统一认证会话：${e.message}")
        CardTokenResult.NeedsUnifiedAuth
    } catch (e: Exception) {
        Log.w(TAG, "换一卡通令牌失败", e)
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                stage = WebAppStage.Error(
                    userFacingMessage(app, e) ?: app.getString(R.string.err_load_page_credential_failed)
                )
            )
        }
        CardTokenResult.Failed
    }

    /** 一卡通平台内页路径（`/plat/xxx` → `xxx`）；不在平台内页就给 null（回平台入口）。 */
    private fun campusCardPathOf(pageUrl: String?): String? {
        val parsed = pageUrl?.toHttpUrlOrNull() ?: return null
        if (!parsed.host.equals("yktfwpt.wbu.edu.cn", ignoreCase = true)) return null
        val path = parsed.encodedPath.trim('/')
        if (!path.startsWith("plat")) return null
        return path.removePrefix("plat").trim('/').takeIf { it.isNotBlank() }
    }

    /** 一卡通换令牌的结果。 */
    private sealed interface CardTokenResult {
        data class Ready(val token: String) : CardTokenResult

        /** 缺统一认证会话 / 服务端不认：需要用户登录统一认证。 */
        data object NeedsUnifiedAuth : CardTokenResult

        /** 其它失败（网络等）：已经落到错误页，调用方不要再往下走。 */
        data object Failed : CardTokenResult
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
        if (_uiState.value.stage !is WebAppStage.ContentReady) {
            // 直连应用（如一卡通）无校园网选择语义，取消登录即视为无法继续
            _uiState.update {
                if (_uiState.value.definition?.directOnly == true) {
                    it.copy(stage = WebAppStage.Error(getApplication<Application>().getString(R.string.err_unified_auth_required)))
                } else {
                    it.copy(stage = WebAppStage.OffCampusChoice)
                }
            }
        }
    }

    fun retry() {
        silentLoginAttempted = false
        lastSilentLoginFailure = null
        webRecoveryAttempted = false
        lastBridgeService = null
        lastBridgeRepeats = 0
        val def = _uiState.value.definition
        if (def != null) {
            evaluateNetworkAndProceed(def)
        } else {
            currentAppId?.let { start(it) }
        }
    }

    // ── WebView 会话：容器被别的页面盖住时，把这一页原样留住 ──

    /** 当前导航条目手里的 WebView，见 [reusableSessionWebView]。 */
    private var sessionWebView: WebView? = null

    /** 本轮流程要落的地址已经换了（探测 / 换票 / 重新登录 / 自救 / 重试）：上一轮的会话作废。 */
    private var sessionInvalidated = false

    init {
        // 容器只要离开过「内容就绪」，就说明这一轮要落的地址是新的（重探网络、重新换票、重新登录、
        // 一卡通自救、重试……），旧 WebView 上的页面作废。
        //
        // 这件事放在 VM 里盯着自己的状态流做，而不是让 Compose 侧去观察 `stage`：阶段是连跳的
        // （LoadingToken → ContentReady 之间只隔着一次网络往返），而 Compose 收集状态时会把
        // 一帧内的中间态合并掉 —— 那样「离开过 ContentReady」就有可能被漏掉，页面于是停在旧地址上。
        viewModelScope.launch {
            uiState.collect { state ->
                if (state.stage !is WebAppStage.ContentReady) invalidateSession()
            }
        }
    }

    /**
     * 本次导航条目是否已经有一份活着、能直接挂回容器的 WebView 会话。
     *
     * true 表示容器只是被别的页面盖住过（门户里点开「一卡通充值」，压进来的是原生页或一卡通平台），
     * 此时**不要**再走一遍 [start]：页面本身、选项卡位置、滚动位置全在 WebView 手里。
     */
    fun hasLiveSession(appId: String): Boolean =
        currentAppId == appId && reusableSessionWebView() != null

    /**
     * 可复用的 WebView 会话（没有则返回 null，由容器新建后交回 [holdSessionWebView]）。
     *
     * 为什么必须留着同一个 WebView：门户这类单页应用把「当前在哪个选项卡、滚到哪儿、页内拿到了哪些
     * 数据」都存在自己的 JS 内存里，换一个 WebView（哪怕地址一样）都会重新加载、回到首页顶部。
     *
     * 生命周期借的是**导航条目**：Nav3 给每个条目一份 ViewModelStore，条目被别的页面压住时留着，
     * 真正从回退栈弹出时才清（见 [onCleared]）—— 正是「同一个条目来回切」这个窗口。
     */
    fun reusableSessionWebView(): WebView? = sessionWebView?.takeIf { !sessionInvalidated }

    /** 容器新建好的 WebView 交回来持有；上一份如果已经作废，顺手销毁它。 */
    fun holdSessionWebView(webView: WebView) {
        val stale = sessionWebView?.takeIf { it !== webView }
        sessionWebView = webView
        sessionInvalidated = false
        stale?.let(::destroyWebView)
    }

    // ── 网页定位桥：http 站点拿不到浏览器定位，由容器自己取位置交给页面 ──

    /** 定位桥（见 [WebAppGeolocationController]），惰性创建。 */
    private var geolocationController: WebAppGeolocationController? = null

    /**
     * 取本条目共用的定位桥。
     *
     * 必须与会话 WebView 同寿：`addJavascriptInterface` 的注册挂在 **WebView 对象**上，
     * 页面里的接管脚本一旦装上，后续定位请求就只会打到当初注册的那个对象。容器被别的页面
     * 盖住再挂回来时如果换了新桥，页面就会打到一只已经放手的桥上（表现为「拿不到位置」）。
     */
    internal fun geolocationController(): WebAppGeolocationController =
        geolocationController ?: WebAppGeolocationController(
            getApplication(),
            Handler(Looper.getMainLooper())
        ).also { geolocationController = it }

    /**
     * 作废当前会话。
     *
     * 只打标记、不当场销毁：这一刻 WebView 可能还挂在正在退场的容器上（销毁正在使用的 WebView
     * 会出问题），真正的销毁交给新会话接手时或条目弹出时。
     */
    fun invalidateSession() {
        if (sessionWebView != null) sessionInvalidated = true
    }

    override fun onCleared() {
        sessionWebView?.let { webView ->
            sessionWebView = null
            destroyWebView(webView)
        }
        super.onCleared()
    }

    /**
     * 销毁 WebView 并清掉它留下的一切（缓存、历史、WebStorage）。
     *
     * 与 WebView 打交道只能在主线程：`onCleared` 由导航弹栈触发（主线程），保险起见兜一层。
     */
    private fun destroyWebView(webView: WebView) {
        val teardown = Runnable {
            // 可能还挂在旧容器上，先摘下来再销毁
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching {
                webView.stopLoading()
                webView.clearCache(true)
                webView.clearHistory()
                WebStorage.getInstance().deleteAllData()
                webView.removeAllViews()
                webView.destroy()
            }.onFailure { Log.w(TAG, "清理 WebView 资源失败", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            teardown.run()
        } else {
            Handler(Looper.getMainLooper()).post(teardown)
        }
    }
}

package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 网页加载失败时给用户看的错误分类。
 *
 * 内置 WebView 在「服务器压根没返回内容」时会自己渲染一张 Chromium 的绿色机器人错误页
 * （`net::ERR_*`），既丑又只有英文。这里把那次回调接过来，换成应用自己的错误页。
 *
 * 注意：只接管**网络层**失败（连不上 / 超时 / DNS / SSL / 空响应…）。
 * 服务器真的返回了 4xx / 5xx 页面时不会触发 `onReceivedError`，那些页面原样展示 —— 它们带着
 * 服务端自己的提示信息，抢过来反而丢信息。
 */
enum class WebErrorKind {
    /** 设备离线 / 网络不可达。 */
    OFFLINE,

    /** 域名解析失败。 */
    DNS,

    /** 连接被拒绝、重置或中断。 */
    CONNECTION,

    /** 连接或读取超时。 */
    TIMEOUT,

    /** 服务器接受了连接却没有返回任何内容（Chromium 的 `ERR_EMPTY_RESPONSE`）。 */
    EMPTY_RESPONSE,

    /** TLS / 证书问题。 */
    SSL,

    /** 链接需要专门的 App 打开（`intent://`、`alipays://` 之类）。 */
    UNSUPPORTED_SCHEME,

    /** 其它未知错误。 */
    UNKNOWN
}

/**
 * 一次主框架加载失败的全部原始信息（用于「保留原有信息」）。
 *
 * @param url 失败的那一页地址
 * @param code `WebResourceError.errorCode`（`WebViewClient.ERROR_*`），可空 = 非 WebView 场景
 * @param description `WebResourceError.description` 原文（通常含 `net::ERR_xxx`）
 */
data class WebPageErrorInfo(
    val url: String?,
    val code: Int?,
    val description: String?
)

/**
 * Chromium 的 `ERR_ABORTED`（-3）。
 *
 * 用户点了别的链接、页面自己取消导航时也会回调 `onReceivedError`，这一条不是真失败，
 * 不能拿来弹错误页。`WebViewClient` 里没有对应常量（它只列到 -16 的粗分类），所以自己定义。
 */
const val WEB_ERROR_ABORTED = -3

/**
 * 这次 `onReceivedError` 是不是「服务器压根没返回内容」的真失败。
 *
 * 排除三类噪声：
 * - [WEB_ERROR_ABORTED]：导航被新页面顶掉 / 用户点了别的链接，属于正常竞争；
 * - `about:` / `data:` / `javascript:`：不是真页面，失败也没意义；
 * - 空地址：拿不到地址时无从展示，交给 WebView 自己处理。
 *
 * 注意：服务器返回了 4xx / 5xx 页面时 WebView 走的是 `onReceivedHttpError`，不经过这里 ——
 * 那些页面带着服务端自己的提示，原样展示。
 */
fun isRealWebLoadFailure(url: String?, code: Int): Boolean {
    if (code == WEB_ERROR_ABORTED) return false
    val target = url?.trim().orEmpty()
    if (target.isEmpty()) return false
    val lower = target.lowercase()
    return !lower.startsWith("about:") &&
        !lower.startsWith("data:") &&
        !lower.startsWith("javascript:")
}

/** 从 `WebResourceError.description` 里抓 Chromium 的错误名（`net::ERR_CONNECTION_TIMED_OUT`）。 */
private val NET_ERROR_NAME = Regex("ERR_[A-Z0-9_]+")

/**
 * 取出描述里最原始的 Chromium 错误名（不含 `net::` 前缀）；没有则返回 null。
 *
 * 实测 WebView 的 `description` 形如 `net::ERR_CONNECTION_TIMED_OUT`，
 * 这个字符串比 `errorCode` 精确得多（后者是阉割过的老枚举），所以优先用它。
 */
fun extractNetErrorName(description: String?): String? =
    description?.let { NET_ERROR_NAME.find(it)?.value }

/**
 * 错误分类：优先按 Chromium 错误名判断，拿不到名字时才退回 [WebViewClient 错误码][android.webkit.WebViewClient]。
 *
 * 放在纯函数里是为了能直接单测（`WebErrorKindTest`），与 `WebAppUrlRepairTest` 一个路子。
 */
fun webErrorKind(code: Int?, description: String?): WebErrorKind {
    val name = extractNetErrorName(description)
    if (name != null) {
        return when {
            name == "ERR_INTERNET_DISCONNECTED" || name == "ERR_NETWORK_ACCESS_DENIED" -> WebErrorKind.OFFLINE
            name == "ERR_NAME_NOT_RESOLVED" || name == "ERR_NAME_RESOLUTION_FAILED" -> WebErrorKind.DNS
            name == "ERR_ADDRESS_UNREACHABLE" || name == "ERR_ADDRESS_INVALID" -> WebErrorKind.OFFLINE
            name == "ERR_EMPTY_RESPONSE" -> WebErrorKind.EMPTY_RESPONSE
            name == "ERR_CONTENT_LENGTH_MISMATCH" -> WebErrorKind.EMPTY_RESPONSE
            name.startsWith("ERR_SSL_") || name.startsWith("ERR_CERT_") ||
                name.startsWith("ERR_BAD_SSL_") -> WebErrorKind.SSL
            name == "ERR_TIMED_OUT" || name == "ERR_CONNECTION_TIMED_OUT" -> WebErrorKind.TIMEOUT
            name == "ERR_UNKNOWN_URL_SCHEME" || name == "ERR_UNKNOWN_SCHEME" ||
                name == "ERR_DISALLOWED_URL_SCHEME" -> WebErrorKind.UNSUPPORTED_SCHEME
            name.startsWith("ERR_CONNECTION_") || name == "ERR_SOCKET_NOT_CONNECTED" ||
                name == "ERR_NETWORK_CHANGED" -> WebErrorKind.CONNECTION
            else -> WebErrorKind.UNKNOWN
        }
    }

    return when (code) {
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> WebErrorKind.DNS
        android.webkit.WebViewClient.ERROR_CONNECT -> WebErrorKind.CONNECTION
        android.webkit.WebViewClient.ERROR_IO -> WebErrorKind.CONNECTION
        android.webkit.WebViewClient.ERROR_TIMEOUT -> WebErrorKind.TIMEOUT
        android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME -> WebErrorKind.UNSUPPORTED_SCHEME
        android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> WebErrorKind.SSL
        else -> WebErrorKind.UNKNOWN
    }
}

/**
 * 给用户看的错误代码文本：优先用 Chromium 原始名，否则退回 `WebViewClient.ERROR_*` 的名字。
 *
 * @return 形如 `ERR_CONNECTION_TIMED_OUT (-8)`；两个都拿不到时返回 null
 */
fun webErrorCodeText(code: Int?, description: String?): String? {
    val name = extractNetErrorName(description) ?: legacyErrorName(code)
    return when {
        name != null && code != null -> "$name ($code)"
        name != null -> name
        code != null -> "ERROR ($code)"
        else -> null
    }
}

/** 老枚举 `WebViewClient.ERROR_*` 对应的 Chromium 名字（description 里没有名字时才用）。 */
private fun legacyErrorName(code: Int?): String? = when (code) {
    android.webkit.WebViewClient.ERROR_UNKNOWN -> "ERR_FAILED"
    android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "ERR_NAME_NOT_RESOLVED"
    android.webkit.WebViewClient.ERROR_CONNECT -> "ERR_CONNECTION_FAILED"
    android.webkit.WebViewClient.ERROR_IO -> "ERR_CONNECTION_CLOSED"
    android.webkit.WebViewClient.ERROR_TIMEOUT -> "ERR_TIMED_OUT"
    android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "ERR_UNKNOWN_URL_SCHEME"
    android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "ERR_SSL_PROTOCOL_ERROR"
    android.webkit.WebViewClient.ERROR_BAD_URL -> "ERR_INVALID_URL"
    else -> null
}

/** 按错误分类选一个语义图标。 */
private fun WebErrorKind.icon(): ImageVector = when (this) {
    WebErrorKind.OFFLINE -> Icons.Rounded.WifiOff
    WebErrorKind.DNS -> Icons.Rounded.Dns
    WebErrorKind.CONNECTION -> Icons.Rounded.CloudOff
    WebErrorKind.TIMEOUT -> Icons.Rounded.HourglassEmpty
    WebErrorKind.EMPTY_RESPONSE -> Icons.Rounded.Inbox
    WebErrorKind.SSL -> Icons.Rounded.Lock
    WebErrorKind.UNSUPPORTED_SCHEME -> Icons.Rounded.LinkOff
    WebErrorKind.UNKNOWN -> Icons.Rounded.ErrorOutline
}

/** 按错误分类给一句人话解释。 */
@Composable
private fun WebErrorKind.description(): String = stringResource(
    when (this) {
        WebErrorKind.OFFLINE -> R.string.web_error_desc_offline
        WebErrorKind.DNS -> R.string.web_error_desc_dns
        WebErrorKind.CONNECTION -> R.string.web_error_desc_connection
        WebErrorKind.TIMEOUT -> R.string.web_error_desc_timeout
        WebErrorKind.EMPTY_RESPONSE -> R.string.web_error_desc_empty
        WebErrorKind.SSL -> R.string.web_error_desc_ssl
        WebErrorKind.UNSUPPORTED_SCHEME -> R.string.web_error_desc_scheme
        WebErrorKind.UNKNOWN -> R.string.web_error_desc_unknown
    }
)

/**
 * 统一的网页错误页。
 *
 * 设计意图：**信息不丢**。原来 Chromium 内置页会显示的地址与错误码照样保留在信息卡里，
 * 另外补上人话解释、复制地址、用浏览器打开、重新加载、返回这几条出路。
 *
 * 视觉跟 `CampusNetworkProbeOverlay` 是同一套语言（同心圆 + 主题色 + 语义图标），
 * 但**不做循环动画**：错误态不该一直在那儿闪。
 *
 * @param info 失败页面的地址与错误码；全程可空（例如取 Token 失败时没有 URL）
 * @param title 标题，默认「页面打不开」
 * @param message 覆盖分类给出的解释（取 Token 失败这类业务错误用）
 * @param retrying 重试进行中：主按钮转圈并禁用
 * @param onReload 重新加载；null 表示不给这个入口
 * @param onCopyUrl 复制地址
 * @param onOpenInBrowser 用系统浏览器打开；null 表示不给这个入口
 * @param onBack 返回上一页
 */
@Composable
fun WebPageErrorView(
    info: WebPageErrorInfo,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.web_error_title),
    message: String? = null,
    retrying: Boolean = false,
    onReload: (() -> Unit)? = null,
    onCopyUrl: () -> Unit = {},
    onOpenInBrowser: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    val kind = webErrorKind(info.code, info.description)
    val accent = MaterialTheme.colorScheme.primary
    val codeText = webErrorCodeText(info.code, info.description)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        // 内容不满一屏时垂直居中；超过一屏（横屏 / 超长地址）时从顶部开始正常滚动，
        // 不会被居中裁掉上半截。minHeight 取外层可用高度，所以要放在滚动容器**外面**量。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val verticalPadding = 32.dp
            val minContentHeight = (maxHeight - verticalPadding * 2).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = verticalPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .heightIn(min = minContentHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // ── 图标区：静态同心圆（不做无限动画，避免错误页一直闪） ──
                    Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(132.dp)
                                .border(2.dp, accent.copy(alpha = 0.18f), CircleShape)
                                .background(accent.copy(alpha = 0.06f), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .border(1.dp, accent.copy(alpha = 0.24f), CircleShape)
                                .background(accent.copy(alpha = 0.10f), CircleShape)
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = kind.icon(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = message ?: kind.description(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    // ── 信息卡：原来内置页有的 URL 与错误码，一条不丢 ──
                    if (!info.url.isNullOrBlank() || codeText != null) {
                        Spacer(Modifier.height(24.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                if (!info.url.isNullOrBlank()) {
                                    InfoRow(
                                        label = stringResource(R.string.web_error_label_url),
                                        value = info.url,
                                        valueFontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        trailing = {
                                            IconButton(onClick = onCopyUrl, enabled = info.url.isNotBlank()) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ContentCopy,
                                                    contentDescription = stringResource(R.string.web_error_copy_url),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    )
                                }

                                if (codeText != null) {
                                    if (!info.url.isNullOrBlank()) {
                                        Spacer(Modifier.height(10.dp))
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                                        )
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    InfoRow(
                                        label = stringResource(R.string.web_error_label_code),
                                        value = codeText,
                                        valueFontFamily = FontFamily.Monospace
                                    )
                                    // 原始描述跟错误名不同（例如带了补充说明）时也显示出来
                                    val raw = info.description?.trim()
                                    if (!raw.isNullOrBlank() && extractNetErrorName(raw) != raw) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = raw,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))

                    if (onReload != null) {
                        Button(
                            onClick = onReload,
                            enabled = !retrying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            if (retrying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.web_error_reload))
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!info.url.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = onCopyUrl,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.web_error_copy_url),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (onOpenInBrowser != null) {
                            OutlinedButton(
                                onClick = onOpenInBrowser,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.web_error_open_browser),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (onBack != null) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onBack) {
                            Text(stringResource(R.string.web_error_back))
                        }
                    }
                }
            }
        }
    }
}

/** 信息卡里的一行：左侧标签，右侧等宽值 + 可选尾部按钮。 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueFontFamily: FontFamily = FontFamily.Default,
    maxLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = valueFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            trailing()
        }
    }
}

package com.xingheyuzhuan.shiguangschedule.ui.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 「需要微信授权」统一页。
 *
 * 门户里有些目标（一卡通微信服务、超星等）只在微信客户端里走得通：在 App 里打开它，
 * 落到的是微信自己渲染的「请在微信客户端打开链接」报错页（一个 1KB 的 JS 页面，什么信息都没有）。
 * 微信授权这一步客户端代替不了，所以这里把那一页换成一个说人话的页面：
 * **说明 + 目标站点 + 复制链接 + 返回**，用户复制链接去微信打开即可继续。
 *
 * 「返回」不是退出容器：它关掉这一页并回退 WebView 历史（见 `WebAppScreen` 的返回键处理），
 * 用户回到门户里原来那一页，还能点别的应用。
 *
 * @param targetHost 被拦下来的目标站点（显示出来，让用户知道复制的是哪儿）；
 * @param copyUrl 复制内容（优先级见 [WebAppWechat.pickCopyUrl]）；
 * @param onCopy 点「复制链接」；复制与提示由调用方做（容器里已有同一套剪贴板处理）。
 */
@Composable
internal fun WebAppWechatOverlay(
    targetHost: String,
    copyUrl: String,
    onCopy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.wechat_auth_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.wechat_auth_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp)
            )

            if (targetHost.isNotBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = stringResource(R.string.wechat_auth_target_host, targetHost),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = onCopy, enabled = copyUrl.isNotBlank()) {
                    Text(stringResource(R.string.wechat_auth_copy))
                }
                Spacer(modifier = Modifier.size(12.dp))
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.wechat_auth_back))
                }
            }
        }
    }
}

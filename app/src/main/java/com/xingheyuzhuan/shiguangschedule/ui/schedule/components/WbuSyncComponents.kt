package com.xingheyuzhuan.shiguangschedule.ui.schedule.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.ui.theme.ClassFlowTheme
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

/**
 * WBU 教务一键同步按钮。
 * ClassFlow 定制：独立文件承载，以缩小 WeeklyScheduleScreen 与上游的差异面。
 */
@Composable
fun WbuSyncActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isDark = LocalIsDarkTheme.current
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(end = 8.dp)
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.18f else 0.55f),
                        Color.White.copy(alpha = if (isDark) 0.05f else 0.12f)
                    )
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.55f else 0.72f),
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = "一键同步武商院课表"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WbuSyncActionButtonPreview() {
    ClassFlowTheme {
        WbuSyncActionButton(onClick = {})
    }
}

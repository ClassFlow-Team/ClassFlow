package com.xingheyuzhuan.shiguangschedule

import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 非 Hilt 上下文（BroadcastReceiver、普通 Composable 等）获取依赖的统一入口。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun appSettingsRepository(): AppSettingsRepository
    fun courseTableRepository(): CourseTableRepository
}

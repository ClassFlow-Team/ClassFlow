package com.xingheyuzhuan.shiguangschedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.db.widget.WidgetDatabase
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.model.toProto
import com.xingheyuzhuan.shiguangschedule.data.repository.WidgetRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.scheduleGridStyleDataStore
import com.xingheyuzhuan.shiguangschedule.widget.compact.CompactNativeProvider
import com.xingheyuzhuan.shiguangschedule.widget.compact.CompactNativeRenderer
import com.xingheyuzhuan.shiguangschedule.widget.double_days.DoubleDaysNativeProvider
import com.xingheyuzhuan.shiguangschedule.widget.double_days.DoubleDaysNativeRenderer
import com.xingheyuzhuan.shiguangschedule.widget.list_vertical.ListVerticalNativeProvider
import com.xingheyuzhuan.shiguangschedule.widget.list_vertical.ListVerticalNativeRenderer
import com.xingheyuzhuan.shiguangschedule.widget.tiny.TinyNativeProvider
import com.xingheyuzhuan.shiguangschedule.widget.tiny.TinyNativeRenderer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * 小组件统一分发中心
 * 负责从 Repository 提取数据并分发给所有 5 种规格的原生 Renderer
 */
suspend fun updateAllWidgets(context: Context) {
    try {
        // 1. 初始化数据库和仓库
        val widgetDb = WidgetDatabase.getDatabase(context)
        val repository = WidgetRepository(
            widgetCourseDao = widgetDb.widgetCourseDao(),
            widgetAppSettingsDao = widgetDb.widgetAppSettingsDao(),
            context = context
        )

        // 2. 准备基础数据
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        // 获取今日和明日的所有课程快照 (超时时间 3秒)
        val dbCourses = withTimeoutOrNull(3000L) {
            repository.getWidgetCoursesByDateRange(today.toString(), tomorrow.toString()).first()
        } ?: emptyList() // 超时则返回空列表

        // 获取当前周 (超时时间 2秒)
        val currentWeek = withTimeoutOrNull(2000L) {
            repository.getCurrentWeekFlow().first()
        } ?: 0

        // 获取样式 (超时时间 2秒)
        val currentStyle = withTimeoutOrNull(2000L) {
            context.scheduleGridStyleDataStore.data.first()
        }

        // 样式保底逻辑
        val finalStyleToSync = if (currentStyle == null || currentStyle.course_color_maps.isEmpty()) {
            ScheduleGridStyle.DEFAULT.toProto()
        } else {
            currentStyle
        }

        // 3. 构造数据快照 (Protobuf)

        // 先将数据库实体转为 Wire 的 WidgetCourseProto 对象
        val courseProtoList = dbCourses.map { course ->
            WidgetCourseProto(
                id = course.id,
                name = course.name,
                teacher = course.teacher,
                position = course.position,
                start_time = course.startTime,
                end_time = course.endTime,
                color_int = course.colorInt,
                is_skipped = course.isSkipped,
                date = course.date
            )
        }

        // 直接通过构造函数创建快照对象
        val snapshot = WidgetSnapshot(
            current_week = currentWeek,
            style = finalStyleToSync,
            courses = courseProtoList
        )

        // 4. 定义所有原生尺寸的映射列表
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val nativeConfigs = listOf(
            TinyNativeProvider::class.java to TinyNativeRenderer::render,
            CompactNativeProvider::class.java to CompactNativeRenderer::render,
            DoubleDaysNativeProvider::class.java to DoubleDaysNativeRenderer::render,
            ListVerticalNativeProvider::class.java to ListVerticalNativeRenderer::render
        )

        // 5. 统一分发更新
        nativeConfigs.forEachIndexed { index, (providerClass, renderFunc) ->
            val componentName = ComponentName(context, providerClass)
            val ids = appWidgetManager.getAppWidgetIds(componentName)

            if (ids.isNotEmpty()) {
                if (index > 0) {
                    kotlinx.coroutines.delay(300L)
                }

                try {
                    val remoteViews = renderFunc(context, snapshot)
                    appWidgetManager.updateAppWidget(componentName, remoteViews)
                    Log.d("WidgetUpdateHelper", "成功刷新规格 ${providerClass.simpleName}")
                } catch (e: Exception) {
                    Log.e("WidgetUpdateHelper", "规格 ${providerClass.simpleName} 渲染失败", e)
                }
            }
        }

    } catch (e: Exception) {
        Log.e("WidgetUpdateHelper", "更新流程异常: ${e.stackTraceToString()}")
    }
}
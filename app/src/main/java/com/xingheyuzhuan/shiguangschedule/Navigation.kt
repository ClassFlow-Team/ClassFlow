package com.xingheyuzhuan.shiguangschedule

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 导航目标定义
 * 不再继承自带 String route 的基类，而是使用 @Serializable 标记。
 * 所有的参数直接定义在 data class 的构造函数中。
 */
@Serializable
sealed interface Destination: NavKey {

    // 主屏幕

    @Serializable
    data object CourseSchedule : Destination

    @Serializable
    data object Settings : Destination

    @Serializable
    data object TodaySchedule : Destination

    @Serializable
    data object TimeSlotSettings : Destination

    @Serializable
    data object ManageCourseTables : Destination

    @Serializable
    data object SchoolSelectionListScreen : Destination

    @Serializable
    data object CourseTableConversion : Destination

    @Serializable
    data object NotificationSettings : Destination

    @Serializable
    data object MoreOptions : Destination

    @Serializable
    data object OpenSourceLicenses : Destination

    @Serializable
    data object UpdateRepo : Destination

    @Serializable
    data object QuickActions : Destination

    @Serializable
    data object TweakSchedule : Destination

    @Serializable
    data object QuickDelete : Destination

    @Serializable
    data object ContributionList : Destination

    @Serializable
    data object CourseManagementList : Destination

    @Serializable
    data object StyleSettings : Destination

    // 带参数的屏幕

    @Serializable
    data class AdapterSelection(
        val schoolId: String,
        val schoolName: String,
        val categoryNumber: Int,
        val resourceFolder: String
    ) : Destination

    @Serializable
    data class WebView(
        val initialUrl: String? = "about:blank",
        val assetJsPath: String? = null
    ) : Destination

    @Serializable
    data class AddEditCourse(
        val courseId: String? = null
    ) : Destination

    @Serializable
    data class CourseManagementDetail(
        val courseName: String
    ) : Destination
}
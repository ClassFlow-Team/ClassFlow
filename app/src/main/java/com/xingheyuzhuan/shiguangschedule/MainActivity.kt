package com.xingheyuzhuan.shiguangschedule

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xingheyuzhuan.shiguangschedule.ui.schedule.WeeklyScheduleScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.list.AdapterSelectionScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.list.SchoolSelectionListScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebViewScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.additional.MoreOptionsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.additional.OpenSourceLicensesScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.contribution.ContributionScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.conversion.CourseTableConversionScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.course.AddEditCourseScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.COURSE_NAME_ARG
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.CourseInstanceListScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.CourseNameListScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables.ManageCourseTablesScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.notification.NotificationSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.QuickActionsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.delete.QuickDeleteScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.tweaks.TweakScheduleScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.style.StyleSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.time.TimeSlotManagementScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.update.UpdateRepoScreen
import com.xingheyuzhuan.shiguangschedule.ui.theme.ShiguangScheduleTheme
import com.xingheyuzhuan.shiguangschedule.ui.today.TodayScheduleScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ShiguangScheduleTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Define slide transitions
    val slideIn = { scope: AnimatedContentTransitionScope<*> ->
        scope.slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(300)
        )
    }
    
    val slideOut = { scope: AnimatedContentTransitionScope<*> ->
        scope.slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(300)
        )
    }
    
    val popSlideIn = { scope: AnimatedContentTransitionScope<*> ->
        scope.slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(300)
        )
    }
    
    val popSlideOut = { scope: AnimatedContentTransitionScope<*> ->
        scope.slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(300)
        )
    }

    // Identify main screens that shouldn't have animations between each other
    val mainScreens = listOf(
        Screen.CourseSchedule.route,
        Screen.Settings.route,
        Screen.TodaySchedule.route
    )

    NavHost(
        navController = navController,
        startDestination = Screen.CourseSchedule.route,
        modifier = Modifier.fillMaxSize()
    ){
        // 主界面之间不需要动画
        composable(
            Screen.CourseSchedule.route,
            enterTransition = { if (initialState.destination.route in mainScreens) EnterTransition.None else slideIn(this) },
            exitTransition = { if (targetState.destination.route in mainScreens) ExitTransition.None else slideOut(this) },
            popEnterTransition = { if (initialState.destination.route in mainScreens) EnterTransition.None else popSlideIn(this) },
            popExitTransition = { if (targetState.destination.route in mainScreens) ExitTransition.None else popSlideOut(this) }
        ) {
            WeeklyScheduleScreen(navController = navController)
        }
        
        composable(
            Screen.Settings.route,
            enterTransition = { if (initialState.destination.route in mainScreens) EnterTransition.None else slideIn(this) },
            exitTransition = { if (targetState.destination.route in mainScreens) ExitTransition.None else slideOut(this) },
            popEnterTransition = { if (initialState.destination.route in mainScreens) EnterTransition.None else popSlideIn(this) },
            popExitTransition = { if (targetState.destination.route in mainScreens) ExitTransition.None else popSlideOut(this) }
        ) {
            SettingsScreen(navController = navController)
        }
        
        composable(
            Screen.TodaySchedule.route,
            enterTransition = { if (initialState.destination.route in mainScreens) EnterTransition.None else slideIn(this) },
            exitTransition = { if (targetState.destination.route in mainScreens) ExitTransition.None else slideOut(this) },
            popEnterTransition = { if (initialState.destination.route in mainScreens) EnterTransition.None else popSlideIn(this) },
            popExitTransition = { if (targetState.destination.route in mainScreens) ExitTransition.None else popSlideOut(this) }
        ) {
            TodayScheduleScreen(navController = navController)
        }

        // 其他所有子页面加上平移动画
        composable(
            Screen.TimeSlotSettings.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            TimeSlotManagementScreen(onBackClick = { navController.popBackStack() })
        }
        
        composable(
            Screen.ManageCourseTables.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            ManageCourseTablesScreen(navController = navController)
        }
        
        // 学校选择
        composable(
            Screen.SchoolSelectionListScreen.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            SchoolSelectionListScreen(navController = navController)
        }

        composable(
            route = "adapterSelection/{schoolId}/{schoolName}/{categoryNumber}/{resourceFolder}",
            arguments = listOf(
                navArgument("schoolId") { type = NavType.StringType },
                navArgument("schoolName") { type = NavType.StringType },
                navArgument("categoryNumber") { type = NavType.IntType },
                navArgument("resourceFolder") { type = NavType.StringType }
            ),
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) { backStackEntry ->
            val schoolId = backStackEntry.arguments?.getString("schoolId") ?: ""
            val schoolName = backStackEntry.arguments?.getString("schoolName") ?: "未知学校"
            val categoryNumber = backStackEntry.arguments?.getInt("categoryNumber") ?: 0
            val resourceFolder = backStackEntry.arguments?.getString("resourceFolder") ?: ""

            AdapterSelectionScreen(
                navController = navController,
                schoolId = schoolId,
                schoolName = schoolName,
                categoryNumber = categoryNumber,
                resourceFolder = resourceFolder
            )
        }
        
        composable(
            route = Screen.WebView.route,
            arguments = listOf(
                navArgument("initialUrl") { type = NavType.StringType },
                navArgument("assetJsPath") { type = NavType.StringType }
            ),
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) { backStackEntry ->
            val initialUrl = backStackEntry.arguments?.getString("initialUrl")
            val assetJsPath = backStackEntry.arguments?.getString("assetJsPath")

            WebViewScreen(
                navController = navController,
                initialUrl = initialUrl,
                assetJsPath = assetJsPath,
                courseScheduleRoute = Screen.CourseSchedule.route,
            )
        }
        
        composable(
            Screen.NotificationSettings.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            NotificationSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        
        composable(
            route = Screen.AddEditCourse.route,
            arguments = listOf(
                navArgument("courseId") {
                    type = NavType.StringType
                    nullable = true
                }
            ),
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            AddEditCourseScreen(onNavigateBack = { navController.popBackStack() })
        }
        
        composable(
            Screen.CourseTableConversion.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            CourseTableConversionScreen(navController = navController)
        }
        
        composable(
            Screen.MoreOptions.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            MoreOptionsScreen(navController = navController)
        }
        
        composable(
            Screen.OpenSourceLicenses.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            OpenSourceLicensesScreen (navController = navController)
        }
        
        composable(
            Screen.UpdateRepo.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            UpdateRepoScreen(navController = navController)
        }
        
        composable(
            Screen.QuickActions.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            QuickActionsScreen(navController = navController)
        }
        
        composable(
            Screen.TweakSchedule.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            TweakScheduleScreen(navController = navController)
        }
        
        composable(
            Screen.ContributionList.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            ContributionScreen(navController = navController)
        }
        
        // 课程管理 - 一级页面：课程名称列表
        composable(
            Screen.CourseManagementList.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            // CourseNameListScreen 负责显示不重复的课程名称
            CourseNameListScreen(navController = navController)
        }

        // 课程管理 - 二级页面：课程实例网格
        composable(
            route = Screen.CourseManagementDetail.route,
            arguments = listOf(
                navArgument(COURSE_NAME_ARG) { type = NavType.StringType }
            ),
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) { backStackEntry ->
            val courseName = Uri.decode(backStackEntry.arguments?.getString(COURSE_NAME_ARG) ?: "")
            CourseInstanceListScreen(
                courseName = courseName,
                onNavigateBack = { navController.popBackStack() },
                navController = navController
            )
        }
        
        // 外观定制页面
        composable(
            Screen.StyleSettings.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            StyleSettingsScreen(navController = navController)
        }
        
        // 快速删除课程页面
        composable(
            Screen.QuickDelete.route,
            enterTransition = { slideIn(this) },
            exitTransition = { slideOut(this) },
            popEnterTransition = { popSlideIn(this) },
            popExitTransition = { popSlideOut(this) }
        ) {
            QuickDeleteScreen(navController = navController)
        }
    }
}
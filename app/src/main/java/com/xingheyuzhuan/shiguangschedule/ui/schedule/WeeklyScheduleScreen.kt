package com.xingheyuzhuan.shiguangschedule.ui.schedule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Screen
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.navigation.AddEditCourseChannel
import com.xingheyuzhuan.shiguangschedule.navigation.PresetCourseData
import com.xingheyuzhuan.shiguangschedule.ui.components.BottomNavigationBar
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ConflictCourseBottomSheet
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridStyleComposed
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.WeekSelectorBottomSheet
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 无限时间轴的中值锚点。
 * 使用 Int.MAX_VALUE / 2 允许用户向过去或未来进行几乎无限次数的滑动。
 */
private const val INFINITE_PAGER_CENTER = Int.MAX_VALUE / 2

/**
 * 周课表主屏幕组件。
 * 采用 HorizontalPager 实现无限滚动的周次切换，并与 ViewModel 的数据流深度绑定。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WeeklyScheduleScreen(
    navController: NavHostController,
    viewModel: WeeklyScheduleViewModel = viewModel(factory = WeeklyScheduleViewModelFactory)
) {
    // 订阅 ViewModel 状态，并感知生命周期（当 UI 不可见时停止收集）
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope() // 用于处理 UI 层级的协程（如 Pager 滚动、Snackbar）

    // 预读取字符串资源，避免在回调协程中直接查询 Context 导致警告
    val snackbarMsg = stringResource(id = R.string.snackbar_add_course_after_start)

    // 性能优化：记住 ApplicationContext，确保 ViewModel 中的资源查询在配置更改时稳定
    val appContext = remember { context.applicationContext }

    // 初始化注入：将资源提供逻辑注入 ViewModel，实现逻辑层与 UI 资源的解耦
    LaunchedEffect(Unit) {
        viewModel.setStringProvider { id, args ->
            appContext.resources.getString(id, *args)
        }
    }

    // Pager 状态：设置为中心位置实现左右滑动
    val pagerState = rememberPagerState(
        initialPage = INFINITE_PAGER_CENTER,
        pageCount = { Int.MAX_VALUE }
    )

    /**
     * 核心同步逻辑：
     * 1. 监听 Pager 的当前页码变化。
     * 2. 根据页码与中心点的差值计算对应的物理周次。
     * 3. 更新 ViewModel 中的基准日期，从而触发数据库拉取该周课程。
     */
    LaunchedEffect(pagerState.currentPage, uiState.firstDayOfWeek) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { pageIndex ->
                val offsetWeeks = (pageIndex - INFINITE_PAGER_CENTER).toLong()
                val firstDay = DayOfWeek.of(uiState.firstDayOfWeek)
                // 计算当前周的起始日（周一或周日）
                val thisMonday = today.with(TemporalAdjusters.previousOrSame(firstDay))
                // 计算 Pager 目标页面对应的日期
                val targetMonday = thisMonday.plusWeeks(offsetWeeks)
                viewModel.updatePagerDate(targetMonday)
            }
    }

    // 衍生状态：计算当前页面显示的 MM-dd 格式日期列表（如 09-01, 09-02...）
    val dateStrings by remember(uiState.pagerMondayDate) {
        derivedStateOf {
            val formatter = DateTimeFormatter.ofPattern("MM-dd")
            (0..6).map { uiState.pagerMondayDate.plusDays(it.toLong()).format(formatter) }
        }
    }

    // 衍生状态：计算“今天”在 0-6 索引中的位置，用于高亮显示当前列
    val todayIndex by remember(uiState.pagerMondayDate) {
        derivedStateOf {
            val weekDates = (0..6).map { uiState.pagerMondayDate.plusDays(it.toLong()) }
            weekDates.indexOf(today)
        }
    }

    // UI 交互控制变量
    var showWeekSelector by remember { mutableStateOf(false) } // 周次选择器弹窗
    var showConflictBottomSheet by remember { mutableStateOf(false) } // 课程冲突列表弹窗
    var conflictCoursesToShow by remember { mutableStateOf(emptyList<CourseWithWeeks>()) } // 冲突详情数据源

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior() // 顶部栏折叠行为
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 将数据库的样式模型转换为 Compose 渲染所需的可组合样式
    val composedStyle by remember(uiState.style) {
        derivedStateOf { with(ScheduleGridStyleComposed) { uiState.style.toComposedStyle() } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景层：如果有自定义背景图，通过 AsyncImage 异步加载并填充全屏
        if (composedStyle.backgroundImagePath.isNotEmpty()) {
            AsyncImage(
                model = composedStyle.backgroundImagePath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent, // 允许底层背景透出
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = uiState.weekTitle,
                            modifier = Modifier.clickable {
                                if (!uiState.isSemesterSet || uiState.semesterStartDate == null) {
                                    // 没设置学期，引导去设置
                                    navController.navigate(Screen.Settings.route)
                                } else {
                                    // 只要设置了，点击就弹周次选择器（无论是在开学前、学期中还是学期后）
                                    showWeekSelector = true
                                }
                            }
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (composedStyle.backgroundImagePath.isNotEmpty()) Color.Transparent else MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    ),
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                BottomNavigationBar(
                    navController = navController,
                    currentRoute = currentRoute,
                    isTransparent = composedStyle.backgroundImagePath.isNotEmpty()
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            // 无限循环周课表 Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(innerPadding).fillMaxSize()
            ) { pageIndex ->
                // 性能优化：仅渲染当前可见及左右相邻的一个页面（预加载）
                if (kotlin.math.abs(pageIndex - pagerState.currentPage) <= 1) {
                    ScheduleGrid(
                        style = composedStyle,
                        dates = dateStrings,
                        timeSlots = uiState.timeSlots,
                        mergedCourses = uiState.currentMergedCourses,
                        showWeekends = uiState.showWeekends,
                        todayIndex = todayIndex,
                        firstDayOfWeek = uiState.firstDayOfWeek,
                        onCourseBlockClicked = { mergedBlock ->
                            if (mergedBlock.isConflict) {
                                // 处理冲突：显示底层弹窗供用户选择具体哪一门课程
                                conflictCoursesToShow = mergedBlock.courses
                                showConflictBottomSheet = true
                            } else {
                                // 无冲突：直接跳转详情编辑页
                                mergedBlock.courses.firstOrNull()?.course?.id?.let {
                                    navController.navigate(Screen.AddEditCourse.createRouteWithCourseId(it))
                                }
                            }
                        },
                        onGridCellClicked = { day, section ->
                            // 空白格子点击：如果在学期范围内，预设时间并跳转“添加课程”
                            if (uiState.semesterStartDate != null && !today.isBefore(uiState.semesterStartDate)) {
                                coroutineScope.launch {
                                    AddEditCourseChannel.sendEvent(PresetCourseData(day, section, section))
                                    navController.navigate(Screen.AddEditCourse.createRouteForNewCourse())
                                }
                            } else {
                                // 非学期内弹出提示
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(snackbarMsg)
                                }
                            }
                        },
                        onTimeSlotClicked = {
                            // 点击左侧侧边栏时间轴，进入时间表设置
                            navController.navigate(Screen.TimeSlotSettings.route)
                        }
                    )
                }
            }
        }
    }

    // 周次快速切换弹窗
    if (showWeekSelector) {
        WeekSelectorBottomSheet(
            totalWeeks = uiState.totalWeeks,
            currentWeek = uiState.currentWeekNumber ?: 1,
            selectedWeek = uiState.weekIndexInPager ?: (uiState.currentWeekNumber ?: 1),
            onWeekSelected = { week ->
                val currentWeekAtPage = uiState.weekIndexInPager ?: 1
                val offset = week - currentWeekAtPage
                coroutineScope.launch {
                    // 执行平滑滚动到指定周次对应的页码
                    pagerState.animateScrollToPage(pagerState.currentPage + offset)
                }
                showWeekSelector = false
            },
            onDismissRequest = { showWeekSelector = false }
        )
    }

    // 课程冲突处理弹窗
    if (showConflictBottomSheet) {
        ConflictCourseBottomSheet(
            style = composedStyle,
            courses = conflictCoursesToShow,
            timeSlots = uiState.timeSlots,
            onCourseClicked = { course ->
                showConflictBottomSheet = false
                navController.navigate(Screen.AddEditCourse.createRouteWithCourseId(course.course.id))
            },
            onDismissRequest = { showConflictBottomSheet = false }
        )
    }
}
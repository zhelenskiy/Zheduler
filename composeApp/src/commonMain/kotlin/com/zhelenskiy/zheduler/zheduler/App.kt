@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlin.time.ExperimentalTime
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.materialkolor.rememberDynamicColorScheme
import com.zhelenskiy.zheduler.zheduler.di.AppGraph
import com.zhelenskiy.zheduler.zheduler.di.AppGraphProvider
import com.zhelenskiy.zheduler.zheduler.di.LocalAppGraph
import com.zhelenskiy.zheduler.zheduler.di.awaitDatabaseInitialization
import com.zhelenskiy.zheduler.zheduler.di.createAppGraph
import com.zhelenskiy.zheduler.zheduler.navigation.CalendarRoute
import com.zhelenskiy.zheduler.zheduler.navigation.NewTaskRoute
import com.zhelenskiy.zheduler.zheduler.navigation.SpaceListRoute
import com.zhelenskiy.zheduler.zheduler.navigation.TaskDetailRoute
import com.zhelenskiy.zheduler.zheduler.navigation.TaskEditRoute
import com.zhelenskiy.zheduler.zheduler.navigation.TaskListRoute
import com.zhelenskiy.zheduler.zheduler.navigation.ViewModeEditorRoute
import com.zhelenskiy.zheduler.zheduler.navigation.ViewModeManagementRoute
import com.zhelenskiy.zheduler.zheduler.navigation.SavedFilterManagementRoute
import com.zhelenskiy.zheduler.zheduler.screens.calendar.CalendarScreen
import com.zhelenskiy.zheduler.zheduler.screens.newtask.NewTaskScreen
import com.zhelenskiy.zheduler.zheduler.screens.spacelist.SpaceListScreen
import com.zhelenskiy.zheduler.zheduler.screens.taskdetail.TaskDetailScreen
import com.zhelenskiy.zheduler.zheduler.screens.taskedit.TaskEditScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.TaskListScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.ViewModeEditorScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.ViewModeManagementScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.savedfilter.SavedFilterManagementScreen
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedFilterViewModel
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.theme.getDynamicColorScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    themeModeState: MutableState<ThemeMode> = remember { mutableStateOf(ThemeMode.System) },
    useDynamicColorsState: MutableState<Boolean> = remember { mutableStateOf(true) }
) {
    var appGraph by remember { mutableStateOf<AppGraph?>(null) }

    LaunchedEffect(Unit) {
        awaitDatabaseInitialization()
        appGraph = createAppGraph()
    }
    val (themeMode, onThemeModeChange) = themeModeState
    val (useDynamicColors, onUseDynamicColorsChange) = useDynamicColorsState

    val graph = appGraph
    if (graph != null) {
        MaterialTheme(colorScheme = getColorScheme(themeMode, useDynamicColors)) {
            AppGraphProvider(graph) {
                AppContent(themeMode, onThemeModeChange, useDynamicColors, onUseDynamicColorsChange)
            }
        }
    } else {
        // Show loading state while database initializes
        MaterialTheme(colorScheme = getColorScheme(themeMode, useDynamicColors)) {
            Surface {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun getColorScheme(themeMode: ThemeMode, useDynamicColors: Boolean): ColorScheme {
    val isDarkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val seedColor = (if (useDynamicColors) getDynamicColorScheme(isDarkTheme)?.primary else null)
        ?: Color(0x1E90FF)

    return rememberDynamicColorScheme(seedColor = seedColor, isDark = isDarkTheme)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onUseDynamicColorsChange: (Boolean) -> Unit
) {
    val appGraph = LocalAppGraph.current

    val navController = rememberNavController()
    var refreshTrigger by remember { mutableStateOf(0) }

    NavHost(
        navController = navController,
        startDestination = SpaceListRoute,
        enterTransition = { slideInHorizontally { it } },
        exitTransition = { slideOutHorizontally { -it } },
        popEnterTransition = { slideInHorizontally { -it } },
        popExitTransition = { slideOutHorizontally { it } }
    ) {
        composable<SpaceListRoute> {
            SpaceListScreen(
                viewModel = appGraph.spaceListViewModel,
                refreshTrigger = refreshTrigger,
                onSpaceClick = { spaceId ->
                    navController.navigate(TaskListRoute(spaceId))
                },
                onRefresh = { refreshTrigger++ },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<TaskListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TaskListRoute>()
            val savedStateHandle = backStackEntry.savedStateHandle
            val viewModel = remember(route.spaceId) {
                appGraph.taskListViewModelFactory.create(route.spaceId)
            }

            // Observe loaded filter from SavedFilterManagementScreen
            val loadedFilterId by savedStateHandle.getStateFlow<String?>("loadedFilterId", null).collectAsState()

            TaskListScreen(
                viewModel = viewModel,
                refreshTrigger = refreshTrigger,
                loadedFilterId = loadedFilterId,
                onFilterLoaded = {
                    savedStateHandle["loadedFilterId"] = null
                },
                onTaskClick = { taskId ->
                    navController.navigate(TaskDetailRoute(route.spaceId, taskId))
                },
                onAddTask = {
                    navController.navigate(NewTaskRoute(spaceId = route.spaceId))
                },
                onCopyTask = { taskId ->
                    navController.navigate(NewTaskRoute(spaceId = route.spaceId, taskIdToCopy = taskId))
                },
                onRefresh = { refreshTrigger++ },
                onNavigateToSpaceList = {
                    navController.popBackStack(SpaceListRoute, inclusive = false)
                },
                onNavigateToCalendar = {
                    navController.navigate(CalendarRoute(route.spaceId))
                },
                onNavigateToViewModeManagement = {
                    navController.navigate(ViewModeManagementRoute(route.spaceId))
                },
                onNavigateToSavedFilterManagement = {
                    navController.navigate(SavedFilterManagementRoute(route.spaceId))
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<CalendarRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CalendarRoute>()
            val viewModel = remember(route.spaceId) {
                appGraph.calendarViewModelFactory.create(route.spaceId)
            }
            CalendarScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSpaceList = {
                    navController.popBackStack(SpaceListRoute, inclusive = false)
                },
                onTaskClick = { taskId ->
                    navController.navigate(TaskDetailRoute(route.spaceId, taskId))
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<TaskDetailRoute>(
            enterTransition = {
                val route = targetState.toRoute<TaskDetailRoute>()
                if (route.fromCreation) {
                    fadeIn()
                } else {
                    slideInHorizontally { it }
                }
            },
            exitTransition = {
                slideOutHorizontally { -it }
            },
            popEnterTransition = {
                slideInHorizontally { -it }
            },
            popExitTransition = {
                slideOutHorizontally { it }
            }
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<TaskDetailRoute>()
            val viewModel = remember(route.spaceId, route.taskId) {
                appGraph.taskDetailViewModelFactory.create(
                    spaceId = route.spaceId,
                    taskId = route.taskId
                )
            }
            TaskDetailScreen(
                viewModel = viewModel,
                externalRefreshTrigger = refreshTrigger,
                onNavigateBack = {
                    refreshTrigger++
                    navController.popBackStack()
                },
                onNavigateToEdit = {
                    navController.navigate(TaskEditRoute(route.spaceId, route.taskId))
                },
                onTaskClick = { taskId ->
                    navController.navigate(TaskDetailRoute(route.spaceId, taskId))
                },
                onNavigateToSpaceList = {
                    navController.popBackStack(SpaceListRoute, inclusive = false)
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<TaskEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TaskEditRoute>()
            val savedStateHandle = backStackEntry.savedStateHandle
            val viewModel = remember(route.spaceId, route.taskId) {
                appGraph.taskEditViewModelFactory.create(
                    spaceId = route.spaceId,
                    taskId = route.taskId,
                    savedStateHandle = savedStateHandle
                )
            }
            TaskEditScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    refreshTrigger++
                    navController.popBackStack()
                },
                onAddNewTaskWithConnection = { targetTaskId, connectionType ->
                    navController.navigate(
                        NewTaskRoute(
                            spaceId = route.spaceId,
                            prefilledConnectionTargetId = targetTaskId,
                            prefilledConnectionType = connectionType.name,
                            returnToEditTaskId = route.taskId
                        )
                    )
                },
                onTaskClick = { taskId ->
                    navController.navigate(TaskDetailRoute(route.spaceId, taskId))
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<NewTaskRoute>(
            exitTransition = {
                // Use fade when navigating to TaskDetailRoute (task creation)
                try {
                    val route = targetState.toRoute<TaskDetailRoute>()
                    if (route.fromCreation) {
                        fadeOut()
                    } else {
                        slideOutHorizontally { -it }
                    }
                } catch (e: Exception) {
                    // Not navigating to TaskDetailRoute, use default
                    slideOutHorizontally { -it }
                }
            },
            popExitTransition = {
                slideOutHorizontally { it }
            }
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<NewTaskRoute>()
            val prefilledConnection =
                if (route.prefilledConnectionTargetId != null && route.prefilledConnectionType != null) {
                    TaskConnection(
                        route.prefilledConnectionTargetId,
                        ConnectionType.valueOf(route.prefilledConnectionType)
                    )
                } else null
            val viewModel = remember(route.spaceId, prefilledConnection, route.taskIdToCopy) {
                appGraph.newTaskViewModelFactory.create(
                    spaceId = route.spaceId,
                    prefilledConnection = prefilledConnection,
                    taskIdToCopy = route.taskIdToCopy
                )
            }
            NewTaskScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    refreshTrigger++
                    navController.popBackStack()
                },
                onTaskCreated = { taskId ->
                    refreshTrigger++
                    if (route.returnToEditTaskId != null) {
                        // Just pop back to the parent task - it will stay in edit mode
                        navController.popBackStack()
                    } else {
                        navController.navigate(TaskDetailRoute(route.spaceId, taskId, fromCreation = true)) {
                            popUpTo<NewTaskRoute> { inclusive = true }
                        }
                    }
                },
                onTaskClick = { taskId ->
                    navController.navigate(TaskDetailRoute(route.spaceId, taskId))
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<ViewModeManagementRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ViewModeManagementRoute>()
            val viewModel = remember(route.spaceId) {
                ViewModeViewModel(appGraph.taskRepository, route.spaceId)
            }

            ViewModeManagementScreen(
                viewModel = viewModel,
                onCreateNew = {
                    navController.navigate(ViewModeEditorRoute(route.spaceId))
                },
                onEdit = { viewMode ->
                    navController.navigate(ViewModeEditorRoute(route.spaceId, viewModeId = viewMode.id))
                },
                onCopy = { viewMode ->
                    navController.navigate(ViewModeEditorRoute(route.spaceId, copyFromViewModeId = viewMode.id))
                },
                onBack = {
                    refreshTrigger++
                    navController.popBackStack()
                },
                onNavigateToSpaceList = {
                    navController.popBackStack(SpaceListRoute, inclusive = false)
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<ViewModeEditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ViewModeEditorRoute>()
            val viewModel = remember(route.spaceId) {
                ViewModeViewModel(appGraph.taskRepository, route.spaceId)
            }

            ViewModeEditorScreen(
                viewModel = viewModel,
                viewModeId = route.viewModeId,
                copyFromViewModeId = route.copyFromViewModeId,
                spaceId = route.spaceId,
                onSave = {
                    navController.popBackStack()
                },
                onCancel = {
                    navController.popBackStack()
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }

        composable<SavedFilterManagementRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SavedFilterManagementRoute>()
            val viewModel = remember(route.spaceId) {
                SavedFilterViewModel(appGraph.taskRepository, route.spaceId)
            }

            SavedFilterManagementScreen(
                viewModel = viewModel,
                onLoad = { filter ->
                    // Navigate back with the filter to apply
                    navController.previousBackStackEntry?.savedStateHandle?.set("loadedFilterId", filter.id)
                    navController.popBackStack()
                },
                onBack = {
                    refreshTrigger++
                    navController.popBackStack()
                },
                onNavigateToSpaceList = {
                    navController.popBackStack(SpaceListRoute, inclusive = false)
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange
            )
        }
    }
}

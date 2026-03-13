package com.zhelenskiy.zheduler.zheduler

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
import com.zhelenskiy.zheduler.zheduler.screens.calendar.CalendarScreen
import com.zhelenskiy.zheduler.zheduler.screens.newtask.NewTaskScreen
import com.zhelenskiy.zheduler.zheduler.screens.spacelist.SpaceListScreen
import com.zhelenskiy.zheduler.zheduler.screens.taskdetail.TaskDetailScreen
import com.zhelenskiy.zheduler.zheduler.screens.taskedit.TaskEditScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.TaskListScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.ViewModeEditorScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.ViewModeManagementScreen
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeViewModel
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.theme.getDynamicColorScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var appGraph by remember { mutableStateOf<AppGraph?>(null) }

    LaunchedEffect(Unit) {
        awaitDatabaseInitialization()
        appGraph = createAppGraph()
    }

    val graph = appGraph
    if (graph != null) {
        AppGraphProvider(graph) {
            AppContent()
        }
    } else {
        // Show loading state while database initializes
        MaterialTheme {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent() {
    val appGraph = LocalAppGraph.current

    var themeMode by remember { mutableStateOf(ThemeMode.System) }
    var useDynamicColors by remember { mutableStateOf(true) }

    val systemInDarkTheme = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemInDarkTheme
    }

    val colorScheme = if (useDynamicColors) getDynamicColorScheme(isDarkTheme) else null

    MaterialTheme(
        colorScheme = colorScheme ?: if (isDarkTheme) darkColorScheme() else lightColorScheme()
    ) {
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
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
                )
            }

            composable<TaskListRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TaskListRoute>()
                val viewModel = remember(route.spaceId) {
                    appGraph.taskListViewModelFactory.create(route.spaceId)
                }
                TaskListScreen(
                    viewModel = viewModel,
                    repository = appGraph.taskRepository,
                    refreshTrigger = refreshTrigger,
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
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
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
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
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
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
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
                        navController.navigate(NewTaskRoute(
                            spaceId = route.spaceId,
                            prefilledConnectionTargetId = targetTaskId,
                            prefilledConnectionType = connectionType.name,
                            returnToEditTaskId = route.taskId
                        ))
                    },
                    onTaskClick = { taskId ->
                        navController.navigate(TaskDetailRoute(route.spaceId, taskId))
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
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
                val prefilledConnection = if (route.prefilledConnectionTargetId != null && route.prefilledConnectionType != null) {
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
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
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
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
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
                    onThemeModeChange = { themeMode = it },
                    useDynamicColors = useDynamicColors,
                    onDynamicColorsChange = { useDynamicColors = it }
                )
            }
        }
    }
}

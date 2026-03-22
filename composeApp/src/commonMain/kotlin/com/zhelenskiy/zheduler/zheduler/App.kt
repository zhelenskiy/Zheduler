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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.materialkolor.rememberDynamicColorScheme
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
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
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedFilterContainer
import com.zhelenskiy.zheduler.zheduler.settings.ThemeSettings
import com.zhelenskiy.zheduler.zheduler.settings.createThemeSettingsStore
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.theme.getDynamicColorScheme
import kotlinx.coroutines.flow.first

val DefaultSeedColor = Color(0xFF1E90FF)

/**
 * Data class holding color-related settings.
 * @param savedColor The persisted custom seed color
 * @param previewColor Optional color being previewed (during color picker interaction).
 *                     When null, savedColor is used.
 */
data class ColorSettings(
    val savedColor: Color = DefaultSeedColor,
    val previewColor: Color? = null
) {
    /** The effective color to use for theming - previewColor if set, otherwise savedColor */
    val effectiveColor: Color get() = previewColor ?: savedColor
}

/**
 * Holds the persisted theme state with callbacks.
 * Used to share theme state between App and external window decorations (e.g., JVM title bar).
 */
@Stable
class ThemeState(
    themeMode: ThemeMode = ThemeMode.System,
    useDynamicColors: Boolean = true,
    colorSettings: ColorSettings = ColorSettings()
) {
    var themeMode by mutableStateOf(themeMode)
    var useDynamicColors by mutableStateOf(useDynamicColors)
    var colorSettings by mutableStateOf(colorSettings)
    var settingsLoaded by mutableStateOf(false)

    val colorScheme: ColorScheme
        @Composable get() = getColorScheme(themeMode, useDynamicColors, colorSettings.effectiveColor)
}

@Composable
fun rememberThemeState(): ThemeState {
    val themeSettingsStore = remember { createThemeSettingsStore() }
    val themeState = remember { ThemeState() }

    // Load settings on startup
    LaunchedEffect(Unit) {
        val settings = themeSettingsStore.updates.first() ?: ThemeSettings()
        themeState.themeMode = settings.themeMode
        themeState.useDynamicColors = settings.useDynamicColors
        themeState.colorSettings = ColorSettings(savedColor = Color(settings.customSeedColorArgb))
        themeState.settingsLoaded = true
    }

    // Save settings when they change (only savedColor, not previewColor)
    LaunchedEffect(themeState.themeMode, themeState.useDynamicColors, themeState.colorSettings.savedColor) {
        if (themeState.settingsLoaded) {
            themeSettingsStore.set(
                ThemeSettings(
                    themeMode = themeState.themeMode,
                    useDynamicColors = themeState.useDynamicColors,
                    customSeedColorArgb = themeState.colorSettings.savedColor.toArgb()
                )
            )
        }
    }

    return themeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(themeState: ThemeState = rememberThemeState()) {
    var appGraph by remember { mutableStateOf<AppGraph?>(null) }

    LaunchedEffect(Unit) {
        awaitDatabaseInitialization()
        appGraph = createAppGraph()
    }

    val onThemeModeChange: (ThemeMode) -> Unit = { themeState.themeMode = it }
    val onUseDynamicColorsChange: (Boolean) -> Unit = { themeState.useDynamicColors = it }
    val onColorSettingsChange: (ColorSettings) -> Unit = { themeState.colorSettings = it }

    val graph = appGraph
    if (graph != null) {
        MaterialTheme(colorScheme = themeState.colorScheme) {
            AppGraphProvider(graph) {
                AppContent(
                    themeState.themeMode, onThemeModeChange,
                    themeState.useDynamicColors, onUseDynamicColorsChange,
                    themeState.colorSettings, onColorSettingsChange
                )
            }
        }
    } else {
        // Show loading state while database initializes
        MaterialTheme(colorScheme = themeState.colorScheme) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Zheduler") },
                        colors = appTopAppBarColors()
                    )
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun getColorScheme(themeMode: ThemeMode, useDynamicColors: Boolean, customSeedColor: Color): ColorScheme {
    val isDarkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    if (useDynamicColors) {
        getDynamicColorScheme(isDarkTheme)?.let { return it }
    }

    return rememberDynamicColorScheme(seedColor = customSeedColor, isDark = isDarkTheme)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onUseDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
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
                container = appGraph.spaceListContainer,
                refreshTrigger = refreshTrigger,
                onSpaceClick = { spaceId ->
                    navController.navigate(TaskListRoute(spaceId))
                },
                onRefresh = { refreshTrigger++ },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }

        composable<TaskListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TaskListRoute>()
            val savedStateHandle = backStackEntry.savedStateHandle
            val container = remember(route.spaceId) {
                appGraph.taskListContainerFactory.create(route.spaceId)
            }

            // Observe loaded filter from SavedFilterManagementScreen
            val loadedFilterId by savedStateHandle.getStateFlow<String?>("loadedFilterId", null).collectAsState()

            TaskListScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }

        composable<CalendarRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CalendarRoute>()
            val container = remember(route.spaceId) {
                appGraph.calendarContainerFactory.create(route.spaceId)
            }
            CalendarScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
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
            val container = remember(route.spaceId, route.taskId) {
                appGraph.taskDetailContainerFactory.create(
                    spaceId = route.spaceId,
                    taskId = route.taskId
                )
            }
            TaskDetailScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }

        composable<TaskEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TaskEditRoute>()
            val savedStateHandle = backStackEntry.savedStateHandle
            val container = remember(route.spaceId, route.taskId) {
                appGraph.taskEditContainerFactory.create(
                    spaceId = route.spaceId,
                    taskId = route.taskId,
                    savedStateHandle = savedStateHandle
                )
            }
            TaskEditScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
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
            val container = remember(route.spaceId, prefilledConnection, route.taskIdToCopy) {
                appGraph.newTaskContainerFactory.create(
                    spaceId = route.spaceId,
                    prefilledConnection = prefilledConnection,
                    taskIdToCopy = route.taskIdToCopy
                )
            }
            NewTaskScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }

        composable<ViewModeManagementRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ViewModeManagementRoute>()
            val container = remember(route.spaceId) {
                ViewModeContainer(appGraph.taskRepository, route.spaceId)
            }

            ViewModeManagementScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }

        composable<ViewModeEditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ViewModeEditorRoute>()
            val container = remember(route.spaceId) {
                ViewModeContainer(appGraph.taskRepository, route.spaceId)
            }

            ViewModeEditorScreen(
                container = container,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }

        composable<SavedFilterManagementRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SavedFilterManagementRoute>()
            val container = remember(route.spaceId) {
                SavedFilterContainer(appGraph.taskRepository, route.spaceId)
            }

            SavedFilterManagementScreen(
                container = container,
                spaceId = route.spaceId,
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
                onDynamicColorsChange = onUseDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }
    }
}

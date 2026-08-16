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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.materialkolor.rememberDynamicColorScheme
import com.zhelenskiy.zheduler.zheduler.components.common.LocalFailureSnackbar
import com.zhelenskiy.zheduler.zheduler.components.common.ReportFailures
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.di.AppGraphProvider
import com.zhelenskiy.zheduler.zheduler.di.LocalAppGraph
import com.zhelenskiy.zheduler.zheduler.di.obtainAppGraph
import com.zhelenskiy.zheduler.zheduler.di.peekAppGraph
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
import com.zhelenskiy.zheduler.zheduler.viewmodels.rememberContainer
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

    /** Takes [settings] as the current theme, and counts as having them. */
    fun adopt(settings: ThemeSettings) {
        themeMode = settings.themeMode
        useDynamicColors = settings.useDynamicColors
        colorSettings = ColorSettings(savedColor = Color(settings.customSeedColorArgb))
        settingsLoaded = true
    }
}

/**
 * The settings as last read or written, for the lifetime of the process.
 *
 * Android rebuilds the composition on every configuration change, which re-ran the disk read and
 * let it land on top of a choice made since — reverting it on screen and then persisting the
 * revert. Seeded from here, a rebuilt composition already knows them and reads nothing.
 */
private var loadedThemeSettings: ThemeSettings? = null

@Composable
fun rememberThemeState(): ThemeState {
    val themeSettingsStore = remember { createThemeSettingsStore() }
    val themeState = remember {
        ThemeState().apply { loadedThemeSettings?.let { adopt(it) } }
    }

    // Load settings on startup
    LaunchedEffect(Unit) {
        if (!themeState.settingsLoaded) {
            val settings = themeSettingsStore.updates.first() ?: ThemeSettings()
            loadedThemeSettings = settings
            themeState.adopt(settings)
        }
    }

    // Save settings when they change (only savedColor, not previewColor)
    LaunchedEffect(themeState.themeMode, themeState.useDynamicColors, themeState.colorSettings.savedColor) {
        if (themeState.settingsLoaded) {
            val settings = ThemeSettings(
                themeMode = themeState.themeMode,
                useDynamicColors = themeState.useDynamicColors,
                customSeedColorArgb = themeState.colorSettings.savedColor.toArgb()
            )
            loadedThemeSettings = settings
            themeSettingsStore.set(settings)
        }
    }

    return themeState
}

/**
 * Pops [this] destination, once.
 *
 * A tap handler can run twice before the first pop takes effect — trivial with a mouse — and the
 * second one then removes the screen underneath as well, landing the user somewhere they never
 * asked to go. A destination that is no longer resumed has already been popped.
 */
private fun NavController.popOnce(from: NavBackStackEntry) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(themeState: ThemeState = rememberThemeState()) {
    // Seeded from the existing graph so a recreated activity draws its first frame with content.
    var appGraph by remember { mutableStateOf(peekAppGraph()) }
    var startupFailure by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(Unit) {
        if (appGraph == null) {
            // A database that cannot be opened used to leave the spinner turning for as long as
            // the user was willing to wait, with nothing anywhere saying why. There is no store
            // yet at this point, so this is the only place the failure can be shown.
            try {
                appGraph = obtainAppGraph()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                startupFailure = failure
            }
        }
    }

    val onThemeModeChange: (ThemeMode) -> Unit = { themeState.themeMode = it }
    val onUseDynamicColorsChange: (Boolean) -> Unit = { themeState.useDynamicColors = it }
    val onColorSettingsChange: (ColorSettings) -> Unit = { themeState.colorSettings = it }

    val graph = appGraph
    if (graph != null) {
        MaterialTheme(colorScheme = themeState.colorScheme) {
            AppGraphProvider(graph) {
                // One host above the whole navigation graph: a screen does not need a snackbar of
                // its own for its store to be able to say that something went wrong.
                val failureSnackbar = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalFailureSnackbar provides failureSnackbar) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppContent(
                            themeState.themeMode, onThemeModeChange,
                            themeState.useDynamicColors, onUseDynamicColorsChange,
                            themeState.colorSettings, onColorSettingsChange
                        )
                        SnackbarHost(failureSnackbar, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
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
                    val failure = startupFailure
                    if (failure == null) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = "Could not open the database: " +
                                (failure.message ?: failure::class.simpleName ?: "unknown error"),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
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
    val navigationScope = rememberCoroutineScope()

    /**
     * Opens [taskId], in the space that task belongs to.
     *
     * A description can reference a task in any space, and the reference used to be opened as
     * though it lived in the space being read from: the screen then showed the other space's task
     * with this space's prefix, and its connection pickers searched the wrong space entirely.
     * [fallbackSpaceId] covers a task that has since been deleted, where the old behaviour is no
     * worse than anything else available.
     */
    // Which task the lookup below is already running for. The database round-trip leaves room for
    // a second tap before the first navigates, and two taps on one reference opened it twice.
    var taskBeingOpened by remember { mutableStateOf<String?>(null) }

    fun openTask(taskId: String, fallbackSpaceId: String) {
        if (taskBeingOpened == taskId) return
        taskBeingOpened = taskId
        navigationScope.launch {
            try {
                // A lookup that fails must not take the app with it: this runs in the
                // composition's own scope, where nothing catches an exception and Android ends
                // the process. Opening the task in the space being read from is what happened
                // before there was a lookup at all, so it is a fair thing to fall back to.
                val spaceId = runCatching { appGraph.taskRepository.getSpaceIdForTask(taskId) }
                    .getOrNull() ?: fallbackSpaceId
                // Deliberately not launchSingleTop: it matches on the destination alone, not on
                // its arguments, so following a reference from one task to another replaced the
                // first task's entry instead of stacking on it — and Back skipped everything the
                // reader had walked through.
                navController.navigate(TaskDetailRoute(spaceId, taskId))
            } finally {
                taskBeingOpened = null
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = SpaceListRoute,
        enterTransition = { slideInHorizontally { it } },
        exitTransition = { slideOutHorizontally { -it } },
        popEnterTransition = { slideInHorizontally { -it } },
        popExitTransition = { slideOutHorizontally { it } }
    ) {
        composable<SpaceListRoute> {
            ReportFailures(appGraph.spaceListContainer)
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
            val container = rememberContainer(route.spaceId) {
                appGraph.taskListContainerFactory.create(route.spaceId)
            }
            ReportFailures(container)

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
                    openTask(taskId, fallbackSpaceId = route.spaceId)
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
            val container = rememberContainer(route.spaceId) {
                appGraph.calendarContainerFactory.create(route.spaceId)
            }
            ReportFailures(container)
            CalendarScreen(
                container = container,
                onNavigateBack = {
                    navController.popOnce(backStackEntry)
                },
                onNavigateToSpaceList = {
                    navController.popBackStack(SpaceListRoute, inclusive = false)
                },
                onTaskClick = { taskId ->
                    openTask(taskId, fallbackSpaceId = route.spaceId)
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
            val container = rememberContainer(route.spaceId, route.taskId) {
                appGraph.taskDetailContainerFactory.create(
                    spaceId = route.spaceId,
                    taskId = route.taskId
                )
            }
            ReportFailures(container)
            TaskDetailScreen(
                container = container,
                externalRefreshTrigger = refreshTrigger,
                onNavigateBack = {
                    refreshTrigger++
                    navController.popOnce(backStackEntry)
                },
                onNavigateToEdit = {
                    navController.navigate(TaskEditRoute(route.spaceId, route.taskId))
                },
                onTaskClick = { taskId ->
                    openTask(taskId, fallbackSpaceId = route.spaceId)
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
            val container = rememberContainer(route.spaceId, route.taskId) {
                appGraph.taskEditContainerFactory.create(
                    spaceId = route.spaceId,
                    taskId = route.taskId,
                    savedStateHandle = savedStateHandle
                )
            }
            ReportFailures(container)
            TaskEditScreen(
                container = container,
                onNavigateBack = {
                    refreshTrigger++
                    navController.popOnce(backStackEntry)
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
                    openTask(taskId, fallbackSpaceId = route.spaceId)
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
            val savedStateHandle = backStackEntry.savedStateHandle
            val container = rememberContainer(route.spaceId, prefilledConnection, route.taskIdToCopy) {
                appGraph.newTaskContainerFactory.create(
                    spaceId = route.spaceId,
                    prefilledConnection = prefilledConnection,
                    taskIdToCopy = route.taskIdToCopy,
                    savedStateHandle = savedStateHandle,
                )
            }
            ReportFailures(container)
            NewTaskScreen(
                container = container,
                onNavigateBack = {
                    refreshTrigger++
                    navController.popOnce(backStackEntry)
                },
                onTaskCreated = { taskId ->
                    refreshTrigger++
                    if (route.returnToEditTaskId != null) {
                        // Just pop back to the parent task - it will stay in edit mode
                        navController.popOnce(backStackEntry)
                    } else {
                        navController.navigate(TaskDetailRoute(route.spaceId, taskId, fromCreation = true)) {
                            popUpTo<NewTaskRoute> { inclusive = true }
                        }
                    }
                },
                onTaskClick = { taskId ->
                    openTask(taskId, fallbackSpaceId = route.spaceId)
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
            val container = rememberContainer(route.spaceId) {
                ViewModeContainer(appGraph.taskRepository, route.spaceId)
            }
            ReportFailures(container)

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
                    navController.popOnce(backStackEntry)
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
            val container = rememberContainer(route.spaceId) {
                ViewModeContainer(appGraph.taskRepository, route.spaceId)
            }
            ReportFailures(container)

            ViewModeEditorScreen(
                container = container,
                viewModeId = route.viewModeId,
                copyFromViewModeId = route.copyFromViewModeId,
                spaceId = route.spaceId,
                onSave = {
                    navController.popOnce(backStackEntry)
                },
                onCancel = {
                    navController.popOnce(backStackEntry)
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
            val container = rememberContainer(route.spaceId) {
                SavedFilterContainer(appGraph.taskRepository, route.spaceId)
            }
            ReportFailures(container)

            SavedFilterManagementScreen(
                container = container,
                spaceId = route.spaceId,
                onLoad = { filter ->
                    // Navigate back with the filter to apply
                    navController.previousBackStackEntry?.savedStateHandle?.set("loadedFilterId", filter.id)
                    navController.popOnce(backStackEntry)
                },
                onBack = {
                    refreshTrigger++
                    navController.popOnce(backStackEntry)
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

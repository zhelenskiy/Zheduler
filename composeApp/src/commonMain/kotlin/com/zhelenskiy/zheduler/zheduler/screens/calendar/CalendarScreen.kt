@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.AnimatedVisibility as BoxAnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.util.TaskStatusChange
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarIntent
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarState
import pro.respawn.flowmvi.compose.dsl.subscribe
import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Suppress("UnusedReceiverParameter")
/** [YearMonth] as the pair of numbers a platform state registry can hold. */
private val yearMonthSaver = listSaver<YearMonth, Int>(
    save = { listOf(it.year, it.month.number) },
    restore = { YearMonth(it[0], Month(it[1])) },
)

/** A nullable [LocalDate] as its epoch day, or an empty list for none. */
private val localDateSaver = listSaver<LocalDate?, Int>(
    save = { date -> date?.let { listOf(it.toEpochDays().toInt()) } ?: emptyList() },
    restore = { saved -> saved.firstOrNull()?.let { LocalDate.fromEpochDays(it.toLong()) } },
)

@Composable
fun BoxScope.AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandIn(),
    exit: ExitTransition = shrinkOut() + fadeOut(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) = BoxAnimatedVisibility(visible, modifier, enter, exit, label, content)

/**
 * Represents a year and month combination
 */
data class YearMonth(val year: Int, val month: Month) {
    val displayName: String
        get() = "${month.name.lowercase().replaceFirstChar { it.uppercase() }} $year"

    fun firstDay(): LocalDate = LocalDate(year, month, 1)

    fun lengthOfMonth(): Int {
        val nextMonth = if (month == Month.DECEMBER) {
            LocalDate(year + 1, Month.JANUARY, 1)
        } else {
            LocalDate(year, Month.entries[month.ordinal + 1], 1)
        }
        return (nextMonth.toEpochDays() - firstDay().toEpochDays()).toInt()
    }
}

private fun YearMonth.toPreviousMonth(): YearMonth =
    if (month == Month.JANUARY) {
        YearMonth(year - 1, Month.DECEMBER)
    } else {
        YearMonth(year, Month.entries[month.ordinal - 1])
    }

private fun YearMonth.toNextMonth(): YearMonth =
    if (month == Month.DECEMBER) {
        YearMonth(year + 1, Month.JANUARY)
    } else {
        YearMonth(year, Month.entries[month.ordinal + 1])
    }

private fun getMonthTransitionAnimation(isNavigatingForward: Boolean?) =
    when (isNavigatingForward) {
        true -> slideInHorizontally { width -> width } + fadeIn() togetherWith
                slideOutHorizontally { width -> -width } + fadeOut()

        false -> slideInHorizontally { width -> -width } + fadeIn() togetherWith
                slideOutHorizontally { width -> width } + fadeOut()

        null -> fadeIn() togetherWith fadeOut()
    }

private fun getEventEnterAnimation(isNavigatingForward: Boolean?) =
    when (isNavigatingForward) {
        true -> slideInHorizontally { width -> width } + fadeIn()
        false -> slideInHorizontally { width -> -width } + fadeIn()
        null -> fadeIn()
    }

private fun getEventExitAnimation(isNavigatingForward: Boolean?) =
    when (isNavigatingForward) {
        true -> slideOutHorizontally { width -> -width } + fadeOut()
        false -> slideOutHorizontally { width -> width } + fadeOut()
        null -> fadeOut()
    }

private fun isDateInMonth(date: LocalDate?, yearMonth: YearMonth): Boolean =
    date?.let { it.year == yearMonth.year && it.month == yearMonth.month } ?: false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    container: CalendarContainer,
    onNavigateBack: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    onTaskClick: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val state by container.store.subscribe()

    // Re-read when the day turns. Fixed at first composition, a desktop app left open overnight
    // kept ringing yesterday and "Go to Today" jumped to it.
    val today by produceState(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())) {
        while (true) {
            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()
            value = now.toLocalDateTime(zone)
            val nextMidnight = value.date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
            delay((nextMidnight - now).coerceAtLeast(1.minutes))
        }
    }

    // Saveable: opening a task from a status-change card rebuilds this composition on the way
    // back, and on Android so does a rotation. Plain remember dropped the month the user had
    // browsed to and the day they had picked, snapping back to today each time.
    var currentMonth by rememberSaveable(stateSaver = yearMonthSaver) {
        mutableStateOf(YearMonth(today.year, today.month))
    }
    var selectedDate by rememberSaveable(stateSaver = localDateSaver) {
        mutableStateOf<LocalDate?>(today.date)
    }

    // Track navigation direction for slide animation
    var isNavigatingForward: Boolean? by remember { mutableStateOf(true) }

    // Load status changes when month changes
    LaunchedEffect(currentMonth) {
        container.store.intent(CalendarIntent.LoadStatusChanges(currentMonth.year, currentMonth.month.number))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status Changes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        currentMonth = YearMonth(today.year, today.month)
                        selectedDate = today.date
                    }) {
                        Icon(Icons.Default.Today, contentDescription = "Go to Today")
                    }
                    IconButton(onClick = onNavigateToSpaceList) {
                        Icon(Icons.Default.Home, contentDescription = "Spaces")
                    }
                    ThemeMenuButton(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        useDynamicColors = useDynamicColors,
                        onDynamicColorsChange = onDynamicColorsChange,
                        colorSettings = colorSettings,
                        onColorSettingsChange = onColorSettingsChange
                    )
                },
                colors = appTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top
            ) {
                MonthNavigationHeader(
                    yearMonth = currentMonth,
                    onPreviousMonth = {
                        isNavigatingForward = false
                        currentMonth = currentMonth.toPreviousMonth()
                    },
                    onNextMonth = {
                        isNavigatingForward = true
                        currentMonth = currentMonth.toNextMonth()
                    }
                )

                AnimatedContent(
                    targetState = currentMonth,
                    transitionSpec = {
                        getMonthTransitionAnimation(isNavigatingForward)
                    },
                    label = "calendar_month_animation"
                ) { targetMonth ->
                    CalendarGrid(
                        yearMonth = targetMonth,
                        selectedDate = selectedDate,
                        statusChangesByDate = state.statusChangesByDate,
                        onDateSelected = { selectedDate = it; isNavigatingForward = null },
                        today = today.date,
                    )
                }
            }

            val visible = isDateInMonth(selectedDate, currentMonth)
            AnimatedVisibility(
                visible = visible,
                enter = getEventEnterAnimation(isNavigatingForward),
                exit = getEventExitAnimation(isNavigatingForward)
            ) {
                selectedDate?.let { targetDate ->
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        val events = state.statusChangesByDate[targetDate] ?: emptyList()
                        SelectedDateEvents(
                            date = targetDate,
                            events = events,
                            onTaskClick = onTaskClick,
                            state = state,
                            loadTask = { taskId -> container.store.intent(CalendarIntent.LoadTask(taskId)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthNavigationHeader(
    yearMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
        }

        Text(
            text = yearMonth.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )

        IconButton(onClick = onNextMonth) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
        }
    }
}

data class CalendarLayoutInfo(
    val firstDayOfWeek: Int,
    val daysInMonth: Int,
    val startOffset: Int
) {
    companion object {
        fun from(yearMonth: YearMonth): CalendarLayoutInfo {
            val firstDay = yearMonth.firstDay()
            val firstDayOfWeek = firstDay.dayOfWeek.isoDayNumber
            val daysInMonth = yearMonth.lengthOfMonth()
            val startOffset = firstDayOfWeek - 1
            return CalendarLayoutInfo(firstDayOfWeek, daysInMonth, startOffset)
        }
    }

    fun hasAnyDayInWeek(week: Int): Boolean {
        val startIndex = week * 7
        return (startIndex until startIndex + 7).any { cellIndex ->
            val dayNumber = cellIndex - startOffset + 1
            dayNumber in 1..daysInMonth
        }
    }

    fun getDayNumber(week: Int, dayInWeek: Int): Int? {
        val cellIndex = week * 7 + dayInWeek
        val dayNumber = cellIndex - startOffset + 1
        return if (dayNumber in 1..daysInMonth) dayNumber else null
    }
}

@Composable
private fun WeekDayHeaders(daysOfWeek: List<String>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        daysOfWeek.forEach { day ->
            Text(
                text = day,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    yearMonth: YearMonth,
    selectedDate: LocalDate?,
    statusChangesByDate: Map<LocalDate, List<StatusChangeEvent>>,
    onDateSelected: (LocalDate) -> Unit,
    // Passed in rather than read here: the grid draws the today-ring, and a copy captured at
    // first composition kept ringing yesterday once the screen had been open overnight.
    today: LocalDate,
    modifier: Modifier = Modifier
) {
    val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val layoutInfo = remember(yearMonth) { CalendarLayoutInfo.from(yearMonth) }

    Column(modifier = modifier.fillMaxWidth()) {
        WeekDayHeaders(daysOfWeek)
        Spacer(modifier = Modifier.height(4.dp))

        // Calendar days
        for (week in 0..5) {
            if (!layoutInfo.hasAnyDayInWeek(week) && week > 0) break

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayInWeek in 0..6) {
                    val dayNumber = layoutInfo.getDayNumber(week, dayInWeek)

                    if (dayNumber != null) {
                        val date = LocalDate(yearMonth.year, yearMonth.month, dayNumber)
                        val changeCount = statusChangesByDate[date]?.size ?: 0
                        val isSelected = date == selectedDate
                        val isToday = date == today

                        CalendarDayCell(
                            dayNumber = dayNumber,
                            changeCount = changeCount,
                            isSelected = isSelected,
                            isToday = isToday,
                            onClick = { onDateSelected(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalendarDayCell(
    dayNumber: Int,
    changeCount: Int,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }

    val borderColor = when {
        isToday && !isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }

    // Everything this cell says apart from the number is said in colour: selected by its fill,
    // today by its ring, and how much happened by up to three dots. Spelled out here as well, or a
    // reader hears a bare list of numbers — which is the whole of what this screen is for.
    val description = buildString {
        append(dayNumber)
        if (isToday) append(", today")
        append(
            when (changeCount) {
                0 -> ", nothing happened"
                1 -> ", 1 change"
                else -> ", $changeCount changes"
            }
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(1.dp, borderColor, CircleShape)
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = isSelected
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = dayNumber.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            if (changeCount > 0) {
                val dotCount = minOf(changeCount, 3)
                val backgroundColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    repeat(dotCount) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(backgroundColor)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SelectedDateEvents(
    date: LocalDate,
    events: List<StatusChangeEvent>,
    onTaskClick: (String) -> Unit,
    state: CalendarState,
    loadTask: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = formatDateHeader(date, Clock.System),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )


        var eventsLast by remember { mutableStateOf(events) }
        // Update eventsLast when events changes and is not empty, to keep showing old events during fade out
        LaunchedEffect(events) {
            if (events.isNotEmpty()) {
                eventsLast = events
            }
        }
        Box {
            AnimatedVisibility(events.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    eventsLast.forEach { event ->
                        StatusChangeCard(
                            event = event,
                            onClick = { onTaskClick(event.task.id) },
                            state = state,
                            loadTask = loadTask,
                            onTaskClick = onTaskClick
                        )
                    }
                }
                // The latest value, not the one this effect was set up with: keyed on Unit it held
                // the first `events`, and wrote back a day the user had already left.
                val currentEvents by rememberUpdatedState(events)
                DisposableEffect(Unit) {
                    onDispose { eventsLast = currentEvents }
                }
            }
            AnimatedVisibility(events.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = "No status changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getBlockerTaskIds(event: StatusChangeEvent): Set<String> {
    val prevStatus = event.statusChange.previousStatus
    val newStatus = event.statusChange.newStatus

    return buildSet {
        if (prevStatus is TaskStatus.Blocked) {
            addAll(prevStatus.blockerTaskIds)
        }
        if (newStatus is TaskStatus.Blocked) {
            addAll(newStatus.blockerTaskIds)
        }
    }
}

@Composable
private fun StatusChangeCard(
    event: StatusChangeEvent,
    onClick: () -> Unit,
    state: CalendarState,
    loadTask: (String) -> Unit,
    onTaskClick: (String) -> Unit
) {
    // Load blocker tasks via intent
    val blockerTaskIds = remember(event) { getBlockerTaskIds(event) }
    LaunchedEffect(blockerTaskIds) {
        blockerTaskIds.forEach { loadTask(it) }
    }
    val blockerTasks = blockerTaskIds.mapNotNull { id -> state.loadedTasks[id]?.let { id to it } }.toMap()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            TaskInfoRow(event)
            TaskStatusChangeRow(event, blockerTasks, onTaskClick, state, loadTask)
        }
    }
}

@Composable
private fun TaskInfoRow(event: StatusChangeEvent) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = event.task.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = event.task.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TaskStatusChangeRow(
    event: StatusChangeEvent,
    blockerTasks: Map<String, Task>,
    onTaskClick: (String) -> Unit,
    state: CalendarState,
    loadTask: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTimeOnly(event.statusChange.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )

        TaskStatusChange(
            change = event.statusChange,
            blockerTasks = blockerTasks,
            onBlockerTaskClick = onTaskClick,
            loadedTasks = state.loadedTasks,
            loadTask = loadTask,
            onTaskClick = onTaskClick
        )
    }
}

private fun formatDateHeader(date: LocalDate, clock: Clock): String {
    val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val daysDiff = date.toEpochDays() - today.toEpochDays()

    val dateStr = "${date.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${date.day}, ${date.year}"

    return when (daysDiff.toInt()) {
        0 -> "Today - $dateStr"
        1 -> "Tomorrow - $dateStr"
        -1 -> "Yesterday - $dateStr"
        else -> dateStr
    }
}

private fun formatTimeOnly(instant: Instant): String {
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val second = dateTime.second.toString().padStart(2, '0')
    return "$hour:$minute:$second"
}

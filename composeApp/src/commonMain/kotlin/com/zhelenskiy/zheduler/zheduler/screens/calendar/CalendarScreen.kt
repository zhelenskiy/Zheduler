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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.components.common.AutomaticChangeIndicator
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.util.TaskStatusChange
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarViewModel
import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Suppress("UnusedReceiverParameter")
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
    viewModel: CalendarViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    onTaskClick: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }

    var currentMonth by remember {
        mutableStateOf(YearMonth(today.year, today.month))
    }
    var selectedDate by remember { mutableStateOf<LocalDate?>(today.date) }

    // Track navigation direction for slide animation
    var isNavigatingForward: Boolean? by remember { mutableStateOf(true) }

    // Get status changes grouped by date via view model - reload when month changes
    var statusChangesByDate by remember { mutableStateOf<Map<LocalDate, List<StatusChangeEvent>>>(emptyMap()) }

    LaunchedEffect(viewModel, currentMonth) {
        statusChangesByDate = viewModel.getStatusChangesByDate(currentMonth.year, currentMonth.month.number)
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
                        onDynamicColorsChange = onDynamicColorsChange
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
                        statusChangesByDate = statusChangesByDate,
                        onDateSelected = { selectedDate = it; isNavigatingForward = null },
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

                        val events = statusChangesByDate[targetDate] ?: emptyList()
                        SelectedDateEvents(
                            date = targetDate,
                            events = events,
                            onTaskClick = onTaskClick,
                            getTaskById = viewModel::getTaskById,
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
    modifier: Modifier = Modifier
) {
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
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
private fun CalendarDayCell(
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
    getTaskById: suspend (String) -> Task?,
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
                            getTaskById = getTaskById,
                            onTaskClick = onTaskClick
                        )
                    }
                }
                DisposableEffect(Unit) {
                    onDispose { eventsLast = events }
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

private suspend fun loadBlockerTasks(
    event: StatusChangeEvent,
    getTaskById: suspend (String) -> Task?
): Map<String, Task> {
    val tasks = mutableMapOf<String, Task>()
    val prevStatus = event.statusChange.previousStatus
    val newStatus = event.statusChange.newStatus

    val blockerIds = buildSet {
        if (prevStatus is TaskStatus.Blocked) {
            addAll(prevStatus.blockerTaskIds)
        }
        if (newStatus is TaskStatus.Blocked) {
            addAll(newStatus.blockerTaskIds)
        }
    }

    blockerIds.forEach { blockerId ->
        getTaskById(blockerId)?.let { task ->
            tasks[blockerId] = task
        }
    }
    return tasks
}

@Composable
private fun StatusChangeCard(
    event: StatusChangeEvent,
    onClick: () -> Unit,
    getTaskById: suspend (String) -> Task?,
    onTaskClick: (String) -> Unit
) {
    // Load blocker tasks asynchronously
    var blockerTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }

    LaunchedEffect(event) {
        blockerTasks = loadBlockerTasks(event, getTaskById)
    }

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
            TaskStatusChangeRow(event, blockerTasks, onTaskClick, getTaskById)
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
    getTaskById: suspend (String) -> Task?
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
            onBlockerTaskClick = onTaskClick
        )

        event.statusChange.automaticChangeReason?.let {
            AutomaticChangeIndicator(
                reason = it,
                getTaskById = getTaskById,
                onTaskClick = onTaskClick
            )
        }
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

private fun formatTimeOnly(instant: kotlin.time.Instant): String {
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val second = dateTime.second.toString().padStart(2, '0')
    return "$hour:$minute:$second"
}

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Modifier.Companion
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
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarViewModel
import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Suppress("UnusedReceiverParameter")
@Composable
public fun BoxScope.AnimatedVisibility(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onNavigateBack: () -> Unit,
    onTaskClick: (String) -> Unit
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
                    // Month navigation header
                    MonthNavigationHeader(
                        yearMonth = currentMonth,
                        onPreviousMonth = {
                            isNavigatingForward = false
                            currentMonth = if (currentMonth.month == Month.JANUARY) {
                                YearMonth(currentMonth.year - 1, Month.DECEMBER)
                            } else {
                                YearMonth(currentMonth.year, Month.entries[currentMonth.month.ordinal - 1])
                            }
                        },
                        onNextMonth = {
                            isNavigatingForward = true
                            currentMonth = if (currentMonth.month == Month.DECEMBER) {
                                YearMonth(currentMonth.year + 1, Month.JANUARY)
                            } else {
                                YearMonth(currentMonth.year, Month.entries[currentMonth.month.ordinal + 1])
                            }
                        }
                    )

                    val slideAnimation = when (isNavigatingForward) {
                        true -> {
                            slideInHorizontally { width -> width } + fadeIn() togetherWith
                                    slideOutHorizontally { width -> -width } + fadeOut()
                        }
                        false -> {
                            slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                    slideOutHorizontally { width -> width } + fadeOut()
                        }
                        null -> fadeIn() togetherWith fadeOut()
                    }
                    // Calendar grid with slide animation
                    AnimatedContent(
                        targetState = currentMonth,
                        transitionSpec = {
                            slideAnimation
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

            // Selected date events - only show if selected date is in current month
            val showEvents = selectedDate?.let { date ->
                date.year == currentMonth.year && date.month == currentMonth.month
            } ?: false

            val visible = showEvents && selectedDate != null
            AnimatedVisibility(
                visible = visible,
                enter = when (isNavigatingForward) {
                    true -> slideInHorizontally { width -> width } + fadeIn()
                    false -> slideInHorizontally { width -> -width } + fadeIn()
                    null -> fadeIn()
                },
                exit = when (isNavigatingForward) {
                    true -> slideOutHorizontally { width -> -width } + fadeOut()
                    false -> slideOutHorizontally { width -> width } + fadeOut()
                    null -> fadeOut()
                }
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

    Column(modifier = modifier.fillMaxWidth()) {
        // Day of week headers
        Row(modifier = Modifier.fillMaxWidth()) {
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

        Spacer(modifier = Modifier.height(4.dp))

        // Calendar days
        val firstDay = yearMonth.firstDay()
        val firstDayOfWeek = firstDay.dayOfWeek.isoDayNumber
        val daysInMonth = yearMonth.lengthOfMonth()
        val startOffset = firstDayOfWeek - 1

        for (week in 0 until 6) {
            val startIndex = week * 7
            val hasAnyDayInWeek = (startIndex until startIndex + 7).any { cellIndex ->
                val dayNumber = cellIndex - startOffset + 1
                dayNumber in 1..daysInMonth
            }

            if (!hasAnyDayInWeek && week > 0) break

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayInWeek in 0 until 7) {
                    val cellIndex = startIndex + dayInWeek
                    val dayNumber = cellIndex - startOffset + 1

                    if (dayNumber in 1..daysInMonth) {
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    repeat(dotCount) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
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
        blockerTasks = tasks
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
            // Task ID and title on one line
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

            // Status change row - compact
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimeOnly(event.statusChange.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )

                // Show previous status with details
                event.statusChange.previousStatus?.let { prevStatus ->
                    StatusBadge(status = prevStatus)
                    when (prevStatus) {
                        is TaskStatus.Blocked -> {
                            // Show blocker tasks if any
                            if (prevStatus.blockerTaskIds.isNotEmpty()) {
                                Text(
                                    text = "by",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                prevStatus.blockerTaskIds.forEach { blockerId ->
                                    val blockerTask = blockerTasks[blockerId]
                                    ConnectedTaskChip(
                                        task = blockerTask,
                                        taskId = blockerId,
                                        onClick = { blockerTask?.let { onTaskClick(it.id) } }
                                    )
                                }
                            }
                            // Show comment if any
                            if (prevStatus.comment.isNotEmpty()) {
                                Text(
                                    text = prevStatus.comment,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is TaskStatus.Declined -> {
                            // Show decline reason
                            Text(
                                text = prevStatus.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {}
                    }
                    Text("→", style = MaterialTheme.typography.labelSmall)
                }

                // Show new status with details
                StatusBadge(status = event.statusChange.newStatus)
                when (val newStatus = event.statusChange.newStatus) {
                    is TaskStatus.Blocked -> {
                        // Show blocker tasks if any
                        if (newStatus.blockerTaskIds.isNotEmpty()) {
                            Text(
                                text = "by",
                                style = MaterialTheme.typography.labelSmall
                            )
                            newStatus.blockerTaskIds.forEach { blockerId ->
                                val blockerTask = blockerTasks[blockerId]
                                ConnectedTaskChip(
                                    task = blockerTask,
                                    taskId = blockerId,
                                    onClick = { blockerTask?.let { onTaskClick(it.id) } }
                                )
                            }
                        }
                        // Show comment if any
                        if (newStatus.comment.isNotEmpty()) {
                            Text(
                                text = newStatus.comment,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is TaskStatus.Declined -> {
                        // Show decline reason
                        Text(
                            text = newStatus.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {}
                }

                event.statusChange.automaticChangeReason?.let{
                    AutomaticChangeIndicator(
                        reason = it,
                        getTaskById = getTaskById,
                        onTaskClick = onTaskClick
                    )
                }
            }
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

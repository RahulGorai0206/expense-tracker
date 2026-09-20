package com.myapp.expensetracker.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapp.expensetracker.CategorySpending
import com.myapp.expensetracker.ui.components.animatedCount
import com.myapp.expensetracker.ui.components.animatedAmount
import com.myapp.expensetracker.ui.components.ChartSkeleton
import com.myapp.expensetracker.ui.components.SummaryCardSkeleton
import com.myapp.expensetracker.MonthlySpending
import com.myapp.expensetracker.TagSpending
import com.myapp.expensetracker.ui.components.getCategoryInfo
import com.myapp.expensetracker.viewmodel.AnalyticsViewModel
import com.myapp.expensetracker.viewmodel.DateRangePreset
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Curated palette for donut chart ────────────────────────────────────
private val ChartColors = listOf(
    Color(0xFF6C63FF),  // Purple
    Color(0xFF00BFA6),  // Teal
    Color(0xFFFF6B6B),  // Coral
    Color(0xFFFFD93D),  // Gold
    Color(0xFF4FC3F7),  // Sky blue
    Color(0xFFFF8A65),  // Peach
    Color(0xFFBA68C8),  // Violet
    Color(0xFF81C784),  // Green
    Color(0xFFF06292),  // Pink
    Color(0xFF90A4AE),  // Grey
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen() {
    val viewModel: AnalyticsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()

    var showDatePicker by remember { mutableStateOf(false) }

    // Date range picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = state.startDate,
            initialSelectedEndDateMillis = state.endDate
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = datePickerState.selectedStartDateMillis
                    val end = datePickerState.selectedEndDateMillis
                    if (start != null && end != null) {
                        viewModel.setCustomRange(start, end)
                    }
                    showDatePicker = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DateRangePicker(
                state = datePickerState,
                modifier = Modifier.height(500.dp)
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────
        item {
            Column {
                Text(
                    "Analytics",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Your spending insights",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }

        // ── Date Range Chips ───────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DateRangePreset.entries.forEach { preset ->
                    val isSelected = state.selectedPreset == preset
                    val bgColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        label = "chipBg"
                    )
                    val textColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        label = "chipText"
                    )

                    Surface(
                        onClick = {
                            if (preset == DateRangePreset.CUSTOM) {
                                showDatePicker = true
                            } else {
                                viewModel.setPreset(preset)
                            }
                        },
                        shape = RoundedCornerShape(100.dp),
                        color = bgColor,
                        tonalElevation = if (isSelected) 0.dp else 2.dp
                    ) {
                        Text(
                            text = preset.label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = textColor,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ── Spending Summary Card ──────────────────────────────────────
        item {
            if (state.isLoading) {
                SummaryCardSkeleton()
            } else SpendingSummaryCard(
                totalSpent = state.totalSpent,
                dailyAverage = state.dailyAverage,
                transactionDays = state.spendingDays,
                monthOverMonth = state.monthOverMonthChange
            )
        }

        // ── Bar Chart ──────────────────────────────────────────────────
        item {
            SectionHeader(title = "Monthly Spending", icon = Icons.Default.BarChart)
            Spacer(modifier = Modifier.height(12.dp))
            if (state.isLoading) {
                ChartSkeleton()
            } else if (state.monthlySpending.isEmpty()) {
                EmptyChartPlaceholder("No spending data for this period")
            } else {
                MonthlyBarChart(data = state.monthlySpending)
            }
        }

        // ── Category Donut Chart ───────────────────────────────────────
        item {
            SectionHeader(title = "Category Breakdown", icon = Icons.Default.DonutLarge)
            Spacer(modifier = Modifier.height(12.dp))
            if (state.isLoading) {
                ChartSkeleton(circular = true)
            } else if (state.categorySpending.isEmpty()) {
                EmptyChartPlaceholder("No category data for this period")
            } else {
                CategoryDonutChart(categories = state.categorySpending)
            }
        }

        // ── Tag Donut Chart ────────────────────────────────────────────
        item {
            SectionHeader(title = "Tag Breakdown", icon = Icons.AutoMirrored.Filled.Label)
            Spacer(modifier = Modifier.height(12.dp))
            if (state.isLoading) {
                ChartSkeleton(circular = true)
            } else if (state.tagSpending.isEmpty()) {
                EmptyChartPlaceholder("No tag data for this period")
            } else {
                TagDonutChart(tags = state.tagSpending)
            }
        }

        // ── Insight Cards ──────────────────────────────────────────────
        item {
            SectionHeader(title = "Spending Insights", icon = Icons.Default.Insights)
            Spacer(modifier = Modifier.height(12.dp))
            InsightCardsGrid(
                topDay = state.topSpendingDay?.let {
                    "${it.dayLabel} - \u20B9%,.0f".format(it.total)
                },
                dailyAvg = "\u20B9%,.0f".format(state.dailyAverage),
                topCategory = state.topCategory?.category ?: "—",
                spendingDays = state.spendingDays.toString()
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Spending Summary Card
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SpendingSummaryCard(
    totalSpent: Double,
    dailyAverage: Double,
    transactionDays: Int,
    monthOverMonth: Double?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                shadowElevation = 24f
                shape = RoundedCornerShape(28.dp)
                clip = true
            }
            .background(
                Brush.linearGradient(
                    0.0f to Color(0xFF1A237E),
                    0.5f to Color(0xFF0D47A1),
                    1.0f to Color(0xFF01579B)
                )
            )
    ) {
        // Subtle decorative radial
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.1f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 600f
                    )
                )
        )

        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "TOTAL SPENT",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 2.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "\u20B9 ",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "%,.0f".format(animatedAmount(totalSpent)),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
                    ),
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryMetric("Daily Avg", "\u20B9%,.0f".format(animatedAmount(dailyAverage)))
                SummaryMetric("Spending Days", animatedCount(transactionDays).toString())
                if (monthOverMonth != null) {
                    val isUp = monthOverMonth > 0
                    SummaryMetric(
                        label = "vs Last Month",
                        value = "${if (isUp) "+" else ""}${"%.1f".format(monthOverMonth)}%",
                        valueColor = if (isUp) Color(0xFFFF8A80) else Color(0xFFA5D6A7)
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontWeight = FontWeight.ExtraBold
        )
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Monthly Bar Chart (Canvas)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun MonthlyBarChart(data: List<MonthlySpending>) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    }

    val maxVal = data.maxOfOrNull { it.total } ?: 1.0
    val barColorStart = Color(0xFF6C63FF)
    val barColorEnd = Color(0xFF4FC3F7)
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    val paintWhite = remember {
        android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
    }

    val paintLabel = remember {
        android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val formattedMonths = remember(data) {
        val inFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val outFormat = SimpleDateFormat("MMM", Locale.getDefault())
        data.map { item ->
            try {
                outFormat.format(inFormat.parse(item.monthLabel)!!)
            } catch (_: Exception) {
                item.monthLabel.takeLast(2)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(20.dp)
        ) {
            val barCount = data.size
            if (barCount == 0) return@Canvas

            val chartWidth = size.width
            val chartHeight = size.height - 40f // leave space for labels
            val barWidth = (chartWidth / barCount) * 0.5f
            val gap = (chartWidth / barCount) * 0.5f
            val barSpacing = chartWidth / barCount

            // Grid lines
            for (i in 0..4) {
                val y = chartHeight * (1 - i / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(chartWidth, y),
                    strokeWidth = 1f
                )
            }

            data.forEachIndexed { index, item ->
                val barHeight =
                    (item.total / maxVal * chartHeight * animatedProgress.value).toFloat()
                val x = index * barSpacing + (barSpacing - barWidth) / 2

                // Bar with gradient
                val brush = Brush.verticalGradient(
                    listOf(barColorStart, barColorEnd),
                    startY = chartHeight - barHeight,
                    endY = chartHeight
                )

                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(8f, 8f)
                )

                // Amount label above bar
                if (animatedProgress.value > 0.8f) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "\u20B9%,.0f".format(item.total),
                        x + barWidth / 2,
                        chartHeight - barHeight - 12f,
                        paintWhite
                    )
                }

                // Month label
                drawContext.canvas.nativeCanvas.drawText(
                    formattedMonths[index],
                    x + barWidth / 2,
                    size.height - 4f,
                    paintLabel
                )
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Category Donut Chart (Canvas)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun CategoryDonutChart(categories: List<CategorySpending>) {
    val animatedSweep = remember { Animatable(0f) }
    LaunchedEffect(categories) {
        animatedSweep.snapTo(0f)
        animatedSweep.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
    }

    val total = categories.sumOf { it.total }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Donut
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(180.dp)
                ) {
                    val strokeWidth = 36f
                    val radius = (size.minDimension - strokeWidth) / 2
                    val topLeft = Offset(
                        (size.width - radius * 2) / 2,
                        (size.height - radius * 2) / 2
                    )
                    val arcSize = Size(radius * 2, radius * 2)

                    var startAngle = -90f
                    categories.forEachIndexed { index, cat ->
                        val sweep = (cat.total / total * 360f * animatedSweep.value).toFloat()
                        drawArc(
                            color = ChartColors[index % ChartColors.size],
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        startAngle += sweep
                    }
                }

                // Center text
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "\u20B9%,.0f".format(total),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Legend
            categories.forEachIndexed { index, cat ->
                val percentage = if (total > 0) (cat.total / total * 100) else 0.0
                val color = ChartColors[index % ChartColors.size]
                val categoryInfo = getCategoryInfo(cat.category)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        categoryInfo.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        cat.category,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "\u20B9%,.0f".format(cat.total),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${"%.1f".format(percentage)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Tag Donut Chart (Canvas)
// ═══════════════════════════════════════════════════════════════════════

private val TagChartColors = listOf(
    Color(0xFF26A69A),  // Teal
    Color(0xFFEF5350),  // Red
    Color(0xFFAB47BC),  // Purple
    Color(0xFF42A5F5),  // Blue
    Color(0xFFFFA726),  // Orange
    Color(0xFF66BB6A),  // Green
    Color(0xFFEC407A),  // Pink
    Color(0xFF5C6BC0),  // Indigo
    Color(0xFF8D6E63),  // Brown
    Color(0xFF78909C),  // Blue Grey
)

private val UntaggedColor = Color(0xFF616161) // Muted grey for "Untagged"

@Composable
private fun TagDonutChart(tags: List<TagSpending>) {
    val animatedSweep = remember { Animatable(0f) }
    LaunchedEffect(tags) {
        animatedSweep.snapTo(0f)
        animatedSweep.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
    }

    val total = tags.sumOf { it.total }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Donut
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(180.dp)
                ) {
                    val strokeWidth = 36f
                    val radius = (size.minDimension - strokeWidth) / 2
                    val topLeft = Offset(
                        (size.width - radius * 2) / 2,
                        (size.height - radius * 2) / 2
                    )
                    val arcSize = Size(radius * 2, radius * 2)

                    var startAngle = -90f
                    tags.forEachIndexed { index, tag ->
                        val sweep = (tag.total / total * 360f * animatedSweep.value).toFloat()
                        val color = if (tag.tagLabel == "Untagged") UntaggedColor
                        else TagChartColors[index % TagChartColors.size]
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        startAngle += sweep
                    }
                }

                // Center text
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "\u20B9%,.0f".format(total),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Legend
            tags.forEachIndexed { index, tag ->
                val percentage = if (total > 0) (tag.total / total * 100) else 0.0
                val color = if (tag.tagLabel == "Untagged") UntaggedColor
                else TagChartColors[index % TagChartColors.size]
                val icon = if (tag.tagLabel == "Untagged") Icons.AutoMirrored.Filled.LabelOff
                else Icons.AutoMirrored.Filled.Label

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        tag.tagLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "\u20B9%,.0f".format(tag.total),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${"%.1f".format(percentage)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Insight Cards Grid
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InsightCardsGrid(
    topDay: String?,
    dailyAvg: String,
    topCategory: String,
    spendingDays: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InsightCard(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                label = "Highest Day",
                value = topDay ?: "—",
                accentColor = Color(0xFFFF6B6B)
            )
            InsightCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CalendarMonth,
                label = "Daily Average",
                value = dailyAvg,
                accentColor = Color(0xFF6C63FF)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InsightCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Category,
                label = "Top Category",
                value = topCategory,
                accentColor = Color(0xFF00BFA6)
            )
            InsightCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Receipt,
                label = "Spending Days",
                value = spendingDays,
                accentColor = Color(0xFFFFD93D)
            )
        }
    }
}

@Composable
private fun InsightCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun EmptyChartPlaceholder(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

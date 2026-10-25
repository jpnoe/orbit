package com.orbit.wellness.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs

private const val MOOD_MIN = 0.0
private const val MOOD_MAX = 10.0

/** Comfortable starting window for individual mood logs. */
private const val INITIAL_VISIBLE_X_INDIVIDUAL = 7.0

/** Start daily-average a bit zoomed in so pan/zoom are useful across the week. */
private const val INITIAL_VISIBLE_X_DAILY = 4.0

private val MoodRangeProvider =
    object : CartesianLayerRangeProvider {
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = MOOD_MIN

        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = MOOD_MAX
    }

private data class ChartDatum(
    val x: Double,
    val y: Double,
    val label: String,
)

@Composable
fun TrendsScreen(
    viewModel: TrendsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.trends_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.mode == TrendMode.INDIVIDUAL,
                onClick = { viewModel.setMode(TrendMode.INDIVIDUAL) },
                label = { Text(stringResource(R.string.trends_mode_individual)) },
            )
            FilterChip(
                selected = uiState.mode == TrendMode.DAILY_AVERAGE,
                onClick = { viewModel.setMode(TrendMode.DAILY_AVERAGE) },
                label = { Text(stringResource(R.string.trends_mode_daily)) },
            )
        }

        Text(
            text = when (uiState.mode) {
                TrendMode.INDIVIDUAL -> stringResource(R.string.trends_subtitle_individual)
                TrendMode.DAILY_AVERAGE -> stringResource(R.string.trends_subtitle_daily)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val chartData = remember(uiState.points, uiState.mode) {
            uiState.points.mapIndexedNotNull { index, point ->
                val score = point.moodScore ?: return@mapIndexedNotNull null
                ChartDatum(
                    x = index.toDouble(),
                    y = score.toDouble(),
                    label = point.xLabel,
                )
            }
        }

        if (!uiState.hasEnoughData || chartData.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.trends_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            // Reset scroll/zoom when switching modes or when point count changes a lot.
            key(uiState.mode, chartData.size) {
                MoodTrendChart(
                    data = chartData,
                    mode = uiState.mode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                )
            }
        }
    }
}

@Composable
private fun MoodTrendChart(
    data: List<ChartDatum>,
    mode: TrendMode,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val labelsByX = remember(data) { data.associate { it.x to it.label } }
    val lineColor = MaterialTheme.colorScheme.primary
    val pointComponent = remember(lineColor) {
        ShapeComponent(fill = Fill(lineColor), shape = CircleShape)
    }

    LaunchedEffect(data) {
        if (data.isEmpty()) return@LaunchedEffect
        // Vico requires a non-empty series — never call series() with empty lists.
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = data.map { it.x },
                    y = data.map { it.y },
                )
            }
        }
    }

    val bottomAxisFormatter = remember(labelsByX) {
        object : CartesianValueFormatter {
            override fun format(
                context: CartesianMeasuringContext,
                value: Double,
                verticalAxisPosition: Axis.Position.Vertical?,
            ): CharSequence {
                val exact = labelsByX[value]
                if (exact != null) return exact
                val nearest = labelsByX.keys.minByOrNull { abs(it - value) }
                return nearest?.let { labelsByX[it] }.orEmpty()
            }
        }
    }

    val startAxisItemPlacer = VerticalAxis.ItemPlacer.step({ 1.0 })
    val scrollState = rememberVicoScrollState(
        scrollEnabled = true,
        initialScroll = Scroll.Absolute.End,
    )
    val initialVisibleX = when (mode) {
        TrendMode.DAILY_AVERAGE -> INITIAL_VISIBLE_X_DAILY
        TrendMode.INDIVIDUAL -> INITIAL_VISIBLE_X_INDIVIDUAL
    }
    val zoomState = rememberVicoZoomState(
        zoomEnabled = true,
        // Readable starting window for both modes; pinch out to see the full series.
        initialZoom = remember(initialVisibleX) {
            Zoom.max(Zoom.x(initialVisibleX), Zoom.fixed())
        },
        minZoom = Zoom.Content,
        maxZoom = remember { Zoom.max(Zoom.fixed(5f), Zoom.Content) },
    )

    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
        // Always show points so a single daily-average day still renders clearly.
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(pointComponent),
        ),
    )

    val lineLayer = rememberLineCartesianLayer(
        rangeProvider = MoodRangeProvider,
        lineProvider = LineCartesianLayer.LineProvider.series(line),
    )

    CartesianChartHost(
        chart = rememberCartesianChart(
            lineLayer,
            startAxis = VerticalAxis.rememberStart(itemPlacer = startAxisItemPlacer),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisFormatter),
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        zoomState = zoomState,
        modifier = modifier,
    )
}

package com.example.readio.ui.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readio.domain.model.Paragraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

private val ITEM_HEIGHT = 84.dp

@Composable
fun ParagraphWheel(
    paragraphs: List<Paragraph>,
    currentIndex: Int,
    onCenterChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (paragraphs.isEmpty()) return

    val view = LocalView.current

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = maxOf(0, currentIndex - 3)
    )
    val snapBehavior = rememberSnapFlingBehavior(listState)

    LaunchedEffect(currentIndex) {
        if (!listState.isScrollInProgress) {
            listState.scrollToItem(currentIndex)
        }
    }

    val liveCenterIndex by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2f - center) }
                ?.index ?: currentIndex
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { liveCenterIndex }
            .distinctUntilChanged()
            .drop(1) // skip initial emission; only fire on actual user scroll
            .collect {
                val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    HapticFeedbackConstants.SEGMENT_TICK
                else
                    HapticFeedbackConstants.CLOCK_TICK
                view.performHapticFeedback(constant)
            }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) onCenterChanged(liveCenterIndex)
    }

    val surfaceColor = MaterialTheme.colorScheme.surface

    BoxWithConstraints(modifier = modifier) {
        val verticalPadding = (maxHeight - ITEM_HEIGHT) / 2

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                flingBehavior = snapBehavior,
                contentPadding = PaddingValues(vertical = verticalPadding),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(paragraphs, key = { _, p -> p.id }) { index, paragraph ->

                    val isCenter = index == currentIndex

                    Box(
                        modifier = Modifier
                            .height(ITEM_HEIGHT)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val info = listState.layoutInfo
                                val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                                val dist = if (itemInfo != null) {
                                    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                    val itemCenter = itemInfo.offset + itemInfo.size / 2f
                                    abs(itemCenter - viewportCenter) / itemInfo.size
                                } else 1f
                                val scale = (1.08f - dist * 0.19f).coerceIn(0.70f, 1.08f)
                                scaleX = scale
                                scaleY = scale
                                alpha = (1f - dist * 0.44f).coerceAtLeast(0f)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = paragraph.text,
                            style = if (isCenter) MaterialTheme.typography.bodyLarge
                                    else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCenter) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = if (isCenter) 4 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to surfaceColor,
                            0.28f to Color.Transparent,
                            0.72f to Color.Transparent,
                            1.00f to surfaceColor
                        )
                    )
            )
        }
    }
}

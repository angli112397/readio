package com.example.readio.ui.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.readio.domain.model.Chunk
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

@Composable
fun ChunkWheel(
    chunks: List<Chunk>,
    currentIndex: Int,
    onCenterChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    lineHeightMultiplier: Float = 1.5f,
    onChunkLongPress: ((chunkText: String) -> Unit)? = null
) {
    if (chunks.isEmpty()) return

    val view = LocalView.current

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = maxOf(0, currentIndex - 1)
    )
    val snapBehavior = rememberSnapFlingBehavior(listState)

    LaunchedEffect(currentIndex) {
        if (!listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex)
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
            .drop(1)
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

    // Surface color drives both the gradient overlay and the background.
    val surfaceColor = MaterialTheme.colorScheme.surface

    BoxWithConstraints(modifier = modifier) {
        val itemHeight = maxHeight / 3
        val verticalPadding = (maxHeight - itemHeight) / 2

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                flingBehavior = snapBehavior,
                contentPadding = PaddingValues(vertical = verticalPadding),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(chunks, key = { _, c -> c.id }) { index, chunk ->

                    val isCenter = index == liveCenterIndex
                    val effectiveFontSize = if (isCenter) fontSize else fontSize * 0.85f
                    val lineHeight = effectiveFontSize * lineHeightMultiplier

                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val info = listState.layoutInfo
                                val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                                val dist = if (itemInfo != null) {
                                    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                    val itemCenter = itemInfo.offset + itemInfo.size / 2f
                                    abs(itemCenter - viewportCenter) / itemInfo.size
                                } else 1f
                                val scale = (1.05f - dist * 0.15f).coerceIn(0.75f, 1.05f)
                                scaleX = scale
                                scaleY = scale
                                alpha = (1f - dist * 0.55f).coerceAtLeast(0f)
                            }
                            .then(
                                if (onChunkLongPress != null) Modifier.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClick = { onChunkLongPress(chunk.text) }
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chunk.text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = effectiveFontSize,
                                lineHeight = lineHeight
                            ),
                            fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCenter) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = if (isCenter) 10 else 3,
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
                            0.20f to Color.Transparent,
                            0.80f to Color.Transparent,
                            1.00f to surfaceColor
                        )
                    )
            )
        }
    }
}

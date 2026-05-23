package com.example.readio.ui.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.readio.domain.model.Chunk
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

private fun centerTextAlign(text: String): TextAlign =
    if (text.count { it in 'A'..'Z' || it in 'a'..'z' } > text.length / 3)
        TextAlign.Start   // English: left-align, no justification rivers
    else
        TextAlign.Justify // CJK: full justification works cleanly with monospace glyphs

@Composable
fun ChunkWheel(
    chunks: List<Chunk>,
    currentIndex: Int,
    onCenterChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    lineHeightMultiplier: Float = 1.5f,
    onTranslateTap: (() -> Unit)? = null,
    /** Called on the first frame of a user-initiated scroll gesture (not audio-driven scroll). */
    onScrollStarted: () -> Unit = {},
    /** False while TTS is synthesizing — locks the wheel to prevent state confusion. */
    isScrollEnabled: Boolean = true,
) {
    if (chunks.isEmpty()) return

    val view = LocalView.current

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = maxOf(0, currentIndex - 1)
    )
    val snapBehavior = rememberSnapFlingBehavior(listState)

    // Intercept user-initiated scroll events before they reach the LazyColumn.
    // NestedScrollSource.UserInput fires only on touch gestures — NOT on programmatic
    // animateScrollToItem() calls (which use SideEffect source). This lets audio auto-advance
    // scroll the wheel without triggering onScrollStarted() and stopping playback.
    val scrollStartConnection = remember(onScrollStarted) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) onScrollStarted()
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.y != 0f || available.x != 0f) onScrollStarted()
                return Velocity.Zero
            }
        }
    }

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

    // Notify caller only when scroll settles (false → true transition is ignored).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling -> if (!isScrolling) onCenterChanged(liveCenterIndex) }
    }

    val surfaceColor = MaterialTheme.colorScheme.surface

    // Precompute item distances once per frame (O(n)) rather than per item (O(n²)).
    val distanceMap by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.associate { item ->
                item.index to (abs(item.offset + item.size / 2f - viewportCenter) / item.size)
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val itemHeight = maxHeight / 3
        val verticalPadding = (maxHeight - itemHeight) / 2

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                flingBehavior = snapBehavior,
                userScrollEnabled = isScrollEnabled,
                contentPadding = PaddingValues(vertical = verticalPadding),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollStartConnection)
            ) {
                itemsIndexed(chunks, key = { _, c -> c.id }) { index, chunk ->

                    val isCenter = index == liveCenterIndex
                    val effectiveFontSize = if (isCenter) fontSize else fontSize * 0.85f
                    val lineHeight = effectiveFontSize * lineHeightMultiplier
                    val chunkTextAlign = if (isCenter) remember(chunk.text) { centerTextAlign(chunk.text) }
                                        else TextAlign.Center

                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .fillMaxWidth()
                            .then(
                                if (isCenter && onTranslateTap != null)
                                    Modifier.clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onTranslateTap
                                    )
                                else Modifier
                            )
                            .graphicsLayer {
                                val dist = distanceMap[index] ?: 1f
                                val scale = (1.05f - dist * 0.15f).coerceIn(0.75f, 1.05f)
                                scaleX = scale
                                scaleY = scale
                                alpha = (1f - dist * 0.55f).coerceAtLeast(0f)
                            },
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
                            textAlign = chunkTextAlign,
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

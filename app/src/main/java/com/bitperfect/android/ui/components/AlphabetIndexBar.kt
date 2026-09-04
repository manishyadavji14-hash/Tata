package com.bitperfect.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.library.AlphabetIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A-Z strip down the right edge of a name-sorted list, for jumping by letter.
 *
 * Shown only while it is useful: it fades in when the list is scrolling or the
 * strip is being dragged, and fades out [HIDE_DELAY_MS] after both stop. A
 * permanent strip would sit on top of the rows it is meant to help reach.
 *
 * Only the letters the list actually contains are offered, and if there are more
 * than fit they are thinned out — see [AlphabetIndex]. Every visible label is
 * therefore a real target, which matters because each one is only a few dp tall.
 */
@Composable
fun AlphabetIndexBar(
    entries: List<AlphabetIndex.Entry>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return

    val scope = rememberCoroutineScope()
    var pressedLetter by remember { mutableStateOf<Char?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    val isScrolling = listState.isScrollInProgress
    val isDragging = pressedLetter != null

    // One effect for both: any activity shows the strip, and the hide is simply
    // what happens if no activity interrupts the delay. Restarting the effect
    // cancels the pending hide, so a scroll that resumes never flickers.
    LaunchedEffect(isScrolling, isDragging) {
        if (isScrolling || isDragging) {
            isVisible = true
        } else {
            delay(HIDE_DELAY_MS)
            isVisible = false
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp)
        ) {
            val density = LocalDensity.current
            val maxLabels = remember(maxHeight, density) {
                with(density) { (maxHeight.toPx() / LABEL_HEIGHT_PX).toInt() }
            }
            val labels = remember(entries, maxLabels) { AlphabetIndex.fit(entries, maxLabels) }

            if (labels.isEmpty()) return@BoxWithConstraints

            /** Scroll to whichever label a touch at [y] within [height] picks. */
            fun jumpTo(y: Float, height: Float) {
                val index = AlphabetIndex.labelAt(
                    fraction = if (height <= 0f) 0f else y / height,
                    count = labels.size
                )
                if (index !in labels.indices) return

                val label = labels[index]
                pressedLetter = label.letter
                scope.launch { listState.scrollToItem(label.itemIndex) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(STRIP_WIDTH)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(percent = 50)
                    )
                    .padding(vertical = 6.dp)
                    // Tap and drag are separate detectors on purpose: a tap must
                    // work on its own, and detectVerticalDragGestures does not
                    // report one because it waits for movement before it fires.
                    .pointerInput(labels) {
                        detectTapGestures { offset -> jumpTo(offset.y, size.height.toFloat()) }
                    }
                    .pointerInput(labels) {
                        detectVerticalDragGestures(
                            onDragStart = { offset -> jumpTo(offset.y, size.height.toFloat()) },
                            onDragEnd = { pressedLetter = null },
                            onDragCancel = { pressedLetter = null }
                        ) { change, _ ->
                            jumpTo(change.position.y, size.height.toFloat())
                        }
                    },
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                labels.forEach { label ->
                    val isPressed = label.letter == pressedLetter
                    Text(
                        text = label.letter.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        color = if (isPressed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // The labels are far too small to read under a fingertip, so the
            // current one is echoed clear of it.
            pressedLetter?.let { letter ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

/** Requested: the strip hides two seconds after scrolling stops. */
private const val HIDE_DELAY_MS = 2_000L
private const val FADE_MS = 200

/** Tap target per label. Below roughly this, labels cannot be hit reliably. */
private const val LABEL_HEIGHT_PX = 44f

private val STRIP_WIDTH = 24.dp

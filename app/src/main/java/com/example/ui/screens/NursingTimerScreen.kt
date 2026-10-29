package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BreastSide
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.LiveTimerState
import com.example.ui.components.BreastSideControls
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.SurfaceContainerLow

/** Breastfeeding: a button per side; every tap is saved and synced, so there's nothing to confirm. */
@Composable
fun NursingTimerScreen(
    liveTimerState: LiveTimerState,
    recentFeedings: List<FeedingEvent>,
    onBack: () -> Unit,
    onTapSide: (BreastSide) -> Unit,
    onEndFeed: () -> Unit,
    lastBreastfeed: FeedingEvent? = null,
    previousFeedMillis: Long? = null,
    onSetSideSeconds: (BreastSide, Int) -> Unit = { _, _ -> },
    onSetFeedStart: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Earlier feeds today, not the one being timed
    val earlier = recentFeedings
        .filter { it.type == FeedType.NURSING && it.id != liveTimerState.feedingLocalId }
        .take(3)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.clip(CircleShape).background(SurfaceContainerLow)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.item_breastfeeding), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        BreastSideControls(
            state = liveTimerState,
            onTapSide = onTapSide,
            onEndFeed = onEndFeed,
            buttonHeight = 96.dp,
            lastFeed = lastBreastfeed,
            onSetSideSeconds = onSetSideSeconds,
            onSetStart = onSetFeedStart,
            previousFeedMillis = previousFeedMillis
        )

        if (earlier.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.earlier_today), style = MaterialTheme.typography.titleSmall, color = OutlineColor)
                earlier.forEach { feed ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(feed.timeFormatted, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.feed_left_right_minutes, feed.leftDurationMinutes, feed.rightDurationMinutes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.size(8.dp))
    }
}

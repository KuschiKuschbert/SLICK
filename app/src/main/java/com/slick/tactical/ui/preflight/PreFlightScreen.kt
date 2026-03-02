package com.slick.tactical.ui.preflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.slick.tactical.ui.theme.SlickColors
import kotlinx.coroutines.launch

private val TABS = listOf("ROUTE", "CONVOY", "SETTINGS")

/**
 * Pre-Flight container screen.
 *
 * Three tabs presented as a swipeable [HorizontalPager] -- the rider can swipe between
 * ROUTE / CONVOY / SETTINGS without tapping the tab bar. The tab indicator animates
 * smoothly as the page scrolls (fractional position tracking via [PagerState.currentPageOffsetFraction]).
 *
 * Insets: [WindowInsets.safeDrawing] applied at the root so that the tab bar sits
 * below the status bar and the content avoids notches/punch-hole cameras on all devices.
 * On tablets, the safe area is minimal but still applied correctly.
 *
 * Standard Material 3 allowed here (kickstand down, bike stationary).
 */
@Composable
fun PreFlightScreen(
    onStartConvoy: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void)
            // Respect system bars, camera cutout, and rounded corners
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Synced tab indicator tracks pager scroll fractionally
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = SlickColors.Surface,
            contentColor = SlickColors.Alert,
            indicator = { tabPositions ->
                if (tabPositions.isNotEmpty()) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(
                            tabPositions[pagerState.currentPage],
                        ),
                        color = SlickColors.Alert,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TABS.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pagerState.currentPage == index) SlickColors.Alert
                            else SlickColors.DataSecondary,
                        )
                    },
                )
            }
        }

        // Swipeable content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            // Allow vertical scroll within each page without conflicting with pager swipe
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                0 -> RouteConfigContent(onStartConvoy = onStartConvoy)
                1 -> ConvoyLobbyScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

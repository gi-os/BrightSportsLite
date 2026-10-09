package com.gios.brightsportslite.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharedFlow

/** The tool's theme, following LightOS's light/dark setting. */
@Composable
fun Themed(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/** A back arrow for the top bar's left slot. */
fun backButton(onBack: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back")

/**
 * The standard page: a top bar, a body that scrolls (with the wheel too, when [wheel] is
 * given), and an optional bottom bar.
 */
@Composable
fun Page(
    center: LightTopBarCenter?,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    bottom: List<LightBarButton?>? = null,
    wheel: SharedFlow<Int>? = null,
    scrollState: ScrollState = rememberScrollState(),
    body: @Composable ColumnScope.() -> Unit,
) {
    Themed {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = left,
                center = center,
                rightButton = right,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            if (wheel != null) WheelScroll(wheel, scrollState)
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                scrollState = scrollState,
            ) {
                body()
                Box(modifier = Modifier.height(1f.gridUnitsAsDp()))
            }
            if (bottom != null) LightBottomBar(bottom)
        }
    }
}

/** A tappable row of copy with an optional detail line underneath. */
@Composable
fun ListRow(
    title: String,
    detail: String? = null,
    lighten: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .padding(vertical = 0.5f.gridUnitsAsDp())
    Column(modifier = if (onClick != null) base.lightClickable(onClick = onClick) else base) {
        LightText(text = title, variant = LightTextVariant.Copy, lighten = lighten)
        if (detail != null) {
            LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}

/** A row with an on/off mark at the end: a team you follow, or not. */
@Composable
fun ToggleRow(
    title: String,
    on: Boolean,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 0.5f.gridUnitsAsDp())) {
            LightText(text = title, variant = LightTextVariant.Copy)
            if (detail != null) {
                LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
            }
        }
        LightIcon(
            icon = if (on) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            size = 1.25f,
            contentDescription = if (on) "Following" else "Not following",
        )
    }
}

/** A small heading between groups of rows. */
@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

/** A line of copy for an empty or failed page. */
@Composable
fun EmptyLine(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
    )
}

/** One line of detail text that never wraps. */
@Composable
fun DetailLine(text: String, lighten: Boolean = true) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = lighten,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A hairline rule. */
@Composable
fun Rule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.25f.gridUnitsAsDp())
            .height(1f.gridUnitsAsDp() / 12)
            .background(LightThemeTokens.colors.contentSecondary),
    )
}

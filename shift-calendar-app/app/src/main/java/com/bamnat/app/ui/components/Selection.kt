package com.bamnat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * [VIS-03] One way to show "this one is chosen" across the app: the primary family (the blue of the app's
 * buttons), not Material's default mint secondaryContainer, which the app also uses for tip boxes, so a chosen
 * chip and a tip no longer look alike (notes/1.22-285/E.md 5장 1번). The scheme's secondaryContainer stays as
 * it is for those boxes; only the choosing controls take these colours.
 */
object Selection {
    /** Behind the chosen chip, tab or segment. */
    val container: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer

    /** Letters and icons on [container]. */
    val onContainer: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryContainer

    @Composable
    fun chipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = container,
        selectedLabelColor = onContainer,
        selectedLeadingIconColor = onContainer,
        selectedTrailingIconColor = onContainer,
    )

    @Composable
    fun segmentedColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
        activeContainerColor = container,
        activeContentColor = onContainer,
    )

    @Composable
    fun navBarItemColors(): NavigationBarItemColors = NavigationBarItemDefaults.colors(
        indicatorColor = container,
        selectedIconColor = onContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
    )

    @Composable
    fun navRailItemColors(): NavigationRailItemColors = NavigationRailItemDefaults.colors(
        indicatorColor = container,
        selectedIconColor = onContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * [VIS-03] Material's FilterChip with the app's [Selection] colours. Same name and parameters, so a screen moves
 * over by changing its import (`SelectionRulesTest` keeps new screens from importing Material's own).
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = Selection.chipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null,
) = androidx.compose.material3.FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    modifier = modifier,
    enabled = enabled,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    interactionSource = interactionSource,
)

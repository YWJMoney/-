package com.bamnat.app.rules

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [VIS-03] "Chosen" looks the same everywhere: chips, segments and tabs take the app's Selection colours
 * (primary family), so no screen reaches for Material's own FilterChip or SegmentedButton, whose chosen
 * colour is the mint the app keeps for tip boxes. Selection.kt alone wraps them.
 */
class SelectionRulesTest {
    private val ui = File("src/main/java/com/bamnat/app")
    private val sources by lazy { ui.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Selection.kt" }.toList() }

    @Test fun noScreenUsesMaterialsOwnChoosingControls() {
        val banned = listOf(
            "import androidx.compose.material3.FilterChip\n", "androidx.compose.material3.FilterChip(",
            "import androidx.compose.material3.SegmentedButton\n", "androidx.compose.material3.SegmentedButton(",
        )
        val found = sources.flatMap { f -> val t = f.readText(); banned.filter { it in t }.map { "${f.name}: ${it.trim()}" } }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun theTabsUseTheSelectionColours() {
        val root = File(ui, "ui/AppRoot.kt").readText()
        assertEquals(1, Regex("Selection\\.navBarItemColors\\(\\)").findAll(root).count())
        assertEquals(1, Regex("Selection\\.navRailItemColors\\(\\)").findAll(root).count())
    }
}

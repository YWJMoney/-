package com.bamnat.app.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * [PERF-01] The hand-written Baseline Profile (app/src/main/baseline-prof.txt) stays true to the code: every
 * line is a rule Android reads, and every class or package it names is still there after a move or rename.
 * A rule that matches nothing is silently ignored at install, so without this the profile would rot unseen.
 */
class BaselineProfileRulesTest {
    private val rules = File("src/main/baseline-prof.txt").readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Every class on the unit test classpath (the app's and core's compiled code among them), as "Lcom/x/Y;". */
    private val classes: List<String> by lazy {
        System.getProperty("java.class.path").split(File.pathSeparator).map(::File).flatMap { entry ->
            when {
                entry.isDirectory -> entry.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                    .map { it.relativeTo(entry).invariantSeparatorsPath }.toList()
                entry.isFile && entry.name.endsWith(".jar") -> runCatching {
                    ZipFile(entry).use { z -> z.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList() }
                }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }.filter { it.startsWith("com/bamnat/") }.map { "L" + it.removeSuffix(".class") + ";" }
    }

    @Test fun everyLineIsARule() {
        val bad = rules.filterNot { RULE.matches(it) }
        assertEquals("lines Android would not read", emptyList<String>(), bad)
        assertTrue("the profile covers the start", rules.size >= 20)
    }

    @Test fun everyRuleMatchesTheCode() {
        assertTrue("the compiled app is on the classpath", classes.any { it == "Lcom/bamnat/app/MainActivity;" })
        val unmatched = rules.filter { rule ->
            val pattern = descriptorPattern(DESCRIPTOR.find(rule)!!.groupValues[1])
            classes.none { pattern.matches(it) }
        }
        assertEquals("rules that name no class", emptyList<String>(), unmatched)
    }

    @Test fun theStartIsCovered() {
        // The classes a cold start cannot avoid, up to the calendar's month.
        val start = listOf("Lcom/bamnat/app/BamnatApp;", "Lcom/bamnat/app/MainActivity;", "Lcom/bamnat/app/AppContainer;",
            "Lcom/bamnat/app/ui/AppRootKt;", "Lcom/bamnat/app/data/ScheduleRepository;", "Lcom/bamnat/app/data/db/AppDatabase;")
        val patterns = rules.map { descriptorPattern(DESCRIPTOR.find(it)!!.groupValues[1]) }
        for (c in start) assertTrue("$c is in the profile", patterns.any { it.matches(c) })
        assertTrue("the calendar screen is in the profile", classes.filter { it.startsWith("Lcom/bamnat/app/ui/calendar/") }
            .all { c -> patterns.any { it.matches(c) } })
    }

    private companion object {
        /** Flags H, S, P, then a class descriptor, then for a method rule its name and signature. */
        val RULE = Regex("""^[HSP]*L[\w/$*]+;?(->[\w<>$*]+\([^)\s]*\)\S+)?$""")
        val DESCRIPTOR = Regex("""^[HSP]*(L[\w/$*]+;?)""")

        /** As the profile tools read a descriptor: ** across packages, * within one name. */
        fun descriptorPattern(descriptor: String): Regex {
            val d = if (descriptor.endsWith(";") || descriptor.endsWith("*")) descriptor else "$descriptor;"
            val out = StringBuilder()
            var i = 0
            while (i < d.length) {
                when {
                    d.startsWith("**", i) -> { out.append(".*"); i += 2 }
                    d[i] == '*' -> { out.append("[^/]*"); i++ }
                    else -> { out.append(Regex.escape(d[i].toString())); i++ }
                }
            }
            return Regex(out.toString())
        }
    }
}

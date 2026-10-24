package com.bamnat.app.ui

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [PERF-05] 364 (검토 B3): a StateFlow worked out from other StateFlows. [value] is read from them each time, so it is
 * never a step behind, and nothing runs while nobody collects it, unlike `stateIn(Eagerly)`, which kept a coroutine
 * per value going for the view model's whole life only so that `.value` stayed fresh. [changes] must start with the
 * current value (a `combine` or `map` of StateFlows does).
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class DerivedStateFlow<T>(private val read: () -> T, private val changes: Flow<T>) : StateFlow<T> {
    override val value: T get() = read()
    override val replayCache: List<T> get() = listOf(read())
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** [PERF-05] This StateFlow's value put through [f], kept as a StateFlow. */
fun <T, R> StateFlow<T>.derive(f: (T) -> R): StateFlow<R> =
    DerivedStateFlow({ f(value) }, kotlinx.coroutines.flow.flow { this@derive.collect { emit(f(it)) } })

package composablearchitecture.sandbox

import arrow.core.left
import arrow.core.right
import arrow.optics.Prism
import arrow.optics.optics
import composablearchitecture.Reducer
import composablearchitecture.Store
import composablearchitecture.cancel
import composablearchitecture.cancellable
import composablearchitecture.test.TestStore
import composablearchitecture.withEffect
import composablearchitecture.withNoEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher

@optics
data class NestedState(var text: String = "") {
    companion object
}

@optics
data class CounterState(val counter: Int = 0, val nestedState: NestedState = NestedState()) {
    companion object
}

sealed class CounterAction : Comparable<CounterAction> {
    object Increment : CounterAction()
    object Noop : CounterAction()
    object Cancel : CounterAction()

    companion object {
        val prism: Prism<AppAction, CounterAction> = Prism(
            getOrModify = { appAction ->
                when (appAction) {
                    is AppAction.Counter -> appAction.action.right()
                    else -> appAction.left()
                }
            },
            reverseGet = { counterAction ->
                AppAction.Counter(
                    counterAction
                )
            }
        )
    }

    override fun compareTo(other: CounterAction): Int = this.compareTo(other)
}

class CounterEnvironment

val counterReducer =
    Reducer<CounterState, CounterAction, CounterEnvironment> { state, action, environment ->
        when (action) {
            CounterAction.Increment -> {
                state.copy(counter = state.counter + 1)
                    .withEffect<CounterState, CounterAction> {
                        delay(2000L)
                        emit(CounterAction.Noop)
                    }
                    .cancellable("1", cancelInFlight = true)
            }
            CounterAction.Noop -> {
                println("Noop")
                state.withNoEffect()
            }
            CounterAction.Cancel -> state.cancel("1")
        }
    }

@optics
data class AppState(val counterState: CounterState = CounterState()) {
    companion object
}

sealed class AppAction : Comparable<AppAction> {
    object Reset : AppAction()
    data class Counter(val action: CounterAction) : AppAction()

    override fun compareTo(other: AppAction): Int = this.compareTo(other)
}

class AppEnvironment {
    var counterEnvironment = CounterEnvironment()
}

val appReducer =
    Reducer.combine<AppState, AppAction, AppEnvironment>(
        Reducer { state, action, _ ->
            when (action) {
                AppAction.Reset ->
                    AppState.counterState.counter
                        .set(state, 0)
                        .withNoEffect()
                else -> state.withNoEffect()
            }
        },
        counterReducer.pullback(
            AppState.counterState,
            CounterAction.prism
        ) { environment -> environment.counterEnvironment }
    )

fun main() {
    runBlocking {
        val testDispatcher = TestCoroutineDispatcher()

        val testStore = TestStore(
            AppState(),
            appReducer,
            AppEnvironment(),
            testDispatcher
        )

        testStore.assert {
            send(AppAction.Counter(CounterAction.Increment)) {
                AppState(CounterState(counter = 1))
            }
            send(AppAction.Counter(CounterAction.Increment)) {
                AppState(CounterState(counter = 2))
            }
            send(AppAction.Reset) {
                AppState(CounterState(counter = 0))
            }
            doBlock {
                testDispatcher.advanceTimeBy(2000L)
            }
            receive(
                AppAction.Counter(CounterAction.Noop)
            ) {
                AppState(CounterState(counter = 0))
            }
        }

        println("✅")

        val store = Store(
            initialState = AppState(),
            reducer = appReducer,
            environment = AppEnvironment(),
            mainDispatcher = testDispatcher
        )

        val scopedStore = store.scope(
            toLocalState = AppState.counterState,
            fromLocalAction = CounterAction.prism,
            coroutineScope = this
        )

        val job1 = launch(testDispatcher) {
            store.states.collect {
                println("[${Thread.currentThread().name}] [global store] state=$it")
            }
        }

        val job2 = launch(testDispatcher) {
            scopedStore.states.collect {
                println("[${Thread.currentThread().name}] [scoped store] state=$it")
            }
        }

        store.send(AppAction.Counter(CounterAction.Increment))
        store.send(AppAction.Counter(CounterAction.Increment))
        store.send(AppAction.Counter(CounterAction.Increment))

        testDispatcher.advanceTimeBy(2000L)

        job1.cancel()
        job2.cancel()
        scopedStore.cancel()

        println("✅")
    }
}

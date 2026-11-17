package io.kopipes.core.flavors

import io.kopipes.core.Event
import io.kopipes.core.PipelineBuilder
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.filterEvents
import io.kopipes.core.sinks.EventPublisher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.onEach

/**
 * Pipeline flavor for an egress gateway: acts on each event (calls an outside system) and publishes nothing.
 *
 * An egress gateway never says "it worked": it acts, then hears back through its ingress partner. An action that
 * throws makes a fault, or, with stream retry on and a retryable error, has the stream deliver the batch again.
 *
 * The one exception: when the outside system refuses a request outright (a `4xx`), no answer will ever come back, so
 * the gateway answers for it. An action built with `actionEmitting` returns those events, and they are published.
 */
class ActionPipeline(
    id: String,
    private val eventFilter: EventFilter,
    private val onContentType: (UnitOfWork) -> Boolean,
    private val action: suspend (UnitOfWork) -> List<Event>,
    private val eventPublisher: EventPublisher? = null,
) : Pipeline(id) {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun connect(fm: FaultManager, fromFlow: Flow<UnitOfWork>): Flow<UnitOfWork> {
        logger.info { "ActionPipeline.connect: id=$id" }
        with(fm) {
            val acted = fromFlow
                .filterEvents(fm, eventFilter)
                .onEach { printStartPipeline(it) }
                .filterNotFaulty { onContentType(it) }
                .flatMapConcat { uow ->
                    val emitted = faulty(uow) { action(it) } ?: return@flatMapConcat emptyFlow()
                    if (eventPublisher == null) listOf(uow).asFlow() else emitted.map { uow.copy(event = it) }.asFlow()
                }
            return (eventPublisher?.publish(acted) ?: acted).onEach { printEndPipeline(it) }
        }
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder : PipelineBuilder<ActionPipeline, Builder>() {
        private var eventFilter: EventFilter = EventFilter.Any
        private var onContentType: (UnitOfWork) -> Boolean = { true }
        private var action: (suspend (UnitOfWork) -> List<Event>)? = null
        private var eventPublisher: EventPublisher? = null

        fun eventFilter(eventFilter: EventFilter) = apply { this.eventFilter = eventFilter }
        fun onContentType(onContentType: (UnitOfWork) -> Boolean) = apply { this.onContentType = onContentType }

        /** Acts, and publishes nothing. */
        fun action(action: suspend (UnitOfWork) -> Unit) = apply { this.action = { uow -> action(uow); emptyList() } }

        /** Acts, and publishes the events it returns, usually none. Needs an [eventPublisher]. */
        fun actionEmitting(action: suspend (UnitOfWork) -> List<Event>) = apply { this.action = action }
        fun eventPublisher(eventPublisher: EventPublisher) = apply { this.eventPublisher = eventPublisher }

        override fun build() = ActionPipeline(
            id = id ?: throw IllegalArgumentException("id is required"),
            eventFilter = eventFilter,
            onContentType = onContentType,
            action = action ?: throw IllegalArgumentException("action is required"),
            eventPublisher = eventPublisher,
        )
    }
}

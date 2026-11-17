package io.kopipes.core.flavors

import io.kopipes.core.PipelineBuilder
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.filterEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Pipeline flavor for an egress gateway: acts on each event (calls an outside system) and publishes nothing.
 *
 * An egress gateway never says "it worked": it acts, then hears back through its ingress partner. An [action] that
 * throws makes a fault, or, with stream retry on and a retryable error, has the stream deliver the batch again.
 */
class ActionPipeline(
    id: String,
    private val eventFilter: EventFilter,
    private val onContentType: (UnitOfWork) -> Boolean,
    private val action: suspend (UnitOfWork) -> Unit,
) : Pipeline(id) {

    override fun connect(fm: FaultManager, fromFlow: Flow<UnitOfWork>): Flow<UnitOfWork> {
        logger.info { "ActionPipeline.connect: id=$id" }
        with(fm) {
            return fromFlow
                .filterEvents(fm, eventFilter)
                .onEach { printStartPipeline(it) }
                .filterNotFaulty { onContentType(it) }
                .mapNotFaulty { uow -> action(uow); uow }
                .onEach { printEndPipeline(it) }
        }
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder : PipelineBuilder<ActionPipeline, Builder>() {
        private var eventFilter: EventFilter = EventFilter.Any
        private var onContentType: (UnitOfWork) -> Boolean = { true }
        private var action: (suspend (UnitOfWork) -> Unit)? = null

        fun eventFilter(eventFilter: EventFilter) = apply { this.eventFilter = eventFilter }
        fun onContentType(onContentType: (UnitOfWork) -> Boolean) = apply { this.onContentType = onContentType }
        fun action(action: suspend (UnitOfWork) -> Unit) = apply { this.action = action }

        override fun build() = ActionPipeline(
            id = id ?: throw IllegalArgumentException("id is required"),
            eventFilter = eventFilter,
            onContentType = onContentType,
            action = action ?: throw IllegalArgumentException("action is required"),
        )
    }
}

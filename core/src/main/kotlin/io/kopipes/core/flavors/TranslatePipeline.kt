package io.kopipes.core.flavors

import io.kopipes.core.Event
import io.kopipes.core.PipelineBuilder
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.sinks.EventPublisher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.onEach

/**
 * Pipeline flavor for an ingress gateway: turns what an outside system said (a changed row, a webhook, a line of a
 * file) into internal events, and publishes them. Unlike the other flavors, the unit of work carries the outside
 * system's record ([UnitOfWork.record]), not an event yet.
 *
 * [toEvents] returns no events for a record that isn't ours. If it throws, the record becomes a fault; with stream
 * retry on and a retryable error (say, the answer arrived before the request it answers), the stream delivers the
 * batch again instead.
 */
class TranslatePipeline(
    id: String,
    private val onContentType: (UnitOfWork) -> Boolean,
    private val toEvents: suspend (UnitOfWork) -> List<Event>,
    private val eventPublisher: EventPublisher,
) : Pipeline(id) {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun connect(fm: FaultManager, fromFlow: Flow<UnitOfWork>): Flow<UnitOfWork> {
        logger.info { "TranslatePipeline.connect: id=$id" }
        with(fm) {
            val translated = fromFlow
                .filterNotFaulty { onContentType(it) }
                .flatMapConcat { uow -> faulty(uow) { toEvents(it) }?.map { uow.copy(event = it) }?.asFlow() ?: emptyFlow() }
                .onEach { printStartPipeline(it) }
            return eventPublisher.publish(translated).onEach { printEndPipeline(it) }
        }
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder : PipelineBuilder<TranslatePipeline, Builder>() {
        private var onContentType: (UnitOfWork) -> Boolean = { true }
        private var toEvents: (suspend (UnitOfWork) -> List<Event>)? = null
        private var eventPublisher: EventPublisher? = null

        fun onContentType(onContentType: (UnitOfWork) -> Boolean) = apply { this.onContentType = onContentType }
        fun toEvents(toEvents: suspend (UnitOfWork) -> List<Event>) = apply { this.toEvents = toEvents }
        fun eventPublisher(eventPublisher: EventPublisher) = apply { this.eventPublisher = eventPublisher }

        override fun build() = TranslatePipeline(
            id = id ?: throw IllegalArgumentException("id is required"),
            onContentType = onContentType,
            toEvents = toEvents ?: throw IllegalArgumentException("toEvents is required"),
            eventPublisher = eventPublisher ?: throw IllegalArgumentException("eventPublisher is required"),
        )
    }
}

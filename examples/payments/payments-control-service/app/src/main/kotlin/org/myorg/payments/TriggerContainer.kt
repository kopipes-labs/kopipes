package org.myorg.payments

import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.from.DynamodbAdapter
import io.kopipes.aws.sinks.DynamoDbEventsMicrostore
import io.kopipes.core.Event
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.UnitOfWork
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.CorrelatePipeline
import io.kopipes.core.flavors.EvaluatePipeline
import io.kopipes.core.sinks.EventPublisher
import io.kopipes.core.sinks.EventsMicrostore

/**
 * The trigger's wiring: the events table's stream → correlate → the rules → the bus.
 *
 * 1. **Correlate.** Each collected event is saved again under its payment: `pk = <payment id>`, `sk = <event id>`.
 *    That item's insert is what the rules react to, and querying `pk` gives everything heard for the payment.
 * 2. **Deadlines.** The event that starts a step also starts its clock: a correlation item under
 *    `<payment id>.<step>-deadline` that expires at the deadline. When TTL deletes it, that step's expiry rule runs.
 * 3. **Rules.** One evaluate pipeline per rule (see Rules.kt), reacting to correlation inserts (`onCorrelated`), so
 *    each fires once per event heard. Each queries the payment's events, checks for a
 *    conflict first, and emits at most one event per payment, with the id `<payment id>.<rule>`: a rule that fires
 *    again emits the same event, and consumers drop the repeat.
 */
class TriggerContainer(
    val context: AwsKopipesContext,
    val eventsMicrostore: EventsMicrostore = DynamoDbEventsMicrostore(context),
    val eventPublisher: EventPublisher = context.eventPublisher,
    val config: ControlServiceConfig = context.envConfig as? ControlServiceConfig ?: ControlServiceConfig(),
) {
    companion object {
        const val CORRELATE = "correlate"
        const val START = "start"
        const val NEXT_STEP = "next-step"
        const val NOTHING_TO_UNDO = "nothing-to-undo"
        const val DONE = "done"
        const val COMPENSATE = "compensate"
        const val UNDONE = "undone"
        const val REFUND_FAILED = "refund-failed"
        const val CONFLICT = "conflict"

        fun deadline(step: String) = "$step-deadline"
        fun expired(step: String) = "expired-$step"

        /** The event that starts each step's clock: the one its entry is emitted for. */
        val STARTS = mapOf(
            Steps.WITHDRAWAL to PaymentEvent.PAYMENT_SUBMITTED,
            Steps.DEPOSIT to PaymentEvent.FUNDS_WITHDRAWN,
            Steps.REFUND to PaymentEvent.DEPOSIT_FAILED,
        )

        /** The production wiring. */
        fun build() = TriggerContainer(AwsKopipesContext(envConfig = ControlServiceConfig()))
    }

    private val codec = PaymentsEventCodec

    private fun paymentId(uow: UnitOfWork): String = (uow.event as PaymentEvent).payment.id

    private fun heard(uow: UnitOfWork) = Heard(uow.correlated.asPaymentEvents())

    private fun trigger(uow: UnitOfWork) = uow.event as PaymentEvent

    // ---- 1. Correlate ----

    val correlatePipeline: CorrelatePipeline = CorrelatePipeline.builder()
        .id(CORRELATE)
        .eventsMicrostore(eventsMicrostore)
        .isCollectedEvent(DynamodbAdapter::forCollectedEvents)
        .normalizer(DynamodbAdapter.normalize(codec))
        .eventFilter(EventFilters.classes(PaymentEvent::class))
        .correlationKey(::paymentId)
        .build()

    // ---- 2. Deadlines ----

    private fun deadlinePipeline(step: String): CorrelatePipeline = CorrelatePipeline.builder()
        .id(deadline(step))
        .eventsMicrostore(eventsMicrostore)
        .isCollectedEvent(DynamodbAdapter::forCollectedEvents)
        .normalizer(DynamodbAdapter.normalize(codec))
        .eventFilter(EventFilters.name(STARTS.getValue(step)))
        .correlationKey(::paymentId)
        .correlationKeySuffix(deadline(step))
        .expire(true)
        .ttl { uow -> (uow.event!!.timestamp!! / 1000) + config.deadline(step).inWholeSeconds }
        .build()

    val deadlinePipelines: List<CorrelatePipeline> = Steps.ALL.map(::deadlinePipeline)

    // ---- 3. Rules ----

    /** A rule that fires on [on], unless the payment is in conflict. */
    private fun rule(id: String, on: EventFilter, emit: (UnitOfWork) -> Event): EvaluatePipeline =
        EvaluatePipeline.builder()
            .id(id)
            .eventPublisher(eventPublisher)
            .eventsMicrostore(eventsMicrostore)
            .eventCodec(codec)
            .onCorrelated()
            .eventFilter(on)
            .expression { uow -> !conflict(heard(uow)) }
            .emit { uow -> listOf(emit(uow)) }
            .oneEventPerCorrelationKey()
            .build()

    private fun on(type: String) = EventFilters.name(type)

    val startPipeline = rule(START, on(PaymentEvent.PAYMENT_SUBMITTED)) { start(trigger(it)) }
    val nextStepPipeline = rule(NEXT_STEP, on(PaymentEvent.FUNDS_WITHDRAWN)) { nextStep(trigger(it)) }
    val nothingToUndoPipeline = rule(NOTHING_TO_UNDO, on(PaymentEvent.WITHDRAWAL_FAILED)) {
        nothingToUndo(it.event as WithdrawalFailed)
    }
    val donePipeline = rule(DONE, on(PaymentEvent.FUNDS_DEPOSITED)) { done(trigger(it)) }
    val compensatePipeline = rule(COMPENSATE, on(PaymentEvent.DEPOSIT_FAILED)) { compensate(trigger(it)) }
    val undonePipeline = rule(UNDONE, on(PaymentEvent.FUNDS_REFUNDED)) { undone(trigger(it), heard(it)) }

    /** Refund failed: no outcome yet, a fault for the operator. Its emit throws, which makes the fault. */
    val refundFailedPipeline: EvaluatePipeline = EvaluatePipeline.builder()
        .id(REFUND_FAILED)
        .eventPublisher(eventPublisher)
        .eventsMicrostore(eventsMicrostore)
        .eventCodec(codec)
        .onCorrelated()
        .eventFilter(on(PaymentEvent.REFUND_FAILED))
        .expression { uow -> heard(uow).let { !conflict(it) && refundStillOwed(it) } }
        .emit { uow -> refundFailedFault(uow.event as RefundFailed) }
        .build()

    /** Conflict: fires on the answer that completes a conflicting pair. A fault; the other rules stop. */
    val conflictPipeline: EvaluatePipeline = EvaluatePipeline.builder()
        .id(CONFLICT)
        .eventPublisher(eventPublisher)
        .eventsMicrostore(eventsMicrostore)
        .eventCodec(codec)
        .onCorrelated()
        .eventFilter(
            EventFilters.onContent {
                it.eventType() in setOf(
                    PaymentEvent.FUNDS_WITHDRAWN, PaymentEvent.WITHDRAWAL_FAILED,
                    PaymentEvent.FUNDS_DEPOSITED, PaymentEvent.DEPOSIT_FAILED,
                )
            }
        )
        .expression { uow -> conflict(heard(uow)) }
        .emit { uow -> conflictFault(trigger(uow).payment, heard(uow)) }
        .build()

    /** Expired: the step's deadline item was deleted by TTL, and the step still has no answer. */
    private fun expiryPipeline(step: String): EvaluatePipeline = EvaluatePipeline.builder()
        .id(expired(step))
        .eventPublisher(eventPublisher)
        .eventsMicrostore(eventsMicrostore)
        .eventCodec(codec)
        .onExpired()
        .correlationKeySuffix(deadline(step))
        .eventFilter(on(STARTS.getValue(step)))
        .expression { uow -> heard(uow).let { !conflict(it) && unanswered(step, it) } }
        .emit { uow -> listOf(expired(trigger(uow), step)) }
        .oneEventPerCorrelationKey()
        .build()

    val expiryPipelines: List<EvaluatePipeline> = Steps.ALL.map(::expiryPipeline)

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(correlatePipeline)
        .apply { deadlinePipelines.forEach { addPipeline(it) } }
        .addPipeline(startPipeline)
        .addPipeline(nextStepPipeline)
        .addPipeline(nothingToUndoPipeline)
        .addPipeline(donePipeline)
        .addPipeline(compensatePipeline)
        .addPipeline(undonePipeline)
        .addPipeline(refundFailedPipeline)
        .addPipeline(conflictPipeline)
        .apply { expiryPipelines.forEach { addPipeline(it) } }
        .build()

    val dynamoDbAdapter = DynamodbAdapter(context)
}

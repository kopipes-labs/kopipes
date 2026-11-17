package io.kopipes.aws.testsupport

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.Identity
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord
import io.kopipes.core.Event
import io.kopipes.core.JsonEvent
import io.kopipes.core.UnitOfWork
import io.kopipes.core.sinks.EventsMicrostore
import io.kopipes.core.sinks.queryParams
import io.kopipes.core.sinks.saveOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue as EventAV

/**
 * An events table in memory, with its stream: an [EventsMicrostore] whose writes come back as DynamoDB stream records,
 * the way the real table feeds a trigger function. Tests use it to run a listener and a trigger together, in process:
 *
 * ```
 * listener.handleRequest(kinesisEvent, ctx)          // collect: writes items
 * while (true) {
 *     val batch = table.drainStream() ?: break        // what the table's stream would deliver
 *     trigger.handleRequest(batch, ctx)               // correlate, evaluate: writes more items, publishes
 * }
 * table.expire { it.suffix == "deadline" }            // what TTL would do once a deadline passed
 * ```
 *
 * Items have the attributes [io.kopipes.aws.sinks.DynamoDbEventsMicrostore] writes. Like DynamoDB, writing an item
 * again unchanged adds nothing to the stream.
 */
class EventsTableSimulator(private val region: String = "us-east-1") : EventsMicrostore {

    /** An item, by the names the microstore and the stream use. */
    data class Item(val attributes: Map<String, EventAV>) {
        val pk: String get() = attributes.getValue("pk").s
        val sk: String get() = attributes.getValue("sk").s
        val suffix: String? get() = attributes["suffix"]?.s
        val ttl: Long? get() = attributes["ttl"]?.n?.toLong()
        val expire: Boolean get() = attributes["expire"]?.bool == true
        val event: String? get() = attributes["event"]?.s
    }

    private val items = LinkedHashMap<Pair<String, String>, Item>()
    private val stream = ArrayDeque<DynamodbEvent.DynamodbStreamRecord>()
    private var sequence = 0L

    @Synchronized
    fun items(): List<Item> = items.values.toList()

    override fun save(flow: Flow<UnitOfWork>): Flow<UnitOfWork> = flow.map { uow ->
        val options = uow.saveOptions ?: return@map uow
        put(itemOf(options, uow.event))
        uow
    }

    override fun queryByPk(flow: Flow<UnitOfWork>): Flow<UnitOfWork> = flow.map { uow ->
        val pk = uow.queryParams?.pk ?: return@map uow
        val events = synchronized(this) { items.values.filter { it.pk == pk }.mapNotNull { it.event } }
        uow.copy(correlated = events.map(::JsonEvent))
    }

    @Synchronized
    fun put(item: Item) {
        val key = item.pk to item.sk
        val old = items.put(key, item)
        if (old != item) record(if (old == null) "INSERT" else "MODIFY", new = item, old = old)
    }

    /** Deletes the items saved with `expire` that match [predicate], as TTL would after their `ttl`. */
    @Synchronized
    fun expire(predicate: (Item) -> Boolean = { true }): Int {
        val expired = items.values.filter { it.expire && predicate(it) }
        expired.forEach { item ->
            items.remove(item.pk to item.sk)
            record("REMOVE", new = null, old = item, byTtl = true)
        }
        return expired.size
    }

    /** The stream records written since the last call, as one batch; `null` when there are none. */
    @Synchronized
    fun drainStream(): DynamodbEvent? {
        if (stream.isEmpty()) return null
        val batch = stream.toList()
        stream.clear()
        return DynamodbEvent().apply { records = batch }
    }

    private fun record(eventName: String, new: Item?, old: Item?, byTtl: Boolean = false) {
        val keyItem = new ?: old!!
        sequence++
        stream += DynamodbEvent.DynamodbStreamRecord().apply {
            eventID = "record-$sequence"
            this.eventName = eventName
            awsRegion = region
            dynamodb = StreamRecord()
                .withKeys(mapOf("pk" to EventAV().withS(keyItem.pk), "sk" to EventAV().withS(keyItem.sk)))
                .withNewImage(new?.attributes)
                .withOldImage(old?.attributes)
                .withSequenceNumber(sequence.toString())
                .withApproximateCreationDateTime(Date())
            if (byTtl) userIdentity = Identity().withType("Service").withPrincipalId("dynamodb.amazonaws.com")
        }
    }

    private fun itemOf(options: EventsMicrostore.SaveOptions, event: Event?): Item = Item(
        buildMap {
            put("pk", EventAV().withS(options.pk))
            put("sk", EventAV().withS(options.sk))
            put("discriminator", EventAV().withS(options.discriminator))
            options.timeStamp?.let { put("timestamp", EventAV().withN(it.toString())) }
            put("awsregion", EventAV().withS(options.awsRegion ?: region))
            options.sequenceNumber?.let { put("sequenceNumber", EventAV().withS(it)) }
            options.ttl?.let { put("ttl", EventAV().withN(it.toString())) }
            put("expire", EventAV().withBOOL(options.expire))
            put("suffix", EventAV().withS(options.suffix))
            options.data?.let { put("data", EventAV().withS(it)) }
            options.pipelineId?.let { put("pipelineId", EventAV().withS(it)) }
            event?.let { put("event", EventAV().withS(it.toString())) }
        }
    )
}

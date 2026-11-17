package io.kopipes.aws.sinks

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BatchGetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.connectors.DynamoDbConnector
import io.kopipes.aws.utils.entityValueToAttributeValue
import io.kopipes.aws.utils.toEntity
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.metrics.withStepMetrics
import io.kopipes.core.sinks.Entity
import io.kopipes.core.sinks.EntityStore
import io.kopipes.core.sinks.entityKeys
import io.kopipes.core.sinks.entityQuery
import io.kopipes.core.sinks.entityUpdate
import io.kopipes.core.sinks.withEntityUpdated
import io.kopipes.core.sinks.withLoadedEntities
import io.kopipes.core.sinks.withQueriedEntities
import io.kopipes.core.utils.mapParallel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * DynamoDB-backed [EntityStore] over one table keyed by `pk` and `sk`. Queries and loads run in order; updates run
 * [parallel] at a time. A failed request is a fault for its unit of work.
 */
open class DynamoDbEntityStore @JvmOverloads constructor(
    private val connector: DynamoDbConnector,
    private val faultManager: FaultManager,
    private val tableName: String = DEFAULT_TABLE_NAME,
    private val parallel: Int = DynamoDbSink.DEFAULT_PARALLEL,
    private val stepMetrics: Boolean = false,
) : EntityStore {

    /** Uses the entity table (`ENTITY_TABLE_NAME`) and settings from [context]. */
    constructor(context: AwsKopipesContext) : this(
        connector = DynamoDbConnector(DynamoDbConnector.Options(context.dynamoDbClientFactory)),
        faultManager = context.faultManager,
        tableName = context.envConfig.entityTableName() ?: DEFAULT_TABLE_NAME,
        parallel = context.envConfig.parallel() ?: DynamoDbSink.DEFAULT_PARALLEL,
        stepMetrics = context.stepMetrics,
    )

    companion object {
        const val DEFAULT_TABLE_NAME = "entities"

        /** BatchGetItem's limit of keys per request. */
        private const val MAX_BATCH_GET_KEYS = 100
    }

    internal fun toQueryRequest(query: EntityStore.Query): QueryRequest {
        val table = tableName
        return QueryRequest {
            tableName = table
            indexName = query.index
            keyConditionExpression = "#k = :v"
            expressionAttributeNames = mapOf("#k" to query.attribute)
            expressionAttributeValues = mapOf(":v" to AttributeValue.S(query.value))
            consistentRead = query.consistentRead && query.index == null
        }
    }

    internal fun toBatchGetRequest(keys: List<EntityStore.Key>): BatchGetItemRequest {
        val table = tableName
        return BatchGetItemRequest {
            requestItems = mapOf(
                table to KeysAndAttributes {
                    this.keys = keys.distinct().map { key -> key.toAttributeValues() }
                }
            )
        }
    }

    internal fun toUpdateItemRequest(update: EntityStore.Update): UpdateItemRequest {
        val expression = updateExpression(
            update.set.mapValues { (_, value) -> DynamoDbUpdateValue.DbSet(entityValueToAttributeValue(value)) } +
                update.add.mapValues { (_, value) -> DynamoDbUpdateValue.DbAdd(entityValueToAttributeValue(value)) } +
                update.remove.associateWith { DynamoDbUpdateValue.DbRemove }
        )
        val condition = update.onlyIfNewer?.let { attribute ->
            val alias = makeAliasForKey(attribute)
            "attribute_not_exists(#$alias) OR #$alias < :$alias"
        }
        val table = tableName
        return UpdateItemRequest {
            tableName = table
            key = update.key.toAttributeValues()
            updateExpression = expression.updateExpression
            expressionAttributeNames = expression.expressionAttributeNames
            expressionAttributeValues = expression.expressionAttributeValues.ifEmpty { null }
            conditionExpression = condition
        }
    }

    override fun query(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.mapNotNull { uow ->
            val query = uow.entityQuery ?: return@mapNotNull uow
            faultManager.faulty(uow) { item ->
                item.withStepMetrics("query", stepMetrics) { measured ->
                    val response = connector.queryAll(toQueryRequest(query), measured)
                    measured.withQueriedEntities(response.items.orEmpty().map { it.toEntity() })
                }
            }
        }

    override fun get(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.mapNotNull { uow ->
            val keys = uow.entityKeys ?: return@mapNotNull uow
            faultManager.faulty(uow) { item ->
                item.withStepMetrics("get", stepMetrics) { measured ->
                    val loaded = keys.chunked(MAX_BATCH_GET_KEYS).flatMap { chunk ->
                        connector.batchGetItem(toBatchGetRequest(chunk), measured)
                            .responses?.get(tableName).orEmpty()
                            .map { it.toEntity() }
                    }
                    measured.withLoadedEntities(inKeyOrder(keys, loaded))
                }
            }
        }

    override fun update(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.mapParallel(parallel) { uow ->
            val update = uow.entityUpdate ?: return@mapParallel uow
            faultManager.faulty(uow) { item ->
                item.withStepMetrics("update", stepMetrics) { measured ->
                    val applied = try {
                        connector.update(toUpdateItemRequest(update), measured) != null
                    } catch (e: ConditionalCheckFailedException) {
                        false
                    }
                    measured.withEntityUpdated(applied)
                }
            }
        }

    /** BatchGetItem answers in any order; this puts the entities back in the order of their keys. */
    private fun inKeyOrder(keys: List<EntityStore.Key>, loaded: List<Entity>): List<Entity> {
        val byKey = loaded.associateBy { EntityStore.Key(it["pk"].toString(), it["sk"].toString()) }
        return keys.distinct().mapNotNull { byKey[it] }
    }

    private fun EntityStore.Key.toAttributeValues(): Map<String, AttributeValue> =
        mapOf("pk" to AttributeValue.S(pk), "sk" to AttributeValue.S(sk))
}

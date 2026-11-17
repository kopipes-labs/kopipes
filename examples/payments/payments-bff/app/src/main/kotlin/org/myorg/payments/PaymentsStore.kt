package org.myorg.payments

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import io.kopipes.aws.utils.DynamoDbAttributeValueMapReader
import io.kopipes.core.utils.AttributeValueMapReader

/**
 * The BFF's views table. One item per payment, `pk = <payment id>, sk = PAYMENT`: the payment as accepted, plus
 * `at_<TYPE>` and `reason_<TYPE>` for each event heard, and `at_PAYMENT_EXPIRED_<step>` for each late step.
 * The sparse index `byCustomer` (`customerId`, `submittedAt`) holds only accepted payments.
 */
object Items {
    const val PAYMENT = "PAYMENT"
    const val BY_CUSTOMER = "byCustomer"

    const val HEARD_PREFIX = "at_"
    const val REASON_PREFIX = "reason_"
    const val EXPIRED_PREFIX = "at_PAYMENT_EXPIRED_"

    const val CUSTOMER_ID = "customerId"
    const val SUBMITTED_AT = "submittedAt"
    const val REQUEST_HASH = "requestHash"

    fun key(id: String): Map<String, AttributeValue> = mapOf("pk" to AttributeValue.S(id), "sk" to AttributeValue.S(PAYMENT))
}

/**
 * Reads a payment item, from the table or from its stream. `null` until a `PUT` has accepted the payment: the listener
 * may record events for an id first (in a replay, say), and that alone isn't a payment.
 */
fun paymentRecordOf(names: Set<String>, item: AttributeValueMapReader): PaymentRecord? {
    val requestHash = item.getS(Items.REQUEST_HASH) ?: return null
    val payment = Payment(
        id = item.getS("pk") ?: return null,
        customerId = item.getS(Items.CUSTOMER_ID) ?: return null,
        from = Party(item.getS("fromKind") ?: return null, item.getS("fromAccount") ?: return null),
        to = Party(item.getS("toKind") ?: return null, item.getS("toAccount") ?: return null),
        amount = item.getS("amount") ?: return null,
        currency = item.getS("currency") ?: return null,
        submittedAt = item.getLong(Items.SUBMITTED_AT) ?: return null,
    )
    val heard = names.filter { it.startsWith(Items.HEARD_PREFIX) && !it.startsWith(Items.EXPIRED_PREFIX) }
        .mapNotNull { name -> item.getLong(name)?.let { name.removePrefix(Items.HEARD_PREFIX) to it } }
        .toMap()
    val reasons = names.filter { it.startsWith(Items.REASON_PREFIX) }
        .mapNotNull { name -> item.getS(name)?.let { name.removePrefix(Items.REASON_PREFIX) to it } }
        .toMap()
    val delayed = names.filter { it.startsWith(Items.EXPIRED_PREFIX) }.map { it.removePrefix(Items.EXPIRED_PREFIX) }.toSet()
    return PaymentRecord(payment, requestHash, heard, reasons, delayed)
}

sealed interface SubmitOutcome {
    /** The first `PUT` for this id. */
    data class Accepted(val record: PaymentRecord) : SubmitOutcome

    /** The id was taken; the caller compares the stored request with its own. */
    data class AlreadyExists(val record: PaymentRecord?) : SubmitOutcome
}

/** What the REST API needs from the table. */
interface PaymentsStore {
    suspend fun find(id: String): PaymentRecord?
    suspend fun submit(request: ValidRequest, submittedAt: Long): SubmitOutcome
    suspend fun listByCustomer(customerId: String): List<PaymentRecord>
}

class DynamoDbPaymentsStore(private val client: DynamoDbClient, private val tableName: String) : PaymentsStore {

    override suspend fun find(id: String): PaymentRecord? {
        val item = client.getItem(GetItemRequest {
            tableName = this@DynamoDbPaymentsStore.tableName
            key = Items.key(id)
            consistentRead = true
        }).item ?: return null
        return paymentRecordOf(item.keys, DynamoDbAttributeValueMapReader(item))
    }

    override suspend fun submit(request: ValidRequest, submittedAt: Long): SubmitOutcome = try {
        client.updateItem(submitRequest(tableName, request, submittedAt))
        SubmitOutcome.Accepted(PaymentRecord(request.toPayment(submittedAt), request.hash))
    } catch (e: ConditionalCheckFailedException) {
        SubmitOutcome.AlreadyExists(find(request.id))
    }

    /** Newest first. No paging: this is a demo. */
    override suspend fun listByCustomer(customerId: String): List<PaymentRecord> {
        val items = client.query(QueryRequest {
            tableName = this@DynamoDbPaymentsStore.tableName
            indexName = Items.BY_CUSTOMER
            keyConditionExpression = "#c = :c"
            expressionAttributeNames = mapOf("#c" to Items.CUSTOMER_ID)
            expressionAttributeValues = mapOf(":c" to AttributeValue.S(customerId))
            scanIndexForward = false
        }).items.orEmpty()
        return items.mapNotNull { paymentRecordOf(it.keys, DynamoDbAttributeValueMapReader(it)) }
    }
}

/**
 * Accepts a payment once. An update, not a put, because the listener may already have recorded events for this id.
 * The condition makes a repeated `PUT` write nothing, so the trigger publishes `PAYMENT_SUBMITTED` once.
 */
fun submitRequest(table: String, request: ValidRequest, submittedAt: Long): UpdateItemRequest {
    val values = mapOf(
        Items.CUSTOMER_ID to AttributeValue.S(request.customerId),
        "fromKind" to AttributeValue.S(request.from.kind),
        "fromAccount" to AttributeValue.S(request.from.account),
        "toKind" to AttributeValue.S(request.to.kind),
        "toAccount" to AttributeValue.S(request.to.account),
        "amount" to AttributeValue.S(request.amount),
        "currency" to AttributeValue.S(request.currency),
        Items.SUBMITTED_AT to AttributeValue.N(submittedAt.toString()),
        Items.REQUEST_HASH to AttributeValue.S(request.hash),
        "discriminator" to AttributeValue.S(Items.PAYMENT),
    )
    val names = values.keys.mapIndexed { i, name -> "#a$i" to name }.toMap()
    val placeholders = values.keys.indices.map { ":v$it" }
    return UpdateItemRequest {
        tableName = table
        key = Items.key(request.id)
        updateExpression = "SET " + names.keys.zip(placeholders).joinToString(", ") { (n, v) -> "$n = $v" }
        conditionExpression = "attribute_not_exists(#hash)"
        expressionAttributeNames = names + ("#hash" to Items.REQUEST_HASH)
        expressionAttributeValues = placeholders.zip(values.values).toMap()
    }
}

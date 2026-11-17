package org.myorg.payments

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import io.kopipes.core.UnitOfWork

/*
 * An outside system answers with a reference, never with our payment: a ledger row knows its own entry, not the card
 * or when the payment was submitted. So each ingress gateway keeps its own copy of the payments it will hear about,
 * built from the entry event on the default channel, and fills in every event's snapshot from it. It never asks
 * another service.
 *
 * Items: `pk = <payment id>`, `sk = PAYMENT`, `payment = <json>`.
 */

/** Remembers the payment of a request event heard on the default channel. */
fun rememberPayment(table: String, uow: UnitOfWork): UpdateItemRequest? {
    val payment = (uow.event as? PaymentEvent)?.payment ?: return null
    return UpdateItemRequest {
        tableName = table
        key = paymentKey(payment.id)
        updateExpression = "SET #p = :p"
        expressionAttributeNames = mapOf("#p" to "payment")
        expressionAttributeValues = mapOf(":p" to AttributeValue.S(paymentsJson.encodeToString(Payment.serializer(), payment)))
    }
}

private fun paymentKey(id: String) = mapOf("pk" to AttributeValue.S(id), "sk" to AttributeValue.S("PAYMENT"))

/**
 * An answer arrived before the request it answers was remembered. Retryable: the request is on its way, so with
 * stream retry on, the answer's batch is delivered again instead of becoming a fault.
 */
class PaymentNotKnownYet(reference: String) : RuntimeException("No request remembered yet for payment $reference")

/** The payment behind an outside system's reference. */
fun interface PaymentLookup {
    /** The payment, or [PaymentNotKnownYet]. */
    suspend fun payment(reference: String): Payment
}

class DynamoDbPaymentLookup(private val client: DynamoDbClient, private val table: String) : PaymentLookup {
    override suspend fun payment(reference: String): Payment {
        val item = client.getItem(GetItemRequest {
            tableName = table
            key = paymentKey(reference)
            consistentRead = true
        }).item
        val json = (item?.get("payment") as? AttributeValue.S)?.value ?: throw PaymentNotKnownYet(reference)
        return paymentsJson.decodeFromString(Payment.serializer(), json)
    }
}

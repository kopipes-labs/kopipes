package io.kopipes.aws.sinks

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue

/** Components required for a DynamoDB update operation. */
data class DynamoDbUpdateExpression(
    val expressionAttributeNames: Map<String, String>,
    val expressionAttributeValues: Map<String, AttributeValue>,
    val updateExpression: String,
    val returnValues: String = "ALL_NEW",
)

/** Single DynamoDB update operation for an attribute. */
sealed interface DynamoDbUpdateValue {
    /** SET operation. */
    data class DbSet(val value: AttributeValue) : DynamoDbUpdateValue

    /** REMOVE operation. */
    data object DbRemove : DynamoDbUpdateValue

    /** ADD operation. */
    data class DbAdd(val value: AttributeValue) : DynamoDbUpdateValue

    /** DELETE operation. */
    data class DbDelete(val value: AttributeValue) : DynamoDbUpdateValue
}

fun updateExpression(item: Map<String, DynamoDbUpdateValue>): DynamoDbUpdateExpression {
    val expressionAttributeNames = mutableMapOf<String, String>()
    val expressionAttributeValues = mutableMapOf<String, AttributeValue>()

    val setClauses = mutableListOf<String>()
    val addClauses = mutableListOf<String>()
    val deleteClauses = mutableListOf<String>()
    val removeClauses = mutableListOf<String>()

    item
        .forEach { (attributeName, updateValue) ->
            val alias = makeAliasForKey(attributeName)
            val namePlaceholder = "#$alias"
            val valuePlaceholder = ":$alias"

            expressionAttributeNames[namePlaceholder] = attributeName

            when (updateValue) {
                is DynamoDbUpdateValue.DbSet -> {
                    expressionAttributeValues[valuePlaceholder] = updateValue.value
                    setClauses += "$namePlaceholder = $valuePlaceholder"
                }

                DynamoDbUpdateValue.DbRemove -> {
                    removeClauses += namePlaceholder
                }

                is DynamoDbUpdateValue.DbAdd -> {
                    expressionAttributeValues[valuePlaceholder] = updateValue.value
                    addClauses += "$namePlaceholder $valuePlaceholder"
                }

                is DynamoDbUpdateValue.DbDelete -> {
                    expressionAttributeValues[valuePlaceholder] = updateValue.value
                    deleteClauses += "$namePlaceholder $valuePlaceholder"
                }
            }
        }

    val updateExpressionParts = buildList {
        if (setClauses.isNotEmpty()) add("SET ${setClauses.joinToString(", ")}")
        if (removeClauses.isNotEmpty()) add("REMOVE ${removeClauses.joinToString(", ")}")
        if (addClauses.isNotEmpty()) add("ADD ${addClauses.joinToString(", ")}")
        if (deleteClauses.isNotEmpty()) add("DELETE ${deleteClauses.joinToString(", ")}")
    }

    return DynamoDbUpdateExpression(
        expressionAttributeNames = expressionAttributeNames,
        expressionAttributeValues = expressionAttributeValues,
        updateExpression = updateExpressionParts.joinToString(" "),
    )
}

internal fun makeAliasForKey(baseKey: String): String =
    baseKey.replace(Regex("[^a-zA-Z0-9_]")) { match ->
        "_x${match.value.first().code.toString(16)}_"
    }

fun timestampCondition(fieldName: String = "timestamp"): Map<String, String> =
    mapOf(
        "ConditionExpression" to "attribute_not_exists(#$fieldName) OR #$fieldName < :$fieldName",
    )

fun pkCondition(fieldName: String = "pk"): Map<String, String> =
    mapOf(
        "ConditionExpression" to "attribute_not_exists($fieldName)",
    )

package io.kopipes.aws.utils

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.kopipes.core.sinks.Entity
import java.math.BigDecimal

/**
 * DynamoDB value of an [Entity] attribute: String, Number, Boolean, null, ByteArray, Map, List, Set (of strings or
 * numbers; other sets become lists) or Enum (its name).
 */
fun entityValueToAttributeValue(value: Any?): AttributeValue = when (value) {
    null -> AttributeValue.Null(true)
    is String -> AttributeValue.S(value)
    is Boolean -> AttributeValue.Bool(value)
    is Number -> AttributeValue.N(numberString(value))
    is ByteArray -> AttributeValue.B(value)
    is Enum<*> -> AttributeValue.S(value.name)
    is Map<*, *> -> AttributeValue.M(value.entries.associate { (k, v) -> k.toString() to entityValueToAttributeValue(v) })
    is Set<*> -> when {
        value.isNotEmpty() && value.all { it is String } -> AttributeValue.Ss(value.map { it as String })
        value.isNotEmpty() && value.all { it is Number } -> AttributeValue.Ns(value.map { numberString(it as Number) })
        else -> AttributeValue.L(value.map(::entityValueToAttributeValue))
    }
    is Iterable<*> -> AttributeValue.L(value.map(::entityValueToAttributeValue))
    is Array<*> -> AttributeValue.L(value.map(::entityValueToAttributeValue))
    else -> throw IllegalArgumentException("unsupported entity value ${value::class.qualifiedName}: $value")
}

/** [Entity] value of a DynamoDB attribute; numbers are Long when integral, BigDecimal otherwise. */
fun attributeValueToEntityValue(value: AttributeValue): Any? = when (value) {
    is AttributeValue.S -> value.value
    is AttributeValue.N -> parseNumber(value.value)
    is AttributeValue.Bool -> value.value
    is AttributeValue.Null -> null
    is AttributeValue.B -> value.value
    is AttributeValue.M -> value.value.mapValues { (_, v) -> attributeValueToEntityValue(v) }
    is AttributeValue.L -> value.value.map(::attributeValueToEntityValue)
    is AttributeValue.Ss -> value.value.toSet()
    is AttributeValue.Ns -> value.value.map(::parseNumber).toSet()
    is AttributeValue.Bs -> value.value
    else -> null
}

fun Map<String, AttributeValue>.toEntity(): Entity = mapValues { (_, v) -> attributeValueToEntityValue(v) }

private fun numberString(number: Number): String = when (number) {
    is BigDecimal -> number.toPlainString()
    is Double, is Float -> BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
    else -> number.toString()
}

private fun parseNumber(value: String): Number = value.toLongOrNull() ?: BigDecimal(value)

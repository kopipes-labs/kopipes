package org.myorg.sut

import io.kopipes.cdk.Stage

import software.amazon.awscdk.Aws
import software.amazon.awscdk.CfnResource
import software.amazon.awscdk.services.dynamodb.*
import software.amazon.awscdk.services.iam.Effect
import software.amazon.awscdk.services.iam.PolicyStatement
import software.amazon.awscdk.services.lambda.Function
import software.amazon.awscdk.services.lambda.StartingPosition
import software.amazon.awscdk.services.lambda.eventsources.DynamoEventSource

fun RegionalHealthCheckStack.entityTableName(): String =
    "${service}-${stage}-tracer"

fun RegionalHealthCheckStack.tableArn(): String =
    "arn:aws:dynamodb:${regionName}:${Aws.ACCOUNT_ID}:table/${entityTableName()}"

fun RegionalHealthCheckStack.tableStreamArn(): String =
    "arn:aws:dynamodb:${regionName}:${Aws.ACCOUNT_ID}:table/${entityTableName()}/stream/*"

fun RegionalHealthCheckStack.newEntitiesTable(): TableV2 {
    val table = TableV2.Builder.create(this, "EntitiesTable")
        .tableName(entityTableName())
        .partitionKey(
            Attribute.builder()
                .name("pk")
                .type(AttributeType.STRING)
                .build()
        )
        .sortKey(
            Attribute.builder()
                .name("sk")
                .type(AttributeType.STRING)
                .build()
        )
//        .replicas(
//            listOf(
//                ReplicaTableProps.builder()
//                    .region("us-east-1")
//                    .build()
//            )
//        )
        .billing(Billing.onDemand())
        .dynamoStream(StreamViewType.NEW_AND_OLD_IMAGES)
        .timeToLiveAttribute("ttl")
        .encryption(TableEncryptionV2.dynamoOwnedKey())
        .build()

    if (stage != Stage.LOCAL) {
        val cfnTable = table.node.defaultChild as CfnResource
        cfnTable.cfnOptions.condition = isWestCondition()
    }

    return table
}

fun RegionalHealthCheckStack.grantAccessToTable(function: Function, tracerTable: TableV2) {
    function.addToRolePolicy(
        PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(
                listOf(
                    "dynamodb:Query",
                    "dynamodb:UpdateItem",
                )
            )
            .resources(
                listOf(
                    tracerTable.tableArn,
                )
            )
            .build()
    )
}

fun RegionalHealthCheckStack.consumeFromTable(
    function: Function,
    table: TableV2,
) {
    function.addEventSource(
        DynamoEventSource.Builder.create(table)
            .startingPosition(StartingPosition.TRIM_HORIZON)
            .let {
                // This is failing in local dev. We need to check if it works in other envs.
                if (stage != Stage.LOCAL) it.filters(entitiesTableStreamFilterPatterns())
                it
            }
            .build()
    )
}

fun RegionalHealthCheckStack.entitiesTableStreamFilterPatterns(): List<Map<String, Any>> =
    listOf(
        mapOf(
            "dynamodb" to mapOf(
                "NewImage" to mapOf(
                    "awsregion" to mapOf(
                        "S" to listOf(regionName)
                    )
                )
            )
        )
    )
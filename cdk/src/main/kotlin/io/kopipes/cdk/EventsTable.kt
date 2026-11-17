package io.kopipes.cdk

import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.dynamodb.Attribute
import software.amazon.awscdk.services.dynamodb.AttributeType
import software.amazon.awscdk.services.dynamodb.Billing
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexPropsV2
import software.amazon.awscdk.services.dynamodb.ProjectionType
import software.amazon.awscdk.services.dynamodb.StreamViewType
import software.amazon.awscdk.services.dynamodb.TableV2

/**
 * @param suffix the table is named `<service>-<stage>-<suffix>`.
 * @param stream adds a DynamoDB stream (new and old images), for a trigger function.
 * @param removalPolicy `RETAIN` keeps the data when the stack is deleted; `DESTROY` suits LocalStack.
 */
data class EventsTableProps @JvmOverloads constructor(
    val suffix: String = "events",
    val stream: Boolean = true,
    val removalPolicy: RemovalPolicy = RemovalPolicy.RETAIN,
)

/**
 * A table in the shape kopipes uses for an events microstore and for a BFF's views: `pk`/`sk` keys, a `ttl`
 * attribute, and the `gsi1` index (`discriminator`, `pk`) for listing items of one kind.
 *
 * With [EventsTableProps.stream], what's written comes back on the stream: that's how a control service's
 * correlations reach its rules, and how a BFF publishes only what it has recorded. An item whose TTL passes is
 * deleted by DynamoDB and arrives on the stream as a REMOVE, which is how deadlines fire.
 */
class EventsTable @JvmOverloads constructor(stack: KopipesStack, id: String, props: EventsTableProps = EventsTableProps()) :
    software.constructs.Construct(stack, id) {

    val table: TableV2 = TableV2.Builder.create(this, "Table")
        .tableName(stack.name(props.suffix))
        .partitionKey(Attribute.builder().name("pk").type(AttributeType.STRING).build())
        .sortKey(Attribute.builder().name("sk").type(AttributeType.STRING).build())
        .billing(Billing.onDemand())
        .removalPolicy(props.removalPolicy)
        .timeToLiveAttribute("ttl")
        .apply { if (props.stream) dynamoStream(StreamViewType.NEW_AND_OLD_IMAGES) }
        .globalSecondaryIndexes(
            listOf(
                GlobalSecondaryIndexPropsV2.builder()
                    .indexName("gsi1")
                    .partitionKey(Attribute.builder().name("discriminator").type(AttributeType.STRING).build())
                    .sortKey(Attribute.builder().name("pk").type(AttributeType.STRING).build())
                    .projectionType(ProjectionType.ALL)
                    .build()
            )
        )
        .build()
}

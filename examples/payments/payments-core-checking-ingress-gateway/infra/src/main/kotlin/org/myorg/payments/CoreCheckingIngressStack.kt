package org.myorg.payments

import io.kopipes.cdk.EnvVars
import io.kopipes.cdk.EventsTable
import io.kopipes.cdk.EventsTableProps
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.dynamodb.Attribute
import software.amazon.awscdk.services.dynamodb.AttributeType
import software.amazon.awscdk.services.dynamodb.StreamViewType
import software.amazon.awscdk.services.dynamodb.TableV2
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * Turns core checking's ledger changes into our events.
 *
 *     default channel ─► listener ─► payments table           remembers each WITHDRAWAL_REQUESTED's payment
 *     ledger ─(change data capture)─► ledger function ─► bus   FUNDS_WITHDRAWN, WITHDRAWAL_FAILED, ...
 *
 * The ledger table stands in for change data capture on core checking's transaction log: in production, a CDC tool
 * would feed this function the same rows. In simulation mode, the egress gateway's simulator writes it.
 */
class CoreCheckingIngressStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val local = stage == Stage.LOCAL
    private val removal = if (local) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN
    private val jar = Code.fromAsset("../app/build/libs/payments-core-checking-ingress-gateway.jar")

    val ledger: TableV2 = TableV2.Builder.create(this, "Ledger")
        .tableName(Partners.ledgerTableName(stage))
        .partitionKey(Attribute.builder().name("ENTRY_ID").type(AttributeType.STRING).build())
        .dynamoStream(StreamViewType.NEW_AND_OLD_IMAGES)
        .removalPolicy(removal)
        .build()

    val payments = EventsTable(this, "Payments", EventsTableProps(suffix = "payments", stream = false, removalPolicy = removal))

    val listener = KopipesFunction(this, "listener", KopipesFunctionProps(jar, "org.myorg.payments.Listener::handleRequest"))
        .listenTo()
        .uses(payments, EnvVars.ENTITY_TABLE_NAME)

    /** A row heard before its request waits: stream retry, until the request arrives or the row expires (24 h). */
    val ledgerFunction = KopipesFunction(this, "ledger", KopipesFunctionProps(jar, "org.myorg.payments.Ledger::handleRequest"))
        .triggeredBy(ledger, retryAttempts = -1)
        .uses(payments, EnvVars.ENTITY_TABLE_NAME)
        .retryStream()

    init {
        CfnOutput.Builder.create(this, "LedgerTableName").value(ledger.tableName).build()
    }
}

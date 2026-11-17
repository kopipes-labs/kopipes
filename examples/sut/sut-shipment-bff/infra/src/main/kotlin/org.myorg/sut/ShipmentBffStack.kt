package org.myorg.sut

import io.kopipes.cdk.EnvVars
import io.kopipes.cdk.EventsTable
import io.kopipes.cdk.EventsTableProps
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * The shipments' BFF.
 *
 *     caller ─► restapi ─► shipments table ─► (stream) ─► trigger ─► bus
 *     default channel ─► listener ─► shipments table
 */
class ShipmentBffStack(scope: Construct, serviceProps: ServiceProps) : KopipesStack(scope, serviceProps) {

    private val jar = Code.fromAsset("../app/build/libs/sut-shipment-bff.jar")

    val shipments = EventsTable(this, "Shipments", EventsTableProps(suffix = "shipments"))

    val restapi = KopipesFunction(this, "restapi", KopipesFunctionProps(jar, "org.myorg.sut.RestApi::handleRequest"))
        .uses(shipments, EnvVars.ENTITY_TABLE_NAME)

    val listener = KopipesFunction(this, "listener", KopipesFunctionProps(jar, "org.myorg.sut.Listener::handleRequest"))
        .listenTo()
        .uses(shipments, EnvVars.ENTITY_TABLE_NAME)

    val trigger = KopipesFunction(this, "trigger", KopipesFunctionProps(jar, "org.myorg.sut.Trigger::handleRequest"))
        .triggeredBy(shipments)
        .uses(shipments, EnvVars.ENTITY_TABLE_NAME)
}

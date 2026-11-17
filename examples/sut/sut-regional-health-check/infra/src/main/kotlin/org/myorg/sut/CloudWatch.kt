package org.myorg.sut

import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.services.cloudwatch.CfnAlarm
import software.amazon.awscdk.services.cloudwatch.CfnCompositeAlarm
import software.amazon.awscdk.services.route53.CfnHealthCheck

fun RegionalHealthCheckStack.createRegionalHealthCheck() {
    val apigAlarmName = "${service}-${stage}-Apig5xxAlarm"
    val dynamoDbAlarmName = "${service}-${stage}-DynamoDB5xxAlarm"
    val regionalHealthAlarmName = "${service}-${stage}-${regionName}"

    val apig5xxAlarm = CfnAlarm.Builder.create(this, "Apig5xxAlarm")
        .alarmName(apigAlarmName)
        .namespace("AWS/ApiGateway")
        .metricName("5XXError")
        .dimensions(
            listOf(
                CfnAlarm.DimensionProperty.builder()
                    .name("ApiName")
                    .value("${stage}-${service}")
                    .build()
            )
        )
        .comparisonOperator("GreaterThanThreshold")
        .threshold(0)
        .period(60)
        .evaluationPeriods(5)
        .statistic("Sum")
        .unit("Count")
        .treatMissingData("notBreaching")
        .build()

    val apigHealthCheck = CfnHealthCheck.Builder.create(this, "ApigHealthCheck")
        .healthCheckConfig(
            CfnHealthCheck.HealthCheckConfigProperty.builder()
                .type("CLOUDWATCH_METRIC")
                .alarmIdentifier(
                    CfnHealthCheck.AlarmIdentifierProperty.builder()
                        .name(apig5xxAlarm.ref)
                        .region(regionName)
                        .build()
                )
                .insufficientDataHealthStatus("LastKnownStatus")
                .build()
        )
        .build()

    apigHealthCheck.addDependency(apig5xxAlarm)

    val dynamoDb5xxAlarm = CfnAlarm.Builder.create(this, "DynamoDB5xxAlarm")
        .alarmName(dynamoDbAlarmName)
        .namespace("AWS/DynamoDB")
        .metricName("SystemErrors")
        .dimensions(
            listOf(
                CfnAlarm.DimensionProperty.builder()
                    .name("TableName")
                    .value("${service}-${stage}-entities")
                    .build()
            )
        )
        .comparisonOperator("GreaterThanThreshold")
        .threshold(0)
        .period(60)
        .evaluationPeriods(5)
        .statistic("Sum")
        .unit("Count")
        .treatMissingData("notBreaching")
        .build()

    val dynamoDbHealthCheck = CfnHealthCheck.Builder.create(this, "DynamoDBHealthCheck")
        .healthCheckConfig(
            CfnHealthCheck.HealthCheckConfigProperty.builder()
                .type("CLOUDWATCH_METRIC")
                .alarmIdentifier(
                    CfnHealthCheck.AlarmIdentifierProperty.builder()
                        .name(dynamoDb5xxAlarm.ref)
                        .region(regionName)
                        .build()
                )
                .insufficientDataHealthStatus("LastKnownStatus")
                .build()
        )
        .build()

    dynamoDbHealthCheck.addDependency(dynamoDb5xxAlarm)

    val regionalHealthAlarm = CfnCompositeAlarm.Builder.create(this, "RegionalHealthAlarm")
        .alarmName(regionalHealthAlarmName)
        .alarmRule("ALARM($apigAlarmName) OR ALARM($dynamoDbAlarmName)")
        .build()

    regionalHealthAlarm.addDependency(apig5xxAlarm)
    regionalHealthAlarm.addDependency(dynamoDb5xxAlarm)

    val regionalHealthCheck = CfnHealthCheck.Builder.create(this, "RegionalHealthCheck")
        .healthCheckConfig(
            CfnHealthCheck.HealthCheckConfigProperty.builder()
                .type("CALCULATED")
                .healthThreshold(2)
                .childHealthChecks(
                    listOf(
                        apigHealthCheck.ref,
                        dynamoDbHealthCheck.ref,
                    )
                )
                .build()
        )
        .build()

    regionalHealthCheck.addDependency(apigHealthCheck)
    regionalHealthCheck.addDependency(dynamoDbHealthCheck)

    CfnOutput.Builder.create(this, "HealthAlarm")
        .value(regionalHealthAlarm.ref)
        .build()

    CfnOutput.Builder.create(this, "HealthCheckId")
        .value(regionalHealthCheck.ref)
        .build()
}
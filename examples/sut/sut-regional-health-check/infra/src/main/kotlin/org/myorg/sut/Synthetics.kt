package org.myorg.sut

import software.amazon.awscdk.Stack
import software.amazon.awscdk.services.iam.*
import software.amazon.awscdk.services.s3.Bucket
import software.amazon.awscdk.services.synthetics.CfnCanary

fun RegionalHealthCheckStack.createSyntheticsCanary(
    bucket: Bucket,
    healthCheckEndpoint: String,
    apiKey: String,
) {
    val syntheticsRole = Role.Builder.create(this, "CloudWatchSyntheticsRole")
        .roleName("${service}-${stage}-${regionName}-synthetics-role")
        .assumedBy(ServicePrincipal("lambda.amazonaws.com"))
        .inlinePolicies(
            mapOf(
                "synthetics" to PolicyDocument.Builder.create()
                    .statements(
                        listOf(
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(
                                    listOf(
                                        "s3:GetObject",
                                        "s3:PutObject",
                                    )
                                )
                                .resources(
                                    listOf(
                                        bucket.arnForObjects("*"),
                                    )
                                )
                                .build(),
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(
                                    listOf(
                                        "s3:GetBucketLocation",
                                    )
                                )
                                .resources(
                                    listOf(
                                        bucket.bucketArn,
                                    )
                                )
                                .build(),
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(
                                    listOf(
                                        "s3:ListAllMyBuckets",
                                    )
                                )
                                .resources(listOf("*"))
                                .build(),
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(
                                    listOf(
                                        "cloudwatch:PutMetricData",
                                    )
                                )
                                .resources(listOf("*"))
                                .conditions(
                                    mapOf(
                                        "StringEquals" to mapOf(
                                            "cloudwatch:namespace" to "CloudWatchSynthetics",
                                        ),
                                    )
                                )
                                .build(),
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(
                                    listOf(
                                        "logs:CreateLogStream",
                                        "logs:PutLogEvents",
                                        "logs:CreateLogGroup",
                                    )
                                )
                                .resources(
                                    listOf(
                                        "arn:aws:logs:${regionName}:${Stack.of(this).account}:log-group:/aws/lambda/cwsyn-regional-health-check-*",
                                    )
                                )
                                .build(),
                        )
                    )
                    .build(),
            )
        )
        .build()

    CfnCanary.Builder.create(this, "Canary")
        .name("regional-health-check")
        .code(
            CfnCanary.CodeProperty.builder()
                .handler("exports.handler")
                .script(
                    """
                    const synthetics = require('Synthetics');

                    exports.handler = async function(event) {
                      return await synthetics.executeHttpStep(
                        'call health check',
                        {
                          'hostname': '$healthCheckEndpoint',
                          'method': 'GET',
                          'path': '/${stage}/check',
                          'port': 443,
                          'protocol': 'https:',
                          'headers': {
                            'x-api-key': process.env.API_KEY,
                          }
                        },
                        undefined,
                        {
                          includeRequestHeaders: true,
                          includeResponseHeaders: true,
                          restrictedHeaders: ['X-Api-Key', 'X-Amz-Security-Token', 'Authorization'],
                          includeRequestBody: true,
                          includeResponseBody: true
                        }
                      );
                    }
                    """.trimIndent()
                )
                .build()
        )
        .executionRoleArn(syntheticsRole.roleArn)
        .runtimeVersion("syn-nodejs-puppeteer-3.8")
        .runConfig(
            CfnCanary.RunConfigProperty.builder()
                .timeoutInSeconds(60)
                .environmentVariables(
                    mapOf(
                        "API_KEY" to apiKey,
                    )
                )
                .build()
        )
        .artifactS3Location("s3://${bucket.bucketName}")
        .startCanaryAfterCreation(true)
        .schedule(
            CfnCanary.ScheduleProperty.builder()
                .expression("cron(* * * * ? *)")
                .durationInSeconds("0")
                .build()
        )
        .successRetentionPeriod(92)
        .failureRetentionPeriod(92)
        .build()
}
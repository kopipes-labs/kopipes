package org.myorg.sut

import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.services.iam.Effect
import software.amazon.awscdk.services.iam.PolicyStatement
import software.amazon.awscdk.services.iam.ServicePrincipal
import software.amazon.awscdk.services.s3.Bucket
import software.amazon.awscdk.services.s3.EventType
import software.amazon.awscdk.services.s3.NotificationKeyFilter
import software.amazon.awscdk.services.s3.notifications.SnsDestination
import software.amazon.awscdk.services.sns.Topic
import software.amazon.awscdk.services.sns.TopicPolicy

fun RegionalHealthCheckStack.newTopic(): Topic =
    Topic.Builder.create(this, "Topic")
        .topicName("${service}-${stage}")
        // Intentionally not setting KMS here, matching the Serverless config comment:
        // KmsMasterKeyId: alias/aws/sns
        .build()

fun RegionalHealthCheckStack.publishToTopic(bucket: Bucket, topic: Topic) {
    bucket.addEventNotification(
        EventType.OBJECT_CREATED_PUT,
        SnsDestination(topic),
        NotificationKeyFilter.builder()
            .prefix(regionName)
            .build()
    )
    TopicPolicy.Builder.create(this, "TopicPolicy")
        .topics(listOf(topic))
        .build()
        .document
        .addStatements(
            PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(listOf(ServicePrincipal("s3.amazonaws.com")))
                .actions(listOf("sns:Publish"))
                .resources(listOf(topic.topicArn))
                .conditions(
                    mapOf(
                        "ArnLike" to mapOf(
                            "aws:SourceArn" to bucket.bucketArn,
                        )
                    )
                )
                .build()
        )
}

fun RegionalHealthCheckStack.newTopicOutputs(topic: Topic) {
    CfnOutput.Builder.create(this, "Topic")
        .value(topic.topicArn)
        .build()
}
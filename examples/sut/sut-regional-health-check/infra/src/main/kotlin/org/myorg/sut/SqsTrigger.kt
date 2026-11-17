package org.myorg.sut

import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.services.iam.Effect
import software.amazon.awscdk.services.iam.PolicyStatement
import software.amazon.awscdk.services.iam.ServicePrincipal
import software.amazon.awscdk.services.lambda.Function
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource
import software.amazon.awscdk.services.sns.CfnSubscription
import software.amazon.awscdk.services.sns.Topic
import software.amazon.awscdk.services.sqs.Queue
import software.amazon.awscdk.services.sqs.QueuePolicy

fun RegionalHealthCheckStack.newTriggerQueue(): Queue =
    Queue.Builder
        .create(this, "TriggerQueue")
        .queueName("${service}-${stage}-trigger")
        // Intentionally not setting KMS here, matching the Serverless config comment:
        // KmsMasterKeyId: alias/aws/sqs
        // https://stackoverflow.com/questions/63808647/aws-forward-event-bridge-event-to-encrypted-sqs-amazon-managed-key
        .build()

fun RegionalHealthCheckStack.allowTopicToSendMessagesToQueue(
    triggerQueue: Queue,
    topic: Topic,
) {
    QueuePolicy.Builder.create(this, "TriggerQueuePolicy")
        .queues(listOf(triggerQueue))
        .build()
        .document
        .addStatements(
            PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(listOf(ServicePrincipal("sns.amazonaws.com")))
                .actions(listOf("sqs:SendMessage"))
                .resources(listOf(triggerQueue.queueArn))
                .conditions(
                    mapOf(
                        "ArnEquals" to mapOf(
                            "aws:SourceArn" to topic.topicArn,
                        )
                    )
                )
                .build()
        )
}

fun RegionalHealthCheckStack.publishToQueue(
    topic: Topic,
    triggerQueue: Queue,
) {
    allowTopicToSendMessagesToQueue(
        triggerQueue = triggerQueue,
        topic = topic,
    )
    CfnSubscription.Builder.create(this, "TriggerSubscription")
        .protocol("sqs")
        // RawMessageDelivery intentionally omitted because it breaks fromSqsEvent.
        .endpoint(triggerQueue.queueArn)
        .topicArn(topic.topicArn)
        .build()
}

fun RegionalHealthCheckStack.newTriggerQueueOutputs(triggerQueue: Queue) {
    CfnOutput.Builder.create(this, "TriggerQueue")
        .value(triggerQueue.queueUrl)
        .build()
}

fun RegionalHealthCheckStack.consumeFromQueue(function: Function, queue: Queue) {
    function.addEventSource(
        SqsEventSource.Builder.create(queue)
            .batchSize(5)
            .build()
    )
}
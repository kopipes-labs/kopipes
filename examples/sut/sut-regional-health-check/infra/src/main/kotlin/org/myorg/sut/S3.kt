package org.myorg.sut

import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.Duration
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.iam.Effect
import software.amazon.awscdk.services.iam.IGrantable
import software.amazon.awscdk.services.iam.PolicyStatement
import software.amazon.awscdk.services.s3.Bucket
import software.amazon.awscdk.services.s3.BucketAccessControl
import software.amazon.awscdk.services.s3.BucketEncryption
import software.amazon.awscdk.services.s3.LifecycleRule

fun RegionalHealthCheckStack.newBucket(): Bucket {
    val bucket = Bucket.Builder.create(this, "Bucket")
        .bucketName("${org}-${service}-${stage}-${regionName}")
        .accessControl(BucketAccessControl.PRIVATE)
        .encryption(BucketEncryption.S3_MANAGED)
        .versioned(true)
        .lifecycleRules(
            listOf(
                LifecycleRule.builder()
                    .prefix("")
                    .enabled(true)
                    .expiration(Duration.days(92))
                    .build()
            )
        )
        .removalPolicy(RemovalPolicy.RETAIN)
        .build()

    return bucket
}

fun RegionalHealthCheckStack.grantAccessToBucket(
    grantable: IGrantable,
    bucket: Bucket,
) {
    grantable.grantPrincipal.addToPrincipalPolicy(
        PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(
                listOf(
                    "s3:PutObject",
                    "s3:PutObjectAcl",
                    "s3:GetObject",
                )
            )
            .resources(
                listOf(
                    bucket.arnForObjects("*"),
                )
            )
            .build()
    )
}

fun RegionalHealthCheckStack.newBucketOutputs(bucket: Bucket) {
    CfnOutput.Builder.create(this, "BucketName")
        .value(bucket.bucketName)
        .build()

    CfnOutput.Builder.create(this, "BucketArn")
        .value(bucket.bucketArn)
        .build()
}
# Envelope Encryption

The framework provides built-in support for **Envelope Encryption**, allowing you to protect sensitive data at the field level within your events and DynamoDB items. It uses AWS Key Management Service (KMS) to manage data keys and AES-GCM for symmetric data encryption.

## Why encrypt at the field level

Disk and database encryption protect against a stolen disk, not against anyone with access to the data: the cloud
decrypts it transparently for every authorized reader. Events make this sharper, because they are copied to every
consumer's store and kept forever in the event lake. *Software Architecture Patterns for Serverless Systems*
(Ch. 10 › *Redacting sensitive data*) therefore encrypts sensitive fields in the application, before an event leaves
its producer: the bus, the channels, the lake and every consumer that doesn't need the data hold only ciphertext.
Consumers that do need it decrypt on the way in.

## Overview

Envelope encryption is a practice where you encrypt your data with a data key, and then encrypt the data key with a master key managed by a service like AWS KMS.

In this framework:
1. A unique **Data Encryption Key (DEK)** is generated for each event or batch.
2. The DEK is used to encrypt specific fields in the event payload using **AES-GCM**.
3. The DEK itself is encrypted using an **AWS KMS Customer Master Key (CMK)** and stored alongside the encrypted data in the `eem` (Envelope Encryption Metadata) field.

## Envelope Encryption Metadata (eem)

The `eem` field in the `Event` interface stores the necessary information to decrypt the event:

```kotlin
@Serializable
data class EnvelopeEncryptionMetadata(
    val masterKeyAlias: String? = null,
    val dataKeys: Map<String, String>? = null, // region -> Base64 encrypted DEK
    val fields: List<String>? = null,           // list of encrypted fields
    val algorithm: String? = "AES/GCM/NoPadding"
)
```

### Multi-region Support

The `dataKeys` map allows storing the encrypted DEK for multiple AWS regions. This enables a service in one region to encrypt an event that can be decrypted by a service in another region, provided both regions have access to the same (or a replicated) KMS master key.

## Pipeline Integration

### Encrypting Events

You can encrypt specific fields of an event before publishing it by configuring the `CdcPipeline` or by using the
`encryptEvent` step. Both need KMS connectors: `context.kmsConnectors` (see [Wiring](Wiring.md)) uses the context's
KMS client factory, with the context's region as the region where data keys are generated.

#### Using CdcPipeline

```kotlin
CdcPipeline.builder()
    .id("publish")
    .context(context)                 // KMS connectors (and the DynamoDB client, for queries)
    .eventPublisher(context.eventPublisher)
    .toEvent(::toEvent)
    .encryptionOptions(
        EncryptionOptions(
            eem = EnvelopeEncryptionMetadata(
                masterKeyAlias = "alias/my-key",
                fields = listOf("email", "phoneNumber")
            ),
            regions = listOf("us-east-1", "eu-west-1")
        )
    )
    .build()
```

#### Using the step

```kotlin
val encrypt = EventEncryption.encryptEvent(
    options = EncryptionOptions(
        eem = EnvelopeEncryptionMetadata(
            masterKeyAlias = "alias/my-key",
            fields = listOf("sensitiveData")
        )
    ),
    fm = faultManager,
    kms = context.kmsConnectors,
)
val encryptedFlow = encrypt(flow)
```

### Decrypting Events

Incoming events can be automatically decrypted if they contain `eem` metadata.

#### Using CdcPipeline

Call `decrypt()` on the `CdcPipeline` builder to automatically decrypt incoming events.

```kotlin
CdcPipeline.builder()
    // ...
    .context(context)
    .decrypt()
    .build()
```

#### Using the step

```kotlin
val decryptedFlow = EventEncryption.decryptEvent(fm = faultManager, kms = context.kmsConnectors)(flow)
```

For DynamoDB Stream events (CDC), use `decryptChangeEvent`:

```kotlin
val decryptedFlow = EventEncryption.decryptChangeEvent(fm = faultManager, kms = context.kmsConnectors)(flow)
```

## Manual Encryption/Decryption

If you need to encrypt or decrypt data manually (e.g., when saving a custom object to DynamoDB), you can use the `EventEncryption` utilities.

### Encrypting DynamoDB Maps

```kotlin
val item: Map<String, AttributeValue> = // ...
val options = EncryptionOptions(
    eem = EnvelopeEncryptionMetadata(
        masterKeyAlias = "alias/my-key",
        fields = listOf("secret")
    )
)

val kms = context.kmsConnectors.forPipeline("manual")
val encryptedItem = EventEncryption.encryptData(item, options, kms)
// encryptedItem now contains an 'eem' field and the 'secret' field is encrypted
```

### Decrypting DynamoDB Maps

```kotlin
val decryptedItem = EventEncryption.decryptData(item, kms)
```

## Fault Handling

If decryption fails (e.g., due to missing KMS permissions or corrupted data), the framework will catch the exception and create a `FaultEvent`. The original, undecrypted event is preserved in `UnitOfWork.undecryptedEvent` for diagnostic purposes.

## Erasure (crypto-shredding)

Keeping every event forever conflicts with a person's right to have their data erased. The book's answer is
**crypto-shredding**: encrypt a person's data under a key specific to them, and delete that key on request. The
events stay in the lake, but the person's fields can no longer be read.

kopipes doesn't manage subject-specific keys. To build it, wrap each data key under a per-subject key (for example,
one KMS key or one stored key per customer), record which key in `eem`, and make an erasure request an event that
each service handles by deleting the keys it owns.

## Security Considerations

- **KMS Permissions**: Ensure your Lambda execution role has `kms:GenerateDataKey`, `kms:Encrypt`, and `kms:Decrypt` permissions for the specified CMK.
- **Key Rotation**: AWS KMS handles CMK rotation. Since a new DEK is generated for each encryption operation, your data remains secure even if a DEK were to be compromised.
- **Algorithm**: The framework uses AES-GCM with a 128-bit authentication tag and a 12-byte random IV, providing both confidentiality and integrity.

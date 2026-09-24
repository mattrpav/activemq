# BlobMessage Client API Touch-ups (Backlog)

Small `activemq-client` improvements identified while building the HYTE
BlobMessage HTTP transfer modules (`hyte-mq/http/` — JDK
HttpClient strategies + broker-hosted blob server). None are prerequisites:
`io.hyte.activemq.blob.http.HttpBlobTransferPolicy` works around all of them.
They are, however, cheap upstream-quality fixes and would simplify third-party
strategy implementations.

## Affected Classes

- `activemq-client/.../blob/BlobTransferPolicy.java`
- `activemq-client/.../command/ActiveMQBlobMessage.java`
- `activemq-client/.../BlobMessage.java`
- `activemq-client/.../blob/DefaultBlobDownloadStrategy.java` (observation)

## Items

### 1. `BlobTransferPolicy` has no `setDownloadStrategy(...)`

`setUploadStrategy(BlobUploadStrategy)` exists, but there is no symmetric
setter for the download strategy — `downloadStrategy` is only reachable via
the lazy `createDownloadStrategy()` factory. Plugging in a custom download
strategy therefore requires subclassing the policy, while a custom upload
strategy is a one-liner.

**Fix:** add `setDownloadStrategy(BlobDownloadStrategy)` mirroring the upload
setter.

### 2. `ActiveMQBlobMessage.deleteFile()` NPEs without a downloader

`getInputStream()` null-guards (`blobDownloader == null` → returns null), but
`deleteFile()` calls `blobDownloader.deleteFile(this)` unguarded. Any
producer-side instance, or a message not injected by a consumer, throws a raw
`NullPointerException` instead of a meaningful error.

**Fix:** null-guard with a clear `JMSException` (or no-op) consistent with
`getInputStream()`.

### 3. `deleteFile()` is not on the public `BlobMessage` interface

Consumers must downcast to `ActiveMQBlobMessage` to clean up a blob after
processing (`FilesystemBlobTest` does exactly this). Cleanup is half of the
blob lifecycle contract and belongs on the public interface.

**Fix:** add `void deleteFile() throws IOException, JMSException` to
`org.apache.activemq.BlobMessage`.

## Related observations (larger than touch-ups; decide separately)

- **`BlobTransferPolicy.copy()` discards subclasses.** It constructs
  `new BlobTransferPolicy()` and copies private fields, so any policy subclass
  silently loses its type on the first defensive copy
  (`ActiveMQConnectionFactory.createActiveMQConnection`, `BlobUploader`,
  `BlobDownloader` all copy). Subclasses must know to override `copy()` —
  undocumented and easy to miss. A protected copy-constructor pattern or a
  javadoc warning would help.
- **`DefaultBlobDownloadStrategy.deleteFile()` reconstructs the URL** from
  `uploadUrl + messageId` instead of using the message's stored
  `remoteBlobUrl` (`message.getURL()`), unlike `getInputStream()`. Deletes
  target the wrong location whenever the actual upload URL differs from the
  reconstruction (different policy config on the consumer, or servers using
  richer URL layouts). Using `getURL()` with the existing protocol/host/port
  validation would be strictly more correct.

## Context / prior art

The HYTE modules ship a working reference for all of the above:
`HttpBlobTransferPolicy` overrides `copy()` and the strategy factories, and
`JdkHttpBlobDownloadStrategy.deleteFile()` operates on `message.getURL()`
with the standard SSRF guard. See
`hyte-mq/http/client/src/main/java/io/hyte/activemq/blob/http/`.

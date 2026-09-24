# pendingSends Leak on Transaction Rollback

## Affected Class

- `activemq-broker/.../broker/region/Queue.java`

## Problem

The internal `pendingSends` counter on `Queue` leaks by +1 for every message
sent within a transaction that is subsequently rolled back. Over time this
permanently disables cursor caching for the affected queue, degrading
performance and amplifying the conditions that trigger `duplicateFromStore`
events.

## Root Cause

When a message is sent to a queue, `doMessageSend()` increments `pendingSends`
(line 901). If the send is within a transaction, a `CursorAddSync`
synchronization is registered on the transaction.

On **commit**, `CursorAddSync.afterCommit()` calls `messageSent()` which
decrements `pendingSends` (line 2025). On **store exception**,
`doMessageSend()` catches the error and decrements `pendingSends` (line 922).
But on **rollback**, `CursorAddSync.afterRollback()` called
`rollbackPendingCursorAdditions()` to clean up the ordered cursor update list
but never decremented `pendingSends`.

## Impact

`pendingSends` is used by `singlePendingSend()` (returns true when
`pendingSends <= 1`), which gates cursor caching via
`QueueStorePrefetch.canEnableCash()`. A leaked counter means
`singlePendingSend()` permanently returns false, which:

1. Disables cursor caching for the queue
2. Forces the cursor into store page-in mode
3. Increases the frequency of `recoverMessage()` calls from the KahaDB journal
4. Creates more opportunities for the cursor to detect duplicates via
   `recordUniqueId()`, triggering `duplicateFromStore` events

This makes the `pendingSends` leak a contributing factor to the queue size drift
bug documented in `duplicate-from-store-queue-size-drift.md`.

## Fix

Added `pendingSends.decrementAndGet()` to `CursorAddSync.afterRollback()`,
placed after the `rollbackPendingCursorAdditions()` call and before
`decrementReferenceCount()`. The decrement is outside the persistent-message
conditional so it covers both persistent and non-persistent transactional sends.

```java
@Override
public void afterRollback() throws Exception {
    if (store != null && messageContext.message.isPersistent()) {
        rollbackPendingCursorAdditions(messageContext.message.getMessageId());
    }
    pendingSends.decrementAndGet();
    messageContext.message.decrementReferenceCount();
}
```

## Test Coverage

- `DuplicateFromStoreQueueSizeDriftTest.testTransactionRollbackDoesNotLeakPendingSends`
  (new) — sends 5 messages in a transaction, rolls back, asserts
  `singlePendingSend()` returns true. Then sends 1 message in a new transaction,
  commits, and verifies both the message count and `singlePendingSend()` recover
  correctly.

## Files Changed

| File | Change |
|------|--------|
| `Queue.java` | Added `pendingSends.decrementAndGet()` in `CursorAddSync.afterRollback()` |
| `DuplicateFromStoreQueueSizeDriftTest.java` | Added transaction rollback test |

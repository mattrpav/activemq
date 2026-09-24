# Incorrect Queue Size from duplicateFromStore

> **SUPERSEDED (2026-09-18) — DO NOT IMPLEMENT.** The fix described below
> (decrementing `messages` inside `duplicateFromStore()`) is WRONG. Its premise
> is that the removed row is a separately-counted orphan; it is not. In both
> real cursor paths the row is single-counted and is still consumed and acked
> normally, so the blanket decrement double-counts the removal and drives the
> counter NEGATIVE.
>
> - Duplicate SEND (failover resend) is suppressed before `messageSent()` counts
>   it, so there is nothing to decrement.
> - Store RE-READ (`"cursor got duplicate from store"`, the `!cached` branch of
>   `AbstractStoreCursor.recoverMessage`) is a single-counted message read twice;
>   the inflight copy's ack already performs the one decrement.
>
> The Gen-1 test only "passed" because it called `queue.duplicateFromStore(...)`
> directly with a fabricated, never-consumed message. `DuplicateFromStoreEndToEndTest`
> (branch `dupe-size-drift`) drives the REAL cursor paths and fails with the
> decrement (negative queue size); it is kept as the regression pin against
> reintroduction.
>
> The real queue size inflation is diagnosed and fixed in
> [queuesize-deep-dive.md](queuesize-deep-dive.md): D1
> (`amq-tx-post-commit-drift`), D2 (`amq-expiry-remove-fail-drift`), D3
> (`amq-upstream-sync-async-dupe-race`). `duplicateFromStore()` must stay
> decrement-free, as `main` is today.
>
> Dead branches carrying this wrong fix, deleted 2026-09-18 (SHAs recorded for
> recovery): `amq-upstream-queue-size-drift` = 1691db9e03,
> `backup/dupe-from-store-pre-rework` = 6afb46186b.

## Affected Class

- `activemq-broker/.../broker/region/BaseDestination.java`

## Problem

The queue size metric (`queueSize`, backed by
`destinationStatistics.getMessages()`) can report a positive message count even
when the queue is empty and the journal correctly shows all messages were
dispatched and acknowledged.

The incorrect queue size correlates with `duplicateFromStore` events logged by the broker. When
the store cursor pages in messages from the KahaDB journal, it detects
duplicates (messages already dispatched but still in the store) via
`AbstractStoreCursor.recoverMessage()` / `recordUniqueId()`. Detected duplicates
are passed to `BaseDestination.duplicateFromStore()`, which removes them from
the persistent store by calling `acknowledge()` and optionally routes them to
the DLQ.

However, `duplicateFromStore()` never decremented
`destinationStatistics.getMessages()` or incremented
`destinationStatistics.getDequeues()`. Since the message was already counted
during queue initialization (from `store.getMessageCount()`), removing it from
the store without adjusting the in-memory counter permanently inflated the queue size by +1
per duplicate event.

## Root Cause

`BaseDestination.duplicateFromStore()` called `acknowledge()` to remove the
message from the store journal but did not update the destination statistics
counters. The two other code paths that remove messages from a queue
(`dropMessage()` for normal acks and `pagedInMessages.contains()` duplicate
check) both correctly adjust the counters.

## Fix

Added two counter updates to `BaseDestination.duplicateFromStore()`, immediately
after the existing `getDuplicateFromStore().increment()` call:

```java
destinationStatistics.getMessages().decrement();
destinationStatistics.getDequeues().increment();
```

This maintains the invariant `enqueues - dequeues == messages` through
duplicate-from-store processing.

## Test Coverage

- `SendDuplicateFromStoreToDLQTest` — updated existing assertions to expect the
  corrected counter values (-1 in the artificial test scenario where the message
  was already consumed before `duplicateFromStore` is called).

- `DuplicateFromStoreQueueSizeDriftTest` (new) — two tests:
  - `testDuplicateFromStoreDecrementsQueueSize`: sends 3 messages, fires 2
    duplicateFromStore events, verifies count goes from 3 to 1, then consumes
    the remaining message and verifies count reaches 0.
  - `testStatisticsConsistencyAfterDuplicateFromStore`: sends 5 messages, fires
    3 duplicateFromStore events, verifies the `enqueues - dequeues == messages`
    invariant holds.

## Files Changed

| File | Change |
|------|--------|
| `BaseDestination.java` | Added `getMessages().decrement()` and `getDequeues().increment()` in `duplicateFromStore()` |
| `SendDuplicateFromStoreToDLQTest.java` | Updated assertions for corrected counter behavior |
| `DuplicateFromStoreQueueSizeDriftTest.java` | New test class verifying queue size accuracy |

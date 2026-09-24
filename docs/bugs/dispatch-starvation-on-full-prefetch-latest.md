# Dispatch Starvation When dispatchPendingList Reaches maxPageSize

## Affected Class

- `activemq-broker/.../broker/region/Queue.java`

## Problem

`Queue.doPageInForDispatch()` gates page-in from the store with:

```java
if (toPageIn > 0 && (force || (haveRealConsumer() && pagedInPendingSize < maxPageSize)))
```

When `dispatchPendingList.size() >= maxPageSize`, no new messages are paged in
from the store, regardless of whether any consumer has available prefetch
capacity. This causes permanent starvation when the dispatch pending list
fills with messages that no active consumer can accept.

## Root Cause

The `dispatchPendingList` holds messages that have been paged in from the
store but could not be dispatched to any consumer. Messages remain in this
list when:

- No consumer's selector matches the message
- The message's group is owned by a stalled/full consumer
- All consumers are temporarily full

Once this list reaches `maxPageSize`, the page-in gate closes permanently
(for the non-force path). Deliverable messages that remain in the store
cannot be paged in, even though a consumer has available prefetch capacity
to receive them.

## Reproduction

Send a mix of messages to a queue: some matching a consumer's selector,
some not matching any consumer. With `maxPageSize=20` and a 1:4 ratio of
deliverable to undeliverable messages:

1. First page-in: 20 messages, ~4 deliverable → dispatched to consumer
2. ~16 undeliverable messages remain in dispatchPendingList
3. Next page-in: 4 messages → ~3 undeliverable added → list grows to ~19
4. After a few cycles: dispatchPendingList = 20 = maxPageSize
5. Gate blocks: `pagedInPendingSize < maxPageSize` → false
6. Consumer starves — deliverable messages stuck in store permanently

The test (`NetworkConsumerPriorityDispatchStarvationTest`) sends 200
interleaved messages (40 with `target='app'`, 160 with `target='other'`).
A consumer with selector `target = 'app'` receives only ~8 of 40 deliverable
messages before the dispatch pending list fills with undeliverable messages
and blocks all further page-in.

## Impact

- Consumers with available prefetch capacity receive no messages
- Deliverable messages remain permanently stuck in the store
- Queue size remains high even though consumers are idle
- The effect is permanent until the queue is purged or the broker restarted
- In network-of-brokers topologies, stalled network consumers with message
  group affinity or selector-based routing trigger this condition

## Fix

Add an alternative gate condition that allows page-in when consumers have
available capacity, and cap the page-in count to what consumers can absorb:

```java
if (pagedInPendingSize >= maxPageSize && !force) {
    toPageIn = Math.min(toPageIn, getConsumerMessageCountBeforeFull());
}

if (toPageIn > 0 && (force || (haveRealConsumer() &&
        (pagedInPendingSize < maxPageSize || getConsumerMessageCountBeforeFull() > 0)))) {
```

## Test

`NetworkConsumerPriorityDispatchStarvationTest` — sends 200 interleaved
messages (1:4 deliverable ratio), asserts a selective consumer receives all
40 deliverable messages within 10 seconds.

- Pre-fix: receives ~8, then permanent starvation (FAIL)
- Post-fix: receives all 40 within seconds (PASS)

## Files

| File | Reference |
|------|-----------|
| `Queue.java` | `doPageInForDispatch()` gate at ~line 2133 |
| `NetworkConsumerPriorityDispatchStarvationTest.java` | `testUndeliverableMessagesClogDispatchBufferAndBlockPageIn` |

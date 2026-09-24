# Network Bridge incrementOutstandingResponses Check-Then-Act Race

## Bug #7

## Affected Class

- `activemq-broker/.../network/DemandSubscription.java`

## Problem

`DemandSubscription.incrementOutstandingResponses()` (lines 150-157) has a
check-then-act race between the atomic increment and the `activeWaiter` check:

```java
dispatched.incrementAndGet();         // step 1: increment
if (activeWaiter.get()) {             // step 2: check waiter
    decrementOutstandingResponses();  // step 3: undo increment
    return false;
}
return true;
```

Between step 1 and step 2, a `waitForCompletion()` call on another thread can:

1. Set `activeWaiter = true`
2. See `dispatched > 0`
3. Enter `wait()`

Then this thread checks `activeWaiter` at step 2, sees `true`, decrements at
step 3, which notifies the waiter. But a different concurrent
`incrementOutstandingResponses()` call may have already passed step 2 (seeing
`activeWaiter = false` before the waiter set it), and its increment is now
invisible to the waiter. The waiter wakes up and exits, but `dispatched` is
still > 0 from the other thread's increment.

## Root Cause

The increment and the `activeWaiter` check are not performed atomically. The
`dispatched` counter and `activeWaiter` flag are two separate atomic variables
with no happens-before relationship enforcing their combined consistency.

## Impact

- `waitForCompletion()` can exit (via the 30-second timeout or a spurious
  notify) while `dispatched > 0`, meaning the subscription is removed while
  messages are still in flight
- In-flight messages are lost or produce unexpected errors on the remote broker
  when the subscription no longer exists
- More likely under high message throughput when multiple transport threads
  call `incrementOutstandingResponses()` concurrently with a bridge shutdown

## Status

**FIXED** on branch `amq-network-connector-fixes` — commit `beac0b3d51`
"Rework DemandSubscription outstanding-response protocol under one monitor"
(fixed together with backlog item NC-2, as prescribed).

## Test

`DemandSubscriptionOutstandingResponsesTest` (activemq-broker, committed with
the fix) proves the race family deterministically against the real class by
holding the wait monitor from the test thread to pin the waiter at monitor
entry — no sleeps, no spurious timing. Covers the missed notify, the
increment slip, post-waiter increment rejection, and prompt interrupt exit.
(An earlier isolated replica test, `NetworkConnectorBugTests.testBug7`, was
superseded by this real-class test and deleted.)

## Files

| File | Reference |
|------|-----------|
| `DemandSubscription.java` | `waitForCompletion()` / `incrementOutstandingResponses()` / `decrementOutstandingResponses()` under `dispatchedLock` |
| `DemandSubscriptionOutstandingResponsesTest.java` | deterministic real-class regression tests |

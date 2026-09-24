# Network Bridge Unsynchronized demandConsumerDispatched Counter

## Bug #4

## Affected Class

- `activemq-broker/.../network/DemandForwardingBridgeSupport.java`

## Problem

The `demandConsumerDispatched` field (line 128) is a plain `int` — not
`volatile`, not `AtomicInteger` — that is read and written from transport
threads without synchronization. It is incremented in `ackAdvisory()` (lines
922-937) and compared against a threshold to determine when to send advisory
acknowledgments.

Concurrent read-modify-write operations on a plain `int` from multiple threads
cause lost updates: two threads can both read the same value, increment locally,
and write back, resulting in only one effective increment.

## Root Cause

The field declaration at line 128:

```java
private int demandConsumerDispatched;
```

Should be an `AtomicInteger` with `incrementAndGet()` and `compareAndSet()` for
the reset logic at the threshold boundary.

## Impact

- Advisory acknowledgment frequency drifts from the configured
  `advisoryAckPercentage` — acknowledgments may fire too often or too rarely
- Under high concurrency, the counter can wrap or stall, causing advisory
  backpressure issues
- The threshold comparison (`demandConsumerDispatched > threshold`) can read a
  stale value, leading to missed resets

## Status

**FIXED** on branch `amq-network-connector-fixes` — commit `8d80f9a2a2`
"Fix advisory dispatch counter races with AtomicInteger claim semantics".
The CAS claims the whole observed count so every counted dispatch is acked
exactly once; ack lag is bounded by one message.

## Test

`AckAdvisoryConcurrencyTest` (activemq-broker, committed with the fix) drives
the REAL `ackAdvisory()` on a `DemandForwardingBridge` with recording stub
transports: an 8-thread × 100k storm asserts total acked + residual counter
== total dispatched. Pre-fix the plain-int counter over-acked ~45% (800k
dispatches acked as ~1.16M). (An earlier isolated replica test,
`NetworkConnectorBugTests.testBug4`, was superseded by this real-class test
and deleted.)

## Files

| File | Reference |
|------|-----------|
| `DemandForwardingBridgeSupport.java` | `demandConsumerDispatched` AtomicInteger, `ackAdvisory()` ~line 943 |
| `AckAdvisoryConcurrencyTest.java` | real-class concurrency regression test |

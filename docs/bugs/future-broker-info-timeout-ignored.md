# FutureBrokerInfo.get(timeout) Ignores Timeout

## Bug #1

## Status

**FIXED** on branch `amq-network-connector-fixes` — commit `7311a15f42`
"Fix FutureBrokerInfo.get(timeout) to honor its timeout". The companion
test commit widens `FutureBrokerInfo` to package-private and converts
`FutureBrokerInfoTimeoutTest` to drive the real inner class (previously it
tested a replica of the buggy logic).

## Affected Class

- `activemq-broker/.../network/DemandForwardingBridgeSupport.java`
  (inner class `FutureBrokerInfo`)

## Problem

`FutureBrokerInfo.get(long timeout, TimeUnit unit)` does not respect its timeout
parameter. The method blocks indefinitely until either `disposed` is set to
`true` or the broker info is provided, completely ignoring the caller's
requested timeout.

## Root Cause

The while-loop condition on line 2015 uses `||` (OR) instead of `&&` (AND):

```java
// BUG: || should be &&
while (!disposed.get() || System.currentTimeMillis() - deadline < 0) {
```

With `||`, the loop continues whenever `!disposed` is true — which is the
normal state. The deadline check on the right side of the `||` is only evaluated
when `disposed` is `true`, at which point the loop should exit anyway. The
deadline is effectively dead code.

The correct condition is:

```java
// FIX: && — exit when EITHER disposed becomes true OR deadline passes
while (!disposed.get() && System.currentTimeMillis() - deadline < 0) {
```

## Impact

- Callers that expect a bounded wait (e.g., bridge startup handshake with a
  timeout) block indefinitely when the remote broker is unreachable
- Bridge startup threads hang until the bridge is explicitly disposed, tying up
  thread pool resources
- The `FutureBrokerInfo` class implements `Future<BrokerInfo>`, but the timed
  `get()` method violates the `Future.get(timeout, unit)` contract, which
  requires throwing `TimeoutException` if the result is not available within
  the specified time

## Test

`FutureBrokerInfoTimeoutTest` contains four tests:

- `testBuggyGetTimedDoesNotRespectTimeout` — calls `get(200ms)` on the buggy
  implementation and verifies the thread is still blocked after 1 second
- `testFixedGetTimedRespectsTimeout` — calls `get(200ms)` on the fixed
  implementation and verifies `TimeoutException` is thrown within ~200ms
- `testBothVersionsReturnWhenInfoIsSet` — confirms both versions work correctly
  on the happy path (info set before timeout)
- `testBuggyVersionBlocksIndefinitelyUntilDisposed` — proves the buggy version
  only unblocks when `disposed` is set, not when the deadline passes

## Files

| File | Reference |
|------|-----------|
| `DemandForwardingBridgeSupport.java` | Inner class `FutureBrokerInfo`, `get(long, TimeUnit)` ~line 2015 |
| `FutureBrokerInfoTimeoutTest.java` | All four test methods |

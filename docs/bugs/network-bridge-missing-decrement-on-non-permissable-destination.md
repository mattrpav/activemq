# Network Bridge Missing Decrement on Non-Permissable Destination

## Bug #2

**Status: FIXED** on branch `amq-network-connector-fixes` — commit
`8bafe3d945` "Ack non-permissible dispatches so excluded traffic cannot
wedge a bridge" (backlog item NC-1).

**HARDENED** (follow-up commits `0f439c856a`/`1bf7b6723f`/`1b177e7cde`):
excluded destinations are now never dispatched to network demand subs at
all - permissibility is composed into the demand subscription's
NetworkBridgeFilter and evaluated by the local broker before dispatch, so
excluded messages remain on the local queue for local consumers (no loss).
The forward-time ack became a policy-driven fallback
(`wildcardExcludePolicy`: ack [default] | dlq) for dispatches the filter
could not prevent.

## Affected Class

- `activemq-broker/.../network/DemandForwardingBridgeSupport.java`

## Problem

In `DemandForwardingBridgeSupport.serviceLocalCommand()`, when a message
dispatch is processed for forwarding across the network bridge, the code
calls `sub.incrementOutstandingResponses()` to track the in-flight message.
If `suppressMessageDispatch()` returns false and `isPermissableDestination()`
also returns false, the code falls through without calling
`sub.decrementOutstandingResponses()` or sending an acknowledgment back to the
local broker.

This leaks the `dispatched` counter on `DemandSubscription` by +1 for each
non-permissable message. The leaked counter causes `waitForCompletion()` to
block for its full 30-second timeout during bridge shutdown or subscription
removal, because the counter never reaches zero.

## Root Cause

The early-exit path for non-permissable destinations (around line 1254 of
`serviceLocalCommand()`) does not mirror the cleanup performed by other
early-exit paths (such as when `suppressMessageDispatch()` returns true), which
correctly decrement the counter and send an ack.

## Impact

- Bridge shutdown delays of up to 30 seconds per leaked counter increment
- Subscription removal hangs during network topology changes
- Under sustained traffic to non-permissable destinations, the counter grows
  unboundedly

## Test

`ExcludedDestinationPrefetchExhaustionTest` (activemq-unit-tests, committed
with the fix) reproduces the full production symptom against two real
brokers: 15 excluded-destination dispatches wedge a prefetch-10 wildcard
network subscription, and an included message never arrives until the fix
acks the excluded dispatches. (An earlier isolated replica test,
`NetworkConnectorBugTests.testBug2`, was superseded by this real-class test
and deleted.)

## Files

| File | Reference |
|------|-----------|
| `DemandForwardingBridgeSupport.java` | `serviceLocalCommand()` non-permissable else branch |
| `ExcludedDestinationPrefetchExhaustionTest.java` | two-broker reproduction + regression test |

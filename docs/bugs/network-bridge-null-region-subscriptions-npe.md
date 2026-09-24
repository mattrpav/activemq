# Network Bridge NPE from Null Region Subscriptions

## Bug #3

## Affected Class

- `activemq-broker/.../network/DemandForwardingBridgeSupport.java`

## Problem

`duplicateSuppressionIsRequired()` (around line 1555) calls
`getRegionSubscriptions(dest)` and iterates the result with a for-each loop.
`getRegionSubscriptions()` returns `null` when the broker region is not an
`AbstractRegion` (line 1646), which causes a `NullPointerException` on the
for-each iteration.

## Root Cause

`getRegionSubscriptions()` (lines 1622-1650) attempts to cast the region to
`AbstractRegion` and call `getSubscriptions()`. When the region is not an
`AbstractRegion` (e.g., a custom region implementation or a region wrapper), the
method returns `null`. The caller does not check for null before iterating.

## Impact

- `NullPointerException` thrown during message forwarding decisions
- Bridge error handling kicks in, potentially restarting the bridge
- Affects deployments using custom region implementations or intercepting
  broker plugins that wrap the region

## Fix

Add a null check before the for-each loop:

```java
Collection<Subscription> currentSubs = getRegionSubscriptions(dest);
if (currentSubs != null) {
    for (Subscription sub : currentSubs) {
        // ...
    }
}
```

Alternatively, `getRegionSubscriptions()` should return an empty collection
instead of null.

## Status

**FIXED** on branch `amq-network-connector-fixes` — commit `53877d42a8`
"Guard against null region subscriptions in duplicate suppression".
The for-each in `duplicateSuppressionIsRequired()` is wrapped in a null
check; `getRegionSubscriptions()`'s contract is unchanged (still returns
null for non-`AbstractRegion` regions, protecting external subclasses).

## Test

No dedicated runtime test: exercising the guard requires installing a
custom non-`AbstractRegion` region implementation, which is not worth the
harness. The trivial guard is verified by inspection and the suppression
regression suite (NoDuplicateOnTopicNetworkTest, virtual-dest and
wildcard-included two-broker tests). (The earlier replica test
`NetworkConnectorBugTests.testBug3` only demonstrated that for-each over
null throws NPE; it was deleted as superseded.)

## Files

| File | Reference |
|------|-----------|
| `DemandForwardingBridgeSupport.java` | `duplicateSuppressionIsRequired()` null guard; `getRegionSubscriptions()` ~line 1653 |

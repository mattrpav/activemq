# Discovery Connector Failed Bridge Left in Map

## Bug #6

## Affected Class

- `activemq-broker/.../network/DiscoveryNetworkConnector.java`

## Problem

In `DiscoveryNetworkConnector.onServiceAdd()` (lines 160-177), when a new
remote broker is discovered, the connector creates a `NetworkBridge`, adds it to
the `bridges` map, and then calls `bridge.start()`. If `start()` throws an
exception, the catch block disposes of transports and calls
`discoveryAgent.serviceFailed()`, but it never removes the bridge from the
`bridges` map.

```java
synchronized (bridges) {
    bridges.put(uri, bridge);   // line 162-165
}
bridge.start();                  // line 166 — if this throws...
// ... catch block does NOT call bridges.remove(uri)
```

## Root Cause

The `bridges.put()` is performed before `bridge.start()`, and the catch block
for `start()` failures does not include a compensating `bridges.remove()`.

## Impact

- A dead bridge entry in the map blocks reconnection to the same remote broker:
  subsequent discovery events for the same URI see the existing map entry and
  skip creating a new bridge
- The `activeEvents` guard may also prevent re-entry, compounding the problem
- In a network of brokers with intermittent connectivity, a single failed start
  can permanently prevent a bridge from being established to that remote broker
  until the local broker is restarted

## Fix

Add `bridges.remove(uri)` in the catch block after `start()` fails:

```java
try {
    bridge.start();
} catch (Exception e) {
    bridges.remove(uri);  // clean up the dead entry
    // existing dispose/serviceFailed logic
}
```

## Status

**FIXED** on branch `amq-network-connector-fixes` — commit `557ec62032`
"Clean up discovery connector bookkeeping directly at failure sites"
(fixed together with backlog item NC-4, as prescribed; uses value-guarded
`bridges.remove(uri, bridge)` rather than the plain remove sketched above so
a racing newer bridge is never evicted).

## Test

`DiscoveryFailurePathCleanupTest` (activemq-broker, committed with the fix)
drives the REAL `DiscoveryNetworkConnector` with a `setBridgeFactory` bridge
that throws on `start()` and a stub discovery agent whose `serviceFailed`
no-ops/throws: asserts the failed bridge leaves the `bridges` map, the
`activeEvents` reservation is released, and a second discovery event for the
same URI is accepted. (An earlier isolated replica test,
`NetworkConnectorBugTests.testBug6`, was superseded by this real-class test
and deleted.)

## Files

| File | Reference |
|------|-----------|
| `DiscoveryNetworkConnector.java` | `onServiceAdd()` failure paths + `DiscoverNetworkBridgeListener.bridgeFailed()` |
| `DiscoveryFailurePathCleanupTest.java` | real-class failure-path regression tests |

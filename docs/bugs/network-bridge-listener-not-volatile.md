# Network Bridge Listener Field Not Volatile

## Bug #5

## Affected Class

- `activemq-broker/.../network/DemandForwardingBridgeSupport.java`

## Problem

The `networkBridgeListener` field is declared as a plain (non-volatile)
reference:

```java
private NetworkBridgeListener networkBridgeListener;
```

This field is set by the thread that configures the bridge (via
`setNetworkBridgeListener()`) and read by transport listener threads that use
the local-variable snapshot pattern:

```java
NetworkBridgeListener l = this.networkBridgeListener;
if (l != null) { l.onStart(this); }
```

Without `volatile`, the Java Memory Model does not guarantee that the write
from the setup thread is visible to the transport threads. On architectures
with weak memory ordering (ARM, POWER), reader threads may cache the `null`
initial value indefinitely.

## Root Cause

The field lacks `volatile` and there is no other happens-before relationship
(no synchronized block, no lock, no concurrent data structure) that would
guarantee visibility between the writer thread and the reader threads.

## Impact

- On weakly-ordered architectures (ARM-based servers, Apple Silicon), transport
  threads may never see the listener, silently skipping `onStart()`,
  `onStop()`, and `bridgeFailed()` callbacks
- On x86 (strong memory ordering), the bug rarely manifests but is still
  technically a JMM violation — future JVM optimizations or JIT reorderings
  could surface it
- Missing `bridgeFailed()` callbacks mean the network connector does not learn
  about bridge failures, preventing reconnection

## Fix

Declare the field as `volatile`:

```java
private volatile NetworkBridgeListener networkBridgeListener;
```

## Status

**FIXED** on branch `amq-network-connector-fixes` — commit `6e261c7bc1`
"Make networkBridgeListener volatile for safe cross-thread publication".
All five read sites already used the local-snapshot pattern; `volatile`
completes it.

## Test

None — a JMM visibility defect of this shape is not deterministically
observable from a test on x86 hardware. Verified by inspection of the field
declaration and every read/write site. (The earlier replica test
`NetworkConnectorBugTests.testBug5` only documented the unsafe pattern; it
was deleted as superseded.)

## Files

| File | Reference |
|------|-----------|
| `DemandForwardingBridgeSupport.java` | `volatile NetworkBridgeListener networkBridgeListener` |

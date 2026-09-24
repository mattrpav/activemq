# KahaDB ackMessageFileMap Concurrent Access Race

## Affected Class

- `activemq-kahadb-store/.../store/kahadb/MessageDatabase.java`

## Problem

The `recordAckMessageReferenceLocation()` method accesses
`metadata.ackMessageFileMap` — a `HashMap` — without synchronization. This map
tracks which journal data files contain messages referenced by ack entries in
other data files, and is used during garbage collection to determine which data
files can be reclaimed.

Multiple threads can call `updateIndex()` concurrently (e.g., during message
acknowledgment across different destinations), each of which calls
`recordAckMessageReferenceLocation()`. Concurrent `get()`, `put()`, and `add()`
operations on an unsynchronized `HashMap` and its value `HashSet` can lead to:

- Lost entries (a `put()` overwriting another thread's `put()`)
- Corrupted internal state of the `HashMap` (infinite loops in bucket chains on
  older JVMs, or lost entries on newer JVMs)
- Missing file ID references causing premature data file reclamation during GC,
  which could result in message loss

## Fix

Wrapped the entire read-modify-write sequence in
`recordAckMessageReferenceLocation()` with `synchronized (metadata.ackMessageFileMap)`.
This serializes access to both the map and its value sets.

```java
protected void recordAckMessageReferenceLocation(Location ackLocation, Location messageLocation) {
    synchronized (metadata.ackMessageFileMap) {
        Set<Integer> referenceFileIds = metadata.ackMessageFileMap.get(ackLocation.getDataFileId());
        if (referenceFileIds == null) {
            referenceFileIds = new HashSet<>();
            referenceFileIds.add(messageLocation.getDataFileId());
            metadata.ackMessageFileMap.put(ackLocation.getDataFileId(), referenceFileIds);
            metadata.ackMessageFileMapDirtyFlag.lazySet(true);
        } else {
            Integer id = messageLocation.getDataFileId();
            if (!referenceFileIds.contains(id)) {
                referenceFileIds.add(id);
            }
        }
    }
}
```

The method visibility was also changed from `private` to `protected` as part of
the #2140 extensibility changes (allowing `KahaDBStore` subclasses to override
index update behavior).

## Files Changed

| File | Change |
|------|--------|
| `MessageDatabase.java` | Added `synchronized` block in `recordAckMessageReferenceLocation()`, changed visibility to `protected` |

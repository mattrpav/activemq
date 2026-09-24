# Shared Topic Subscriptions — Implementation Status

Jakarta Messaging 3.1 shared topic subscription support for ActiveMQ.
See `docs/jms/shared-subscriptions.md` for the full analysis and design.

## OpenWire v13 Protocol Additions

All v13 protocol changes live in the HYTE `activemq-client` module as overlays
and new classes — no upstream ActiveMQ modifications required.

### Commands

| Command | Class | Change | Status |
|---------|-------|--------|--------|
| `ConsumerInfo` | `SharedConsumerInfo extends ConsumerInfo` | adds `boolean shared`, `boolean durable` | Done |
| `SubscriptionInfo` | `SharedSubscriptionInfo extends SubscriptionInfo` | adds `boolean shared` | Done |
| `ExceptionResponse` | overlay of upstream | adds `String errorCode` (JMS 3.1 error codes) | Done |
| `Message` | overlay of upstream | adds `long deliveryTime` getter/setter (field exists upstream, no accessor) | Done |

### Marshallers (all in `org.apache.activemq.openwire.v13`)

| Marshaller | Extends | Status |
|------------|---------|--------|
| `ConsumerInfoMarshaller` | v12 | Done |
| `SubscriptionInfoMarshaller` | v12 | Done |
| `ExceptionResponseMarshaller` | v12 | Done |
| `MessageMarshaller` | v12 | Done |
| `ActiveMQMessageMarshaller` | v13 `MessageMarshaller` | Done |
| `ActiveMQTextMessageMarshaller` | v13 `ActiveMQMessageMarshaller` | Done |
| `ActiveMQBytesMessageMarshaller` | v13 `ActiveMQMessageMarshaller` | Done |
| `ActiveMQMapMessageMarshaller` | v13 `ActiveMQMessageMarshaller` | Done |
| `ActiveMQStreamMessageMarshaller` | v13 `ActiveMQMessageMarshaller` | Done |
| `ActiveMQObjectMessageMarshaller` | v13 `ActiveMQMessageMarshaller` | Done |
| `ActiveMQBlobMessageMarshaller` | v13 `ActiveMQMessageMarshaller` | Done |
| `MarshallerFactory` | — | Done (registers all above) |

### ExceptionResponse Error Code Flow

The `ExceptionResponse.errorCode` field carries JMS error codes over the wire.
On the client side, `getException()` lazily reconstructs the `JMSException`
with the error code applied via the `(String message, String errorCode)`
constructor, preserving the exception subtype. No changes to
`ActiveMQConnection` or `JMSExceptionSupport` required — transparent through
existing `getException()` calls.

Broker-side code must populate `errorCode` when creating `ExceptionResponse`
for JMS spec violations.

### Message DeliveryTime

`Message.deliveryTime` field already existed upstream (declared, copied in
`copy()`) but had no public accessor and was never marshalled. The hyte
overlay adds `getDeliveryTime()`/`setDeliveryTime()` with
`@openwire:property version=13`. The v13 `MessageMarshaller` appends
`deliveryTime` after all v12 fields. All concrete message marshallers
(Text, Bytes, Map, Stream, Object, Blob) inherit the new field through
the v13 `MessageMarshaller` base class.

Client-side `ActiveMQMessageProducerSupport.setDeliveryDelay()` currently
throws `UnsupportedOperationException` — needs implementation to compute
`deliveryTime = sendTime + deliveryDelay` and set it on the message before
send. Broker-side needs scheduling logic to hold messages until
`deliveryTime` elapses.

---

## Broker — Shared Subscriptions

### Done

| Component | File | Notes |
|-----------|------|-------|
| `SharedDurableTopicSubscription` | `io.hyte.activemq.broker.region` | Queue-style dispatch across N consumers, round-robin with prefetch skip, ack routing via `dispatchedTo` map, requeue on consumer removal, `storedKey` pins `SubscriptionKey` at creation |
| `SharedTopicSubscription` | `io.hyte.activemq.broker.region` | Non-durable shared dispatch, extends `PrefetchSubscription` directly, in-memory cursor, destroyed on last consumer detach |
| `SharedTopicRegion` | `io.hyte.activemq.broker.region` | Extends `TopicRegion`, routes shared durable and non-durable consumers, restore path, `addSharedDurableConsumer`, `addSharedNonDurableConsumer`, `removeSubscription`, type-conflict guards |
| `SharedTopicBrokerService` | `io.hyte.activemq.broker` | Installs `SharedTopicRegion`, sets `storeOpenWireVersion=13`, JMX support via `ManagedSharedTopicRegion` |
| `ManagedSharedTopicRegion` | `io.hyte.activemq.broker.jmx` | JMX-enabled `SharedTopicRegion` |
| `topicSubscriptionConversionEnabled` | `SharedTopicBrokerService` config flag | Allows automatic shared/unshared durable sub promotion (default: false = throw JMSException per spec) |
| KahaDB persistence | `SharedTopicBrokerService` sets `storeOpenWireVersion=13` | v13 marshallers create `SharedSubscriptionInfo` on deserialization, shared flag survives broker restart |
| Stale restore consumer fix | `SharedDurableTopicSubscription.addConsumer()` | Clears null-connection consumers from restore path before adding real consumer |

### Key Design Decisions

- **`storedKey`**: `SharedDurableTopicSubscription` pins its `SubscriptionKey` at
  creation time via a `final` field and overrides `getSubscriptionKey()`. This
  prevents the key from drifting when `this.context`/`this.info` are swapped
  during consumer attach/detach.

- **Stale consumer cleanup**: The constructor always adds a `ConsumerState` for
  the initial context. During broker restore, this context has `connection = null`.
  `addConsumer()` clears the consumer list when `!isActive() && !consumers.isEmpty()`
  before adding the real consumer.

- **Store version**: `PROTOCOL_STORE_VERSION = 11` upstream. `SharedTopicBrokerService`
  sets it to `13` so KahaDB uses v13 marshallers that create `SharedSubscriptionInfo`
  on unmarshal. Without this, the `shared` flag is lost on broker restart.

---

## Tests

21 JMS integration tests in `SharedSubscriptionJmsTest`:

| # | Test | Type | Status |
|---|------|------|--------|
| 1 | `testSharedDurableCreateAndReceive` | durable | Pass |
| 2 | `testSharedDurableMultipleConsumersRoundRobin` | durable | Pass |
| 3 | `testSharedDurableReconnectReceivesBuffered` | durable | Pass |
| 4 | `testSharedDurableWithSelector` | durable | Pass |
| 5 | `testSharedNonDurableCreateAndReceive` | non-durable | Pass |
| 6 | `testSharedNonDurableCleanupOnDisconnect` | non-durable | Pass |
| 7 | `testSharedNonDurableMultipleConsumersRoundRobin` | non-durable | Pass |
| 8 | `testSharedNonDurableWithSelector` | non-durable | Pass |
| 9 | `testSharedNonDurableConsumerLeaveRebalance` | non-durable | Pass |
| 10 | `testSelectorMismatchOnJoinThrows` | validation | Pass |
| 11 | `testSharedToUnsharedTypeConflictThrows` | validation | Pass |
| 12 | `testUnsharedToSharedTypeConflictThrows` | validation | Pass |
| 13 | `testSharedDurableWithoutClientId` | durable | Pass |
| 14 | `testSharedDurableUnsubscribeWhenInactive` | durable | Pass |
| 15 | `testSharedDurableUnsubscribeWithActiveConsumerThrows` | durable | Pass |
| 16 | `testSharedDurableClientAcknowledge` | durable | Pass |
| 17 | `testSharedDurableDupsOkAcknowledge` | durable | Pass |
| 18 | `testConversionUnsharedToSharedWhenEnabled` | conversion | Pass |
| 19 | `testConversionSharedToUnsharedWhenEnabled` | conversion | Pass |
| 20 | `testSharedDurablePersistsAcrossRestart` | durable | Pass |
| 21 | `testSharedNonDurableDoesNotPersistAcrossRestart` | non-durable | Pass |

32 bridge tests: 16 `TopicBridgeDemandForwardingTest` + 16 `TopicConsumerAdvisoryTest`

14 unit tests in `SharedTopicSubscriptionTest` (non-durable dispatch mechanics)

---

## Remaining Work

### High Priority — Core Functionality

- [x] **Shared non-durable subscriptions** (done)
  - `SharedTopicSubscription` — in-memory cursor, extends `PrefetchSubscription`, destroyed on last consumer detach
  - `SharedTopicRegion` routing via `addSharedNonDurableConsumer` / `removeSharedNonDurableConsumer`
  - 6 JMS integration tests + 14 unit tests

- [x] **Client-side API** (done)
  - `SharedTopicSession` overrides `createSharedConsumer` and `createSharedDurableConsumer` (all 4 overloads)
  - `SharedTopicConnectionFactory` wires `SharedTopicSession` into connections
  - Builds `SharedConsumerInfo` with appropriate flags

- [ ] **Broker error code population**
  - Set `ExceptionResponse.errorCode` on JMS spec violations (selector mismatch, noLocal on shared, type conflict, etc.)

### Medium Priority — Robustness

- [ ] **KahaDB `LastAck` max-tracking**
  - Use `max(current, sequence)` instead of blind overwrite to prevent recovery cursor going backward under out-of-order shared dispatch acks
  - Not a correctness issue (ackPositions filters correctly) but affects restart recovery efficiency

- [ ] **`deliveryDelay` client implementation**
  - Override `setDeliveryDelay()`/`getDeliveryDelay()` in HYTE's `ActiveMQMessageProducerSupport`
  - Compute `message.setDeliveryTime(System.currentTimeMillis() + deliveryDelay)` before send
  - Broker-side scheduling: hold message dispatch until `deliveryTime` elapses

- [ ] **Selector validation**
  - Broker enforces all consumers in a shared subscription use the same selector
  - Currently guarded in tests but enforcement path needs verification

### Lower Priority — Production Readiness

- [x] **Advisory messages** (done — no changes needed)
  - `AdvisoryBroker` sits above the region in the broker interceptor chain and fires consumer add/remove advisories for every `addConsumer`/`removeConsumer` call unconditionally. Shared subscriptions inherit this behavior with no special handling — each consumer join/leave fires its own advisory, consistent with standard topic and queue behavior.
  - 16-case parameterized `TopicConsumerAdvisoryTest` covers all combinations of `brokerAdvisory × durable × shared × multiple`

- [x] **Network bridge — demand forwarding** (done)
  - Unique subscription names per remote consumer in `DemandForwardingBridgeSupport.createDemandSubscription()`: `NC-DS_<broker>_<destination>_<consumerId>` prevents durable identity collisions when multiple shared consumers share the same subscription name
  - `SharedTopicRegion.addConsumer()` bypasses shared consolidation for network subscriptions (`networkConsumerIds` guard) — no-op under v12, active under v13
  - All 16 `TopicBridgeDemandForwardingTest` + 16 `TopicConsumerAdvisoryTest` pass

- [x] **Network bridge — v13 advisory class check** (done)
  - `DemandForwardingBridgeSupport.serviceRemoteConsumerAdvisory()` line 943: changed `data.getClass() == ConsumerInfo.class` to `data instanceof ConsumerInfo` so `SharedConsumerInfo` advisories are accepted under v13

- [ ] **JDBC persistence** (backlogged)
  - Relax `CLIENT_ID NOT NULL` constraint
  - Add `SHARED` column
  - Same `LastAck` fix as KahaDB

- [ ] **xbean/Spring XML configuration**
  - Schema generation for extension classes
  - Example `activemq.xml` configuration

- [ ] **`stackTraceEnabled` default**
  - Consider defaulting to `false` in `OpenWireFormatFactory` for bandwidth and security
  - Stack traces serve no functional purpose — client only inspects exception type and message

- [ ] **Dispatch to consumers on unstarted connections**
  - `RoundRobinDispatchPolicy` / shared-subscription round-robin dispatches to all registered
    consumers regardless of connection start state
  - JMS spec: message delivery should not occur until `connection.start()` is called
  - Root cause in `PrefetchSubscription.dispatch()` → `context.getConnection().dispatchAsync(md)` — no guard on connection start state
  - Fix: broker-side dispatch (subscription / dispatch policy) should skip consumers whose connection has not sent START, or exclude them as round-robin targets until started
  - **Confirmed by isolated repro (2026-07-17):** a shared durable subscription with two
    consumers, the first on an *unstarted* connection, strands the round-robined message in
    the unstarted consumer's client-side prefetch buffer (FifoMessageDispatchChannel is not
    running until start()); a blocking `receive()` on the started consumer never returns.
  - Affected TCK test (excluded in `ts.jtx` as workaround): `core20/jmsconsumertests#queueReceiveTests`
  - Not shared-subscription-specific — general ActiveMQ dispatch behavior that shared
    subscriptions inherit through round-robin

- [ ] **Shared durable subscription + prefetch=0 (pull consumer) hangs on receive()**
  - A shared durable consumer with prefetch 0 becomes a pull consumer; `receive(timeout)`
    should send a MessagePull and return a message or null, but for shared durable
    subscriptions it never returns (the pull path does not retrieve the queued match).
  - **This was the actual cause of the `createSharedDurableConsumerTopicTest2` hang** that
    stalled the tail of the full TCK run — NOT the unstarted-connection issue above. The
    TCK factory's `prefetchPolicy.setAll(0)` left `durableTopicPrefetch` at 0, and shared
    durable consumers read that value. Confirmed by A/B repro (2026-07-17): identical
    structure (both consumers on one started connection, selector, 3 messages) hangs at
    `durableTopicPrefetch=0`, passes at `durableTopicPrefetch=1`.
  - **TCK worked around** by setting `durableTopicPrefetch=1` and
    `optimizeDurableTopicPrefetch=1` in `JNDIInitialContextFactory` (also satisfies async
    consumers, which reject prefetch 0). Test now passes; no `ts.jtx` exclusion needed.
  - Underlying product fix (deferred): make the prefetch=0 pull path work for shared durable
    subscriptions in `SharedDurableTopicSubscription`.

---

## Known Limitations — Protocol Backward Compatibility

OpenWire version negotiation uses `min(local, remote)`. When a v13 broker
connects to a v12 broker (or any pre-v13 client), the wire protocol falls
back to v12. This has the following effects on shared subscription support:

### SharedConsumerInfo fields lost on v12 wire

`SharedConsumerInfo` does not declare its own `DATA_STRUCTURE_TYPE` — it shares
`ConsumerInfo`'s type byte. The v12 marshaller's `createObject()` returns
plain `ConsumerInfo`, so the `shared`, `durable`, and `userSpecifiedClientId`
fields are silently dropped during deserialization on the v12 side.

**Impact:** `ConsumerInfo.isDurable()` falls back to `subscriptionName != null`,
so every shared consumer with a subscription name (both durable *and*
non-durable) is treated as durable. The shared flag is completely lost —
the v12 broker creates standard non-shared durable subscriptions.

**Mitigation:** The unique subscription name fix in
`DemandForwardingBridgeSupport.createDemandSubscription()` generates a
per-consumer name (`NC-DS_<broker>_<destination>_<consumerId>`) *before*
the `ConsumerInfo` hits the wire. This prevents durable identity collisions
regardless of negotiated wire version. Non-durable shared consumers that
are incorrectly promoted to durable on the v12 side still function — they
just leave behind durable subscriptions that are cleaned up when the bridge
disconnects.

### SharedSubscriptionInfo fields lost on v12 wire

`SharedSubscriptionInfo` also shares `SubscriptionInfo`'s type byte. At v12,
the `shared` flag is dropped and only base `SubscriptionInfo` fields survive.

**Impact:** Store-level subscription metadata exchanged between brokers
(e.g., during KahaDB replication or manual backup/restore) will not
preserve the `shared` flag if serialized at v12.

**Note:** KahaDB journal serialization uses `storeOpenWireVersion` (set to 13
by `SharedTopicBrokerService`), which is independent of the wire version negotiated
between brokers. A v13 broker's own journal correctly persists
`SharedSubscriptionInfo`. The v12 wire limitation only affects data exchanged
*between* brokers.

### ~~v13-only: advisory exact class check~~ (fixed)

`DemandForwardingBridgeSupport.serviceRemoteConsumerAdvisory()` (line 943)
previously used `data.getClass() == ConsumerInfo.class` — an exact class
match that would silently drop `SharedConsumerInfo` advisories under v13.
Changed to `data instanceof ConsumerInfo` so subclasses are accepted.

---

## File Map

### HYTE `activemq-client` (protocol layer)

```
activemq-client/src/main/java/
  org/apache/activemq/
    command/
      SharedConsumerInfo.java          # extends ConsumerInfo, adds shared+durable
      SharedSubscriptionInfo.java      # extends SubscriptionInfo, adds shared
      ExceptionResponse.java           # overlay: adds errorCode, lazy apply to JMSException
      Message.java                     # overlay: adds getDeliveryTime/setDeliveryTime
    openwire/v13/
      MarshallerFactory.java           # clones v12 array, replaces changed marshallers
      ConsumerInfoMarshaller.java      # shared + durable fields
      SubscriptionInfoMarshaller.java  # shared field, creates SharedSubscriptionInfo
      ExceptionResponseMarshaller.java # errorCode field
      MessageMarshaller.java           # deliveryTime field (abstract)
      ActiveMQMessageMarshaller.java   # re-parents onto v13 MessageMarshaller
      ActiveMQTextMessageMarshaller.java
      ActiveMQBytesMessageMarshaller.java
      ActiveMQMapMessageMarshaller.java
      ActiveMQStreamMessageMarshaller.java
      ActiveMQObjectMessageMarshaller.java
      ActiveMQBlobMessageMarshaller.java
```

### HYTE `activemq-broker` (broker layer)

```
activemq-broker/src/main/java/
  io/hyte/activemq/broker/
    SharedTopicBrokerService.java                    # wires SharedTopicRegion, storeVersion=13
    region/
      SharedDurableTopicSubscription.java       # queue-style dispatch, N consumers
      SharedTopicRegion.java                    # extends TopicRegion, shared sub lifecycle
    jmx/
      ManagedSharedTopicRegion.java             # JMX-enabled SharedTopicRegion
```

### Tests

```
tests/jms/src/test/java/
  io/hyte/activemq/tests/jms/
    SharedSubscriptionJmsTest.java              # 18 integration tests
```

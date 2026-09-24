# Network Connector / Bridge Bug Backlog

Actionable backlog from the July 2026 deep review of
`activemq-broker/src/main/java/org/apache/activemq/network/` (and `network/jms/`),
motivated by hung network connectors observed in production
(hyte-mq 5.14.10-based brokers, `eus2rel-prd-slrm-comn-5` ↔ `scusrel` cross-region
+ 6-broker `11.16.x.3` mesh). The same code exists in this 6.x tree; fixes here
apply to both.

Companion documents:
- `network-connector-review-2026-07.md` — full review findings (source of this backlog)
- Existing individual bug docs (already written, pre-review):
  `future-broker-info-timeout-ignored.md`,
  `network-bridge-missing-decrement-on-non-permissable-destination.md`,
  `network-bridge-null-region-subscriptions-npe.md`,
  `network-bridge-unsynchronized-demand-consumer-dispatched.md`,
  `network-bridge-listener-not-volatile.md`,
  `discovery-connector-failed-bridge-left-in-map.md`,
  `network-bridge-increment-outstanding-responses-race.md`

Production context (2026-07-02 thread dumps, 5 × 60s): NO in-JVM hang — zero
threads in bridge code, all 16 outbound bridge connections idle in blocking
socket reads. The production symptom is therefore a SILENT stall: bridge
connected but not forwarding (prefetch exhaustion / lost demand subscriptions /
advisory exhaustion), or bridge gone with the connector never rebuilding it
(stale bookkeeping). Tasks NC-1, NC-2, NC-7, NC-8 are the prime suspects.

Workflow per task: write a test that demonstrates the bug FIRST (commit test,
observe failure), then apply the fix (test passes) — same pattern as
`NetworkConsumerPriorityDispatchStarvationTest`.

---

## P1 — Silent stall / hung-connector causes (production suspects)

### NC-1 Non-permissible destination dispatch never acks — prefetch exhaustion
- [x] Status: fixed on amq-network-connector-fixes (0ca5be98f6 test, 8bafe3d945 fix)
- [x] HARDENED: excluded destinations are now never dispatched to demand subs
  (permissibility composed into the NetworkBridgeFilter, evaluated at local
  dispatch; per-destination memoized). Excluded messages stay on the local
  queue for local consumers - no loss. Forward-time fallback is policy-driven
  via new `wildcardExcludePolicy` config (ack=consume [default] | dlq=poison
  ack -> DLQ). Commits 0f439c856a (no-loss test), 1bf7b6723f (filter),
  1b177e7cde (policy).
- **Severity:** HIGH. **Symptom:** hung network consumer; bridge connected, idle,
  messages stuck on local broker.
- **Classes:** `DemandForwardingBridgeSupport.serviceLocalCommand()` (~line 1228
  increments `sub.incrementOutstandingResponses()` unconditionally; the
  `if (isPermissableDestination(md.getDestination()))` block at ~1254 has NO
  else branch). Interacts with `DemandSubscription.dispatched`.
- **Description:** A demand subscription created for a wildcard consumer (e.g.
  remote consumer on `SOME.>`, which passes the permissible check at sub-creation
  ~line 966) receives dispatches for concrete destinations. If a concrete
  destination is excluded (`excludedDestinations` checked first in
  `isPermissableDestination`, ~1380-1388), the message is dispatched to the
  bridge's local consumer, `dispatched` is incremented, but the message is never
  forwarded, never ACKed, and `dispatched` is never decremented. The local
  prefetch window fills with permanently-unacked messages → dispatch to the
  bridge stops entirely. `dispatched > 0` forever also makes every subsequent
  `removeSubscription()` hit the full 30s `waitForCompletion()` timeout
  (compounds NC-2). Contrast: the duplicate-suppression path at ~1230-1240
  correctly ACKs (INDIVIDUAL_ACK) and decrements.
- **Fix:** add else branch mirroring the suppression path: send
  `MessageAck(md, MessageAck.INDIVIDUAL_ACK_TYPE, 1)` to localBroker, decrement
  in finally.
- **Test idea:** two brokers, network connector with wildcard-capable demand +
  `excludedDestinations` on a concrete match; remote wildcard consumer; publish
  N > prefetchSize excluded-destination messages, then publish an included
  message; assert the included message still arrives (pre-fix: prefetch is
  exhausted, it never does).

### NC-2 `DemandSubscription.waitForCompletion()` missed notify — 30s serial stalls
- [x] Status: fixed on amq-network-connector-fixes (21b1f33d27 test, beac0b3d51 fix; includes increment race)
- **Severity:** HIGH. **Symptom:** bridge unresponsive for minutes during mass
  consumer disconnect; destination add/remove processing stalls.
- **Classes:** `DemandSubscription.waitForCompletion()` (lines ~124-148),
  `DemandSubscription.decrementOutstandingResponses()`; caller
  `DemandForwardingBridgeSupport.removeSubscription()` (~1189-1199) which runs it
  on `serialExecutor` — a `newSingleThreadExecutor()` (~line 169) shared with
  destination add/remove processing (~1009).
- **Description:** `dispatched.get() > 0` is checked OUTSIDE the `activeWaiter`
  monitor and `wait(30s)` is called with no re-check loop inside the monitor. A
  `notifyAll()` firing between the check and the `wait()` is lost → silent full
  30s sleep (the post-wait warning doesn't fire because `dispatched` is already
  0). One missed notify per removed subscription serializes on the
  single-threaded executor: mass disconnect = 30s × N stall. At `stop()` the
  serialExecutor gets 5s then `shutdownNow()` — pending remove commands dropped.
- **Fix:** standard monitor protocol — deadline loop with the state check inside
  `synchronized (activeWaiter)`; decrement+notify under the same monitor.
  (Related known bug: `incrementOutstandingResponses()` check-then-act race —
  see `network-bridge-increment-outstanding-responses-race.md`; fix together.)
- **Test idea:** unit test with controlled interleaving (decrement between check
  and wait) asserting `waitForCompletion` returns promptly, not after 30s.

### NC-3 Conduit matching race attaches interest to a dying DemandSubscription
- [x] Status: fixed on amq-network-connector-fixes (2807b46358, subscriptionsLock; churn guard ConduitSubscriptionChurnTest)
- **Severity:** HIGH. **Symptom:** remote consumer silently receives nothing
  until it reconnects; no errors logged.
- **Classes:** `ConduitBridge.addToAlreadyInterestedConsumers()` (~70-101) vs
  `ConduitBridge.removeDemandSubscription()` (~115-133);
  `DemandForwardingBridgeSupport.configureDemandSubscription()` (~1722 publishes
  into `subscriptionMapByLocalId` BEFORE duplicate suppression),
  `undoMapRegistration()` (~1537), `removeDuplicateSubscription()` (~1603-1609,
  cross-bridge).
- **Description:** conduit match+`ds.add(id)` is unsynchronized against the
  removal side's `ds.remove()`+`isEmpty()`+map-removal. Adds run on the
  remote-transport thread AND `syncExecutor` (durable sync path ~756-798);
  removes on transport threads; cross-bridge removes via duplicate suppression.
  Interleaving: thread A matches ds → thread B removes the last consumer and
  tears ds down → A adds its consumer id to the dead ds and returns matched →
  `createDemandSubscription` returns null → NO local subscription exists for the
  remote consumer. Second window: a new sub is published into the map before
  duplicate suppression; suppression's `undoMapRegistration` strands any
  interest conduited onto it in that window. The `synchronized
  (brokerService.getVmConnectorURI())` at ~979 only guards add vs add, not add
  vs remove.
- **Fix:** per-bridge (or per-DemandSubscription) lock covering (i)
  match+`ds.add()`, (ii) `ds.remove()`+`isEmpty()`+map removal; re-verify ds is
  still in `subscriptionMapByLocalId` after acquiring; don't publish into maps
  until suppression passes.

### NC-4 Stale `activeEvents` entry permanently blocks reconnection
- [x] Status: fixed on amq-network-connector-fixes (fcc7e7a3ca test, 557ec62032 fix; includes bridges-map leak)
- **Severity:** HIGH. **Symptom:** connector "up" in JMX, bridge gone, never
  reconnects until broker restart; only DEBUG-level evidence. Matches a
  bridge-gone production stall (nothing visible in thread dumps).
- **Classes:** `DiscoveryNetworkConnector.onServiceAdd()` (guard ~108; failure
  paths ~136-141, ~150-155, ~171-176), `SimpleDiscoveryAgent.serviceFailed()`
  (activemq-client, ~131-176).
- **Description:** `onServiceAdd` reserves the URI in `activeEvents` up front;
  every failure path relies on `discoveryAgent.serviceFailed(event)`
  round-tripping through `onServiceRemove()` to release it. `serviceFailed` can
  throw IOException (caught, debug-logged only) or silently no-op
  (SimpleDiscoveryAgent guard: `running` momentarily false, or the event's
  `failed` flag already CAS'd by a concurrent failure path — `bridgeFailed()`
  listener and the `bridge.start()` catch can both call it for the same event).
  The URI stays reserved forever; all future discovery events for that broker
  are dropped as duplicates.
- **Fix:** clean local state directly at the failure site —
  `activeEvents.remove(uri, event)` (and `bridges.remove(uri, bridge)` on start
  failure) BEFORE calling `serviceFailed`; raise swallowed IOException logs to
  WARN. Related known bug (`discovery-connector-failed-bridge-left-in-map.md`):
  same failure path also leaves the bridge in `bridges` — fix together.
- **Ops note:** SimpleDiscoveryAgent permanently disables reconnect after
  `maxReconnectAttempts` ("Reconnecting has been disabled for:" WARN) — never
  configure maxReconnectAttempts > 0 for self-healing topologies; grep prod
  logs for that message.

### NC-5 `onServiceAdd` vs `handleStop` race — zombie bridge + blocked restart
- [x] Status: fixed on amq-network-connector-fixes (3a053c9305 test, 015630d3dd fix)
- **Severity:** HIGH.
- **Classes:** `DiscoveryNetworkConnector.onServiceAdd()` (~84 stopped-check,
  ~162-165 put/start), `DiscoveryNetworkConnector.handleStop()` (~224-233, does
  NOT hold `synchronized (bridges)`).
- **Description:** the isStopped/isStopping check happens only at the top of
  `onServiceAdd`; an in-flight call can block in
  `TransportFactory.connect(connectUri)` (~132) for the TCP connect timeout
  while `handleStop` stops all bridges and clears `bridges`/`activeEvents`.
  The in-flight thread then resumes, inserts and STARTS a bridge under a
  stopped connector — live transports, demand subscriptions, nothing will ever
  stop it. On connector restart the stale `activeEvents` entry makes new events
  for that broker return as duplicates — permanent reconnect failure plus a
  zombie bridge forwarding concurrently.
- **Fix:** re-check `serviceSupport.isStopping()/isStopped()` inside
  `synchronized (bridges)` before put/start (bail out and dispose transports if
  stopping); run handleStop's iterate-stop-clear under the same lock.

### NC-6 `startLocalBridge` failure paths skip latch countdowns; untimed duplex latch await
- [x] Status: fixed on amq-network-connector-fixes (10c0e726d6 test, d84fad4f3e fix)
- **Severity:** HIGH. **Symptom:** duplex remote transport reader thread parked
  forever; duplex connection wedges (keepalives unserviced).
- **Classes:** `DemandForwardingBridgeSupport.startLocalBridge()`
  (`startedLatch.countDown()`/`localStartedLatch.countDown()` at ~572-573 not in
  a finally; throws at ~522/547 skip them); duplex ConsumerInfo handler
  `serviceRemoteCommand` — untimed `localStartedLatch.await()` at ~878.
- **Description:** two coupled defects. (1) Any exception in startLocalBridge
  (ExceptionResponse, oneway IOException, `networkBridgeStarted` throw) leaves
  the start latches un-counted; release then depends on a fully-completing async
  dispose. (2) A duplex peer forwards ConsumerInfo right after the BrokerInfo
  exchange; the inbound transport thread blocks on the untimed latch await. If
  local start failed (latches never counted), teardown of the duplex initiating
  connection must service the very transport whose reader thread is parked —
  stuck teardown / thread held hostage.
- **Fix:** try/finally around startLocalBridge body so both latches always count
  down (stop() already does this at ~337-340); make the await timed with a
  disposed check (`if (!localStartedLatch.await(t, MS) || disposed.get()) return;`).

### NC-7 Untimed `request()` during bridge start while holding `synchronized(this)`
- [x] Status: fixed on amq-network-connector-fixes (7012abb4c3 test+localRequestTimeout config, e0adab5286 fix; helper-thread bound, transport-level timeout insufficient for sync vm dispatch)
- **Severity:** HIGH. **Symptom:** bridge exists but nothing flows, forever; no
  bridgeFailed, no reconnect; remote threads poll in `safeWaitUntilStarted()`.
- **Classes:** `DemandForwardingBridgeSupport.startLocalBridge()` — untimed
  `localBroker.request(localConnectionInfo)` at ~520 and
  `duplexInboundLocalBroker.request(...)` at ~545, both inside
  `synchronized (this)` opened at ~497; `safeWaitUntilStarted()` (~1764-1771,
  1s poll loop, exits only on disposed).
- **Description:** if the local broker stalls in addConnection (JAAS/LDAP login
  hang, persistence adapter blocked, advisory dispatch blocked), the bridge
  start thread blocks forever holding the bridge monitor. `startedLatch` never
  counts down; every remote transport thread spins in safeWaitUntilStarted;
  `bridgeFailed` never fires; the connector never retries.
- **Fix:** use `request(cmd, timeoutMillis)`; on timeout route to
  `serviceLocalException`; move blocking I/O outside `synchronized(this)`.

### NC-8 Broker-global `vmConnectorURI` monitor held across blocking operations
- [x] Status: fixed on amq-network-connector-fixes (a78d4d4088 narrowing; 6235f36411/8e059584c2 vmConnectorURI sync)
- **Severity:** HIGH. **Symptom:** with duplicate suppression enabled, ALL
  bridges on the broker stop creating demand subscriptions when one bridge
  blocks — cluster-wide silent stall.
- **Classes:** `DemandForwardingBridgeSupport` ~979-981 and ~884-886
  (`synchronized (brokerService.getVmConnectorURI())` around
  `addConsumerInfo(info)`), `addConsumerInfo` → `localBroker.oneway` (~1174),
  `removeDuplicateSubscription` (~1603-1609, iterates ALL NetworkConnectors and
  calls into other bridges); `BrokerService.getVmConnectorURI()`
  (BrokerService.java ~1648-1657 — lazy, unsynchronized creation: racing threads
  can get DIFFERENT monitor objects, silently defeating the lock).
- **Description:** when `suppressDuplicateQueueSubscriptions`/`...TopicSubscriptions`
  is on, every bridge serializes consumer-advisory handling on one shared
  monitor whose critical section includes transport sends and cross-bridge
  calls. One blocked bridge (producer flow control on the advisory path, or a
  wedged peer per NC-7) freezes demand subscription creation broker-wide.
- **Fix:** narrow the critical section to the map/duplicate check only;
  or dedicated `ReentrantLock.tryLock(timeout)`. Also initialize
  `vmConnectorURI` eagerly (or synchronize the getter).

---

## P2 — Reconnect / lifecycle correctness

### NC-9 Cross-bridge RejectedExecutionException kills a healthy bridge
- [ ] Status: open. **Severity:** MEDIUM.
- **Classes:** `DemandForwardingBridgeSupport.removeSubscription()` (~1189,
  `serialExecutor.execute`), `removeDemandSubscriptionByLocalId` (~1746-1758,
  catches IOException ONLY), `hasLowerPriority`/`removeDuplicateSubscription`.
- **Description:** duplicate suppression routes calls from healthy bridge C into
  stopping bridge B; B's shut-down serialExecutor throws unchecked REE, uncaught
  by the IOException-only handlers, propagates up C's `serviceRemoteCommand`
  Throwable handler → `serviceRemoteException` → C torn down. Random bridge
  flapping under churn.
- **Fix:** catch RejectedExecutionException in `removeSubscription` (or check
  `isShutdown()`); broaden catches to Exception.

### NC-10 Loop-back detection leaves bridge in limbo
- [ ] Status: open. **Severity:** MEDIUM-HIGH.
- **Classes:** `DemandForwardingBridgeSupport` ~411-419 (localBrokerId ==
  remoteBrokerId branch: disposes transports only; `disposed` stays false,
  latches un-counted, executors alive, no bridgeFailed;
  `doStartLocalAndRemoteBridges` still runs and unwinds only via a secondary
  TransportDisposedIOException).
- **Description:** a failover/VIP/DNS URI resolving back to the same broker (a
  common misconfiguration) hits this on every connect: limbo bridges holding 2
  executor threads each, or a permanent build/teardown loop.
- **Fix:** set disposed, count down latches, route through stop().

### NC-11 serialExecutor/syncExecutor thread leak on never-started bridges
- [ ] Status: open. **Severity:** MEDIUM (memory/thread leak).
- **Classes:** `DemandForwardingBridgeSupport` ~169-171 (executors created
  eagerly in field initializers), `stop()` ~275+ (shutdown only inside the
  `started.compareAndSet(true, false)` branch).
- **Description:** a bridge constructed but never successfully started leaks two
  non-daemon unnamed threads per attempt — visible as accumulating
  `pool-N-thread-1` in thread dumps of brokers with flapping networks.
- **Fix:** lazy creation in start() or unconditional shutdown in stop(); named
  daemon thread factories.

### NC-12 Duplex advisory path skips `safeWaitUntilStarted()`
- [ ] Status: open. **Severity:** MEDIUM.
- **Classes:** `DemandForwardingBridgeSupport` ~806-810 (advisory branch
  processes immediately; contrast non-advisory at ~815 and ConsumerInfo at
  ~878); NPE sites ~995 (`localConnectionInfo`), ~1660/1704
  (`localSessionInfo`), ~923 (`demandConsumerInfo` in ackAdvisory).
- **Description:** duplex peer sends advisories right after BrokerInfo exchange,
  before start populated the fields → NPE → serviceRemoteException → bridge
  torn down mid-handshake → reconnect → same race. Duplex bridge flaps and
  never comes up under load. Also: those fields are non-volatile (no
  happens-before without the latch).
- **Fix:** `if (!safeWaitUntilStarted()) return;` before the advisory handling.
  Related low item NC-20: the return value is ignored at all 3 existing call
  sites (~733, ~815, ~1224).

### NC-13 `onServiceRemove` drops the bridge without stopping it
- [ ] Status: open. **Severity:** MEDIUM.
- **Classes:** `DiscoveryNetworkConnector.onServiceRemove()` (~193-198).
- **Description:** bridge removed from `bridges` but never stop()ed — assumes
  every remove follows a failure. On graceful service deregistration (or
  multicast advertisement loss with TCP alive) the orphan keeps forwarding;
  re-advertise builds a SECOND bridge to the same broker (duplicate forwarding,
  duplicate durable network subs); handleStop can no longer reach the orphan.
- **Fix:** capture and dispose the removed bridge (async-safe).

### NC-14 `bridgeFailed` flag never set when listener is null
- [ ] Status: open. **Severity:** LOW-MEDIUM.
- **Classes:** `DemandForwardingBridgeSupport.fireBridgeFailed()` (~1800):
  `if (l != null && bridgeFailed.compareAndSet(...))` — short-circuit skips the
  flag for listenerless (duplex-created) bridges, so the `!bridgeFailed.get()`
  guards in startLocalBridge (~496)/startRemoteBridge (~579) don't stop a
  concurrently failing bridge's startup.
- **Fix:** `if (bridgeFailed.compareAndSet(false, true) && l != null) l.bridgeFailed();`

### NC-15 ShutdownInfo runs `stop()` inline on the transport thread
- [ ] Status: open. **Severity:** LOW-MEDIUM.
- **Classes:** `DemandForwardingBridgeSupport.serviceLocalCommand` (~1299-1301);
  stop() awaits executors 2×5s + `sendShutdown.await(10s)`; serialExecutor may
  be draining 30s waitForCompletion tasks; `shutdownNow` drops pending removes
  (durable demand subs left unremoved → message accumulation).
- **Fix:** hand stop() to taskRunnerFactory (as exception paths do via
  `ServiceSupport.dispose`).

### NC-16 `cleanupDurableSub` check-then-act race
- [x] Status: fixed on amq-network-connector-fixes (2807b46358, with NC-3). **Severity:** MEDIUM.
- **Classes:** `DemandForwardingBridgeSupport.cleanupDurableSub()` (~1088-1110)
  vs `ConduitBridge.addToAlreadyInterestedConsumers` durable add (~94); also
  reachable from RemoveInfo (~1041) and RemoveSubscriptionInfo (~1082) paths.
- **Description:** `durableRemoteSubs.isEmpty()` + teardown races a concurrent
  conduit add of new durable interest (durable sync on syncExecutor vs advisory
  on remote-transport thread) → local durable sub deactivated+unsubscribed while
  the remote believes demand is registered → stalled durable NC sub until
  bridge restart.
- **Fix:** same lock as NC-3; re-check emptiness under it.

---

## P3 — Auxiliary connectors / MBeans / misc

### NC-17 MBeanNetworkListener.onStop() leaks on unregister failure
- [ ] Status: open. **Severity:** MEDIUM-HIGH (unbounded under flapping).
- **Classes:** `MBeanNetworkListener.onStop()` (~73-89: unregisterMBean, map
  remove, `mBeanBridgeDestination.stop()` share one try);
  `MBeanBridgeDestination.stop()` (~120 cancels the purge scheduler task);
  `NetworkConnector.unregisterNetworkBridgeMBean()` (~224, double-unregister
  source); onStart mirror (~59-69).
- **Description:** InstanceNotFoundException from unregisterMBean skips the map
  remove AND the stop — purge task fires forever per dead bridge, destination
  MBeans stay registered, map pins the bridge. onStart mirror: InstanceAlready
  ExistsException skips MBeanBridgeDestination creation; stale MBean shows a
  dead bridge as up (misleads hung-connector diagnosis).
- **Fix:** separate try/catch per step, teardown in finally; unregister stale
  MBean before registering in onStart.

### NC-18 LdapNetworkConnector defects (grouped)
- [ ] Status: open. **Severity:** MEDIUM.
- **Classes:** `LdapNetworkConnector`.
- (a) never calls `brokerService.removeNetworkConnector()` (add ~315, remove
  ~371-373, stop ~269-278) — each LDAP change cycle (objectChanged does
  remove+add, ~449) leaks a stopped connector + MBean in
  `BrokerService.networkConnectors`, unbounded.
- (b) `objectRenamed()` (~434-443): `uuidMap.put(uuidNew, null)` NPEs when the
  old key misses (key-scheme mismatch between `getNameInNamespace` and
  `getOldBinding().getName()` is baked in); NPE escapes into the JNDI event
  dispatcher → LDAP event listening dies silently → frozen connector set. Not
  synchronized, unlike addConnector/removeConnector.
- (c) lifecycle: start()/stop() bypass `serviceSupport` (isStarted/isStopped
  always false); `failover=true` retry loop (~228) can hang broker startup
  forever; stop() aborts on first child failure leaving the rest running and
  the LDAP context open; context.close() NPE/double-close paths.
- (d) minor: unsynchronized `++availableURIsIndex` (~96); referenceMap
  zero-count entries never removed (~362-363).

### NC-19 MulticastNetworkConnector bridge invisible to `bridges` map
- [ ] Status: open. **Severity:** LOW-MEDIUM.
- **Classes:** `MulticastNetworkConnector.handleStart()` (~95-116).
- **Description:** bridge stored only in a private field, never in the inherited
  `bridges` map — `NetworkConnector.removeDemandSubscription()` (~241-253, used
  by duplicate suppression) and `activeBridges()` can't see it; stale demand sub
  cleanup never works for multicast. handleStart failure leaks connected
  transports (no dispose on exception; handleStop won't run).
- **Fix:** register/deregister in `bridges`; try/catch dispose in handleStart.

### NC-20 Small DemandForwardingBridgeSupport items (grouped)
- [ ] Status: open. **Severity:** LOW.
- `safeWaitUntilStarted()` return ignored at ~733/815/1224 (proceed against
  torn-down state when disposed).
- `ConditionalNetworkBridgeFilterFactory` rate limiter (~109-110, 170-180):
  plain `matchCount`/`rateDurationEnd` mutated from multiple dispatch threads —
  over-admits / spuriously rejects. Make synchronized or atomic.
- `ConduitBridge.removeDemandSubscription` leaves a stale
  `subscriptionMapByRemoteId` entry for the founding consumer (vs base class
  ~1735-1744 which removes it); duplex ack lookup (~869) can resolve a
  long-gone consumer id. Bounded, correctness wart.
- `NetworkBridgeConfiguration`: `setUseBrokerNameAsIdSees` public API typo;
  `setNetworkTTL` silently overwrites consumerTTL/messageTTL (XML
  order-sensitive).

---

## P4 — network/jms (JMS-to-JMS bridge) — only if this component is in use

### NC-21 Reconnect executor can only ever run one task
- [ ] Status: open. **Severity:** HIGH (for jms bridge users).
- **Classes:** `jms/JmsConnector` (~648-652): `ThreadPoolExecutor(0, 2, 30s,
  LinkedBlockingQueue)` — unbounded queue never rejects, so the pool never grows
  past 1 thread. With default INFINITE retries in `doInitializeConnection`
  (~579-637), one dead side's endless reconnect loop starves the other side's
  reconnect forever.
- **Fix:** SynchronousQueue with max 2 / fixed pool of 2 / core 2 +
  allowCoreThreadTimeOut.

### NC-22 DestinationBridge.start() latches started=true on failure
- [ ] Status: open. **Severity:** HIGH (for jms bridge users).
- **Classes:** `jms/DestinationBridge.start()` (~85-91).
- **Description:** if createConsumer/createProducer throws, `started` stays true
  and every later start() no-ops — connector reports connected, bridge has no
  consumer, messages accumulate silently. "Restart fixes it."
- **Fix:** reset started and rethrow on failure.

### NC-23 Remaining jms-bridge items (grouped)
- [ ] Status: open. **Severity:** MEDIUM (LOW where noted).
- `ForwardingBridge.stop()` (~170-171): untimed `request()` to both brokers —
  black-holed connection hangs stop/restart/shutdown. Use timed request/oneway. (HIGH if ForwardingBridge used.)
- `ForwardingBridge.serviceRemoteException` (~185-188): logs only, never
  `fireBridgeFailed()` (contrast serviceLocalException ~213-217) — remote socket
  death on an idle destination leaves the bridge dead forever. (HIGH if used.)
- `ForwardingBridge.startBridge` (~147 vs 149-151/227): consumer registered
  before producerInfo assigned — NPE window on first dispatch; fields lack safe
  publication.
- `JmsConnector` restart broken (~171-199 vs ~104-127): stop() nulls
  `connectionService` but `initialized` stays true → restart NPEs, no reconnect
  ever scheduled; plus stop/failure NPE race at ~492/514.
- `replyToBridges` LRUCache (~73) unsynchronized across dispatch threads
  (LinkedHashMap corruption → livelock possible); `clear()` (~499) drops live
  replyTo bridges without stop() — session/temp-queue leak per failure.
- `SimpleJmsQueueConnector`/`SimpleJmsTopicConnector` ExceptionListeners
  (~195-200/268-273) pass `foreignConnection.get()` (the CURRENT connection)
  instead of their own — stale listener tears down the freshly reconnected
  connection (flapping).
- Foreign reconnect doesn't reset outbound bridges' cached producer/session
  (~335-352; consumer side does) — first send after reconnect kills the new
  connection.
- `QueueBridge`/`TopicBridge.stop()` closes two sessions without try/finally —
  healthy-side session leaks per reconnect cycle.
- LOW: `ReconnectionPolicy.setSendRetyDelay` floor is dead code and
  sendRetryDelay is never used; backoff is linear not exponential (~236-238);
  `DestinationBridge.onMessage` retry loop can pin the provider dispatch
  thread; ForwardingBridge enqueueCounter overcounts on failed forwards.

---

## Self-healing configuration (verified; deploy alongside fixes)

```xml
<networkConnector name="A-to-B"
    uri="static:(tcp://brokerB:61616?soWriteTimeout=30000&amp;keepAlive=true&amp;wireFormat.maxInactivityDuration=30000)?initialReconnectDelay=1000&amp;maxReconnectDelay=30000&amp;useExponentialBackOff=true"
    prefetchSize="500" advisoryPrefetchSize="100"
    dispatchAsync="true" alwaysSyncSend="true" duplex="false"/>
```
- `soWriteTimeout` (WriteTimeoutFilter) is DISABLED by default — the single most
  important missing setting; converts a 13–30 min TCP retransmit hang into fast
  failure → bridgeFailed → reconnect.
- Never set `maxReconnectAttempts > 0` (permanently disables retry).
- `advisoryPrefetchSize` decouples advisory flow from message prefetch
  (exhaustion silently stops demand-sub creation).
- Prefer two simplex connectors over duplex.
- `abortSlowAckConsumerStrategy` with `ignoreNetworkConsumers="false"`,
  `maxTimeSinceLastAck="300000"` — default strategies IGNORE network consumers;
  aborting a hung one closes the bridge connection → reconnect.
  NOTE: safe to enable now — the bridge's own advisory consumer used to batch
  acks purely by `advisoryAckPercentage`, so a quiet bridge holding a partial
  batch looked like a slow consumer and got aborted. `advisoryAckInterval`
  (new bridge config, default 15000 ms, <=0 disables) flushes pending advisory
  acks on an interval; keep it below the strategy's `maxTimeSinceLastAck`.
- `sendFailIfNoSpaceAfterTimeout="60000"` in systemUsage — PFC blocking of
  bridge forwards becomes a detectable failure.
- `localRequestTimeout` (new bridge config, default 30000) bounds the local-broker
  requests during bridge start (pre-campaign, no such option existed).

## Production diagnosis state (2026-07-02)

Thread dumps from `eus2rel-prd-slrm-comn-5` (5 × 60s) show NO in-JVM hang:
zero bridge-code frames, 6 tcp bridges to the `11.16.x.3` mesh (all reconnected
together ~7.9 days prior — mesh-wide event) + 10 ssl to `scusrel:61632`
(23–27 days old), all idle in blocking reads; one transient KahaDB fsync stall.
Next step when it recurs: JMX per-network-subscription stats on the REMOTE
brokers (dispatched vs dequeued/inflight — prefetch full ⇒ NC-1 family; demand
sub missing with queue depth ⇒ NC-3 family; bridge count < expected ⇒ NC-4/NC-5
family), and grep all brokers' logs for "Reconnecting has been disabled".

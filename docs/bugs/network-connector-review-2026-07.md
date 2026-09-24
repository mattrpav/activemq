# Network Connector / Network Bridge Review — July 2026

Deep review of `activemq-broker/src/main/java/org/apache/activemq/network/` (and
`network/jms/`) for bugs, thread safety, and memory leaks, motivated by observed
hung network connectors in production. Excludes the 7 previously documented bugs
(see the other `network-bridge-*.md` / `discovery-connector-*.md` /
`future-broker-info-*.md` files in this directory).

Findings are grouped by file area, ordered by severity. "HUNG" tags mark
findings that can directly produce a hung connector/bridge.

---

## A. DemandForwardingBridgeSupport.java (core bridge)

### A1 [HIGH][HUNG] Untimed `localBroker.request()` while holding `synchronized(this)` in startLocalBridge
- Lines 520, 545 (inside `synchronized (this)` at 497).
- If the local broker stalls (auth plugin hang, store flush, blocked advisory
  dispatch), the bridge-start thread blocks forever. `startedLatch` stays
  un-counted; every remote transport thread spins in `safeWaitUntilStarted()`
  (1s poll loop, exits only on `disposed`); `bridgeFailed` never fires; the
  connector never reconnects. Classic "bridge exists, nothing flows, forever."
- Fix: use `request(cmd, timeout)`, fail into `serviceLocalException` on
  timeout; move blocking I/O outside `synchronized(this)`.

### A2 [HIGH][HUNG] startLocalBridge exception path skips latch countdowns
- Lines 572-573 (`startedLatch.countDown()` / `localStartedLatch.countDown()`)
  are not in a `finally`; throws at 522/547 skip them. Release then depends on a
  fully-completing async dispose, which (see A3) can itself be blocked by a
  thread parked on the latch.
- Fix: `try/finally` around the body so both latches always count down.

### A3 [HIGH][HUNG] Untimed `localStartedLatch.await()` on the remote transport thread
- Line 878 (duplex `ConsumerInfo` handling).
- Duplex peer forwards a ConsumerInfo right after the BrokerInfo exchange; if
  local bridge start is slow or failed, the TCP reader thread for the duplex
  connection parks forever — keepalives stop being serviced and the whole duplex
  connection wedges.
- Fix: timed await + disposed check, same pattern as `safeWaitUntilStarted()`.

### A4 [HIGH][HUNG] Broker-global `vmConnectorURI` monitor held across blocking bridge operations
- Lines 979-981, 884-886: `synchronized (brokerService.getVmConnectorURI())`
  around `addConsumerInfo(info)`, which does `localBroker.oneway` (1174), region
  introspection, and `removeDuplicateSubscription` (1603-1609) that calls into
  OTHER bridges.
- With `suppressDuplicateQueueSubscriptions=true`, all bridges on the broker
  serialize advisory processing on one monitor. One blocked bridge stalls
  demand-subscription creation broker-wide.
- Related nit: `BrokerService.getVmConnectorURI()` lazily creates the URI
  unsynchronized — racing threads can get different monitor objects, silently
  defeating the lock.
- Fix: narrow the critical section to map/duplicate-check only, or use a
  `ReentrantLock.tryLock(timeout)`.

### A5 [MEDIUM] Cross-bridge `RejectedExecutionException` kills a healthy bridge
- Line 1189 `serialExecutor.execute(...)` vs 1746-1758 which catches
  `IOException` only. Duplicate-suppression routes calls from bridge C into a
  stopping bridge B; B's shut-down serialExecutor throws REE, uncaught, which
  propagates up C's `serviceRemoteCommand` → C is torn down. Random bridge
  flapping under churn.
- Fix: catch `RejectedExecutionException` in `removeSubscription`; broaden
  catches to `Exception`.

### A6 [MEDIUM-HIGH][HUNG] Loop-back detection leaves bridge in limbo
- Lines 411-419: on self-connection (localBrokerId == remoteBrokerId), only the
  transports are disposed — `disposed` stays false, latches un-counted,
  executors running, no `bridgeFailed`. `doStartLocalAndRemoteBridges()` still
  runs and unwinds only via a secondary transport exception. A VIP/DNS URI that
  resolves back to the same broker produces limbo bridges or a build/teardown
  loop.
- Fix: set `disposed`, count down latches, route through `stop()`.

### A7 [MEDIUM] serialExecutor/syncExecutor thread leak on never-started bridges
- Lines 169-171: executors created eagerly at construction; shut down only
  inside `stop()`'s `started==true` branch (275+). A bridge constructed but
  never successfully started leaks 2 non-daemon unnamed threads per attempt.
  Visible as accumulating `pool-N-thread-1` threads on brokers with flapping
  networks.
- Fix: create lazily in `start()` or shut down unconditionally in `stop()`; use
  named daemon thread factories.

### A8 [MEDIUM] Duplex advisory path skips `safeWaitUntilStarted()`
- Lines 806-810: advisory messages processed before
  `localConnectionInfo`/`localSessionInfo`/`demandConsumerInfo` are populated →
  NPE (lines 995, 1660/1704, 923) → bridge torn down mid-handshake → reconnect →
  same race. Duplex bridge flaps and never comes up under load.
- Fix: call `safeWaitUntilStarted()` (and honor its return) in the advisory
  branch.

### A9 [LOW] `safeWaitUntilStarted()` return value ignored at call sites
- Lines 733, 815, 1224: proceeds against torn-down state when disposed.
- Fix: `if (!safeWaitUntilStarted()) return;`

### A10 [LOW-MEDIUM] `bridgeFailed` never set when listener is null
- Line 1800: `if (l != null && bridgeFailed.compareAndSet(...))` — short-circuit
  means listenerless (duplex-created) bridges never set the flag, so the
  `!bridgeFailed.get()` guards in start paths don't stop a concurrently failing
  bridge.
- Fix: `if (bridgeFailed.compareAndSet(false, true) && l != null) l.bridgeFailed();`

### A11 [LOW-MEDIUM] ShutdownInfo runs `stop()` inline on the transport thread
- Lines 1299-1301: `stop()` awaits executor termination (2×5s) plus
  `sendShutdown.await(10s)`; serialExecutor may be draining 30s
  `waitForCompletion()` tasks. Pins the VM transport thread; `shutdownNow`
  drops pending remove tasks (durable demand subs left unremoved).
- Fix: hand off to taskRunnerFactory as the exception paths do.

---

## B. DemandSubscription / ConduitBridge / filters

### B1 [HIGH][HUNG] Missed notify in `DemandSubscription.waitForCompletion()`
- DemandSubscription.java:124-148. `dispatched.get() > 0` is checked OUTSIDE the
  `activeWaiter` monitor with no re-check loop inside; a `notifyAll()` firing
  between check and `wait()` is lost → full 30s sleep, silently. Caller
  `removeSubscription()` runs this on the bridge's SINGLE-thread serialExecutor,
  which also processes destination add/remove — a mass consumer disconnect
  stalls the bridge for 30s × N. Presents as a hung bridge.
- Fix: standard monitor protocol — deadline loop with state check inside the
  monitor; decrement/notify under the same monitor.

### B2 [HIGH][HUNG] Non-permissible destination dispatch leaks `dispatched` count and never acks
- DemandForwardingBridgeSupport.java:1228 increments unconditionally;
  `if (isPermissableDestination(...))` at 1254 has NO else branch — message is
  never forwarded, never acked, never decremented.
- Reachable via wildcard demand sub (consumer on `SOME.>`) + excluded concrete
  destination (`SOME.EXCLUDED`). Local prefetch window fills with permanently
  unacked messages → dispatch to the bridge stops (hung network consumer);
  `dispatched > 0` forever makes every later removal hit the full 30s wait
  (compounds B1).
- Fix: else branch that acks INDIVIDUAL_ACK and decrements, mirroring the
  suppression path at 1230-1240.

### B3 [HIGH][HUNG] Conduit matching race attaches interest to a dying DemandSubscription
- ConduitBridge.java:70-101 (`addToAlreadyInterestedConsumers`) vs 115-133
  (`removeDemandSubscription`); no synchronization between match+`ds.add(id)`
  and `ds.remove()`+`isEmpty()`+map-removal. Adds run on remote-transport AND
  syncExecutor threads; removes on transport threads; cross-bridge calls via
  `removeDuplicateSubscription`.
- Interleaving: A matches ds → B removes last consumer, tears ds down → A adds
  its id to the dead ds and returns matched → no subscription created → remote
  consumer silently receives nothing until reconnect.
- Second window: `configureDemandSubscription` publishes into
  `subscriptionMapByLocalId` (1722) BEFORE duplicate suppression;
  `undoMapRegistration` (1537) can strand conduited interest the same way.
- Fix: per-bridge or per-subscription lock covering both compound sequences;
  re-verify ds is still mapped after acquiring; don't publish into maps until
  suppression passes.

### B4 [MEDIUM] `cleanupDurableSub` check-then-act race
- DemandForwardingBridgeSupport.java:1088-1110 vs ConduitBridge.java:94.
  `durableRemoteSubs.isEmpty()` check races a concurrent conduit add of new
  durable interest → local durable sub torn down while the remote believes
  demand is registered → permanently stalled durable NC sub until bridge
  restart.
- Fix: same lock as B3; re-check emptiness under the lock.

### B5 [LOW] ConditionalNetworkBridgeFilter rate limiter not thread-safe
- ConditionalNetworkBridgeFilterFactory.java:109-110, 170-180: plain
  `matchCount`/`rateDurationEnd` mutated from multiple dispatch threads;
  over-admits and spuriously rejects. Fix: synchronized or atomics.

### B6 [LOW] Stale `subscriptionMapByRemoteId` entry for a conduit sub's founding consumer
- ConduitBridge.removeDemandSubscription never removes the departed founding
  consumer's id unless the whole ds is empty. Bounded staleness; duplex ack
  lookup (line 869) can resolve a long-gone consumer id.

---

## C. DiscoveryNetworkConnector / NetworkConnector / MBeans / LDAP / Multicast

### C1 [HIGH][HUNG] Stale `activeEvents` entry permanently blocks reconnection
- DiscoveryNetworkConnector.java:108 (guard), failure paths 136-141, 150-155,
  171-176. All failure paths rely on `discoveryAgent.serviceFailed(event)`
  round-tripping through `onServiceRemove()` to release the `activeEvents`
  reservation. `serviceFailed` can throw (caught, DEBUG-logged) or no-op
  (SimpleDiscoveryAgent guard: `running` false, or `failed` flag already CAS'd
  by a concurrent failure path) → URI reserved forever → all future discovery
  events for that broker dropped as duplicates. Invisible at default log level.
- Also: SimpleDiscoveryAgent permanently disables reconnect after
  `maxReconnectAttempts` ("Reconnecting has been disabled for:" WARN) — check
  production logs.
- Fix: remove local state directly at the failure site (`activeEvents.remove`,
  `bridges.remove`) before calling `serviceFailed`; WARN-level logging.

### C2 [HIGH][HUNG] onServiceAdd vs handleStop race → zombie bridge + blocked restart
- DiscoveryNetworkConnector.java:84 guard vs 224-233 `handleStop` (which does
  NOT hold `synchronized (bridges)`). An in-flight onServiceAdd blocked in
  `TransportFactory.connect()` can insert and start a bridge AFTER handleStop
  cleared the maps: fully-started bridge under a stopped connector that nothing
  will ever stop, plus a stale activeEvents entry that blocks reconnection after
  connector restart.
- Fix: re-check isStopping/isStopped inside `synchronized (bridges)` before
  put/start; do handleStop's iterate-stop-clear under the same lock.

### C3 [MEDIUM-HIGH] MBeanNetworkListener.onStop() leaks on unregister failure
- MBeanNetworkListener.java:73-89: `unregisterMBean`, map remove, and
  `mBeanBridgeDestination.stop()` share one try block. An
  `InstanceNotFoundException` (reachable via double-unregister) skips the map
  remove AND the stop — the purge scheduler task for the dead bridge fires
  forever, per-destination MBeans stay registered, and the map pins the dead
  bridge. Unbounded under bridge flapping. Mirror problem in onStart
  (InstanceAlreadyExists skips MBeanBridgeDestination creation, stale MBean
  shows dead bridge as up).
- Fix: separate try/catch per step; teardown in finally; unregister stale MBean
  before register in onStart.

### C4 [MEDIUM] onServiceRemove drops the bridge without stopping it
- DiscoveryNetworkConnector.java:193-198: bridge removed from `bridges` but
  never stopped. On graceful service deregistration (or multicast advertisement
  timeout with TCP still up), the orphan bridge keeps forwarding; a re-advertise
  builds a second bridge → duplicate forwarding.
- Fix: dispose the removed bridge.

### C5 [MEDIUM] LdapNetworkConnector never removes child connectors from BrokerService
- LdapNetworkConnector.java:315 vs 371-373/269-278: `addNetworkConnector` but
  never `removeNetworkConnector` → each LDAP change cycle (objectChanged does
  remove+add) leaks a stopped connector + its MBean in
  `BrokerService.networkConnectors`. Unbounded.

### C6 [MEDIUM] LdapNetworkConnector.objectRenamed NPE can kill LDAP event listening
- Lines 434-443: `uuidMap.put(uuidNew, null)` NPEs when the old key misses
  (key-scheme mismatch is baked in per the code's own comment); exception
  escapes into the JNDI event dispatcher → LDAP-driven add/remove silently stops
  → frozen connector set.

### C7 [MEDIUM] LdapNetworkConnector lifecycle defects
- start()/stop() bypass serviceSupport entirely (isStarted/isStopped always
  false); `failover=true` retry loop can hang broker startup forever if LDAP is
  down (line 228); stop() aborts on first child-connector failure leaving the
  rest running and the LDAP context open; context.close() NPE/double-close
  paths.

### C8 [LOW-MEDIUM] MulticastNetworkConnector bridge invisible to `bridges` map
- MulticastNetworkConnector.java:95-116: bridge stored only in a private field —
  `removeDemandSubscription()` and `activeBridges()` can't see it (stale demand
  sub cleanup never works for multicast); handleStart failure leaks connected
  transports.

### C9 [LOW] Minor
- LdapNetworkConnector.getUri() unsynchronized index increment; referenceMap
  zero-count entries never removed; MBeanBridgeDestination start/stop useJmx
  asymmetry; non-volatile transport/bridge fields; NetworkBridgeStatistics
  clean.

---

## D. ForwardingBridge / network/jms (JMS-to-JMS bridge)

### D1 [HIGH][HUNG] JmsConnector reconnect executor can only ever run one task
- jms/JmsConnector.java:648-652: `ThreadPoolExecutor(0, 2, ..,
  LinkedBlockingQueue)` never grows past 1 thread (unbounded queue never
  rejects). With default INFINITE retries, one side's endless reconnect loop
  starves the other side's reconnect forever → connector hung until restart.
- Fix: SynchronousQueue with max 2, or fixed pool of 2, or core=2 with
  allowCoreThreadTimeOut.

### D2 [HIGH][HUNG] DestinationBridge.start() latches started=true even on failure
- jms/DestinationBridge.java:85-91: if createConsumer/createProducer throws,
  `started` stays true; every later start() no-ops. Connector reports connected
  but the bridge has no consumer — messages accumulate silently. "Restart fixes
  it."
- Fix: reset `started` and rethrow on failure.

### D3 [HIGH][HUNG] ForwardingBridge.stop() blocking request() with no timeout
- ForwardingBridge.java:170-171: untimed `request()` to both brokers; a
  black-holed connection hangs stop() → hangs connector restart / broker
  shutdown.
- Fix: `request(cmd, timeout)` or `oneway()`.

### D4 [HIGH][HUNG] ForwardingBridge.serviceRemoteException never fires bridgeFailed
- ForwardingBridge.java:185-188: logs only (contrast serviceLocalException at
  213-217). Remote socket death on an idle destination → bridge sits dead
  forever, no reconnect.
- Fix: call fireBridgeFailed().

### D5 [HIGH] JmsConnector cannot be restarted
- jms/JmsConnector.java:171-199 vs 104-127: stop() nulls `connectionService`
  but `initialized` stays true → restart yields no executor, no doConnectorInit;
  first failure NPEs in handleConnectionFailure. Also a stop/failure race NPE
  even without restart.
- Fix: reset init flags and clear refs in stop(); null-check captured executor.

### D6 [MEDIUM] `replyToBridges` LRUCache unsynchronized across dispatch threads
- jms/JmsConnector.java:73 + SimpleJmsQueueConnector.createReplyToBridge:
  LinkedHashMap corruption can livelock a provider dispatch thread → hung
  bridge. Fix: synchronize access + compound get/create/put.

### D7 [MEDIUM] ExceptionListener reports the CURRENT connection, not its own
- SimpleJmsQueueConnector.java:195-200, 268-273 (same in TopicConnector): stale
  listener passes `foreignConnection.get()` (the fresh connection B) →
  healthy connection torn down → flapping/double reconnect. Fix: capture the
  connection in a final local.

### D8 [MEDIUM] Foreign reconnect doesn't reset outbound bridges' cached producer/session
- SimpleJmsQueueConnector.java:335-352 (consumer side resets, producer side
  doesn't): first send after reconnect throws on the stale producer, tearing
  down the NEW connection — one spurious full teardown per reconnect.

### D9 [MEDIUM] replyToBridges.clear() leaks live replyTo bridges
- jms/JmsConnector.java:499: bridges are started services (sessions, temp
  queues); clear() drops refs without stop(). Slow leak per connection failure.

### D10 [MEDIUM] QueueBridge/TopicBridge.stop() session close not exception-safe
- jms/QueueBridge.java:44-52 / TopicBridge.java:46-55: first close throwing
  skips the second — leaks one healthy-side session per reconnect cycle.

### D11 [MEDIUM] ForwardingBridge consumer registered before producerInfo assigned
- ForwardingBridge.java:147 vs 149-151/227: dispatch can race startup → NPE on
  the transport thread; shared bridge state fields have no safe publication.

### D12 [LOW] ReconnectionPolicy quirks
- setSendRetyDelay floor is dead code and sendRetryDelay is never used
  (jms/ReconnectionPolicy.java:79-85, DestinationBridge:110); "exponential"
  backoff is actually linear (line 236-238); DestinationBridge.onMessage retry
  loop can pin the provider dispatch thread; ForwardingBridge enqueueCounter
  overcounts on failed forwards; `setUseBrokerNameAsIdSees` public API typo;
  setNetworkTTL silently overwrites consumerTTL/messageTTL (order-sensitive
  XML).

---

## Diagnosing the production hang

Take a thread dump on an affected broker and look for:

1. Threads in `DemandForwardingBridgeSupport.safeWaitUntilStarted` polling
   forever, or parked in `FutureBrokerInfo.get` → A1/A2 (or the known
   FutureBrokerInfo || bug).
2. A remote transport thread parked in `localStartedLatch.await()` → A3.
3. Threads blocked on a monitor whose owner is inside `addConsumerInfo` /
   `removeDuplicateSubscription` (the `vm://` URI object) → A4.
4. The bridge's `pool-N-thread-1` serialExecutor thread inside
   `DemandSubscription.waitForCompletion` (Object.wait) → B1/B2.
5. `SimpleDiscoveryAgent` "Reconnecting has been disabled" WARN in logs → C1.
6. Connector "up" in JMX with no bridge and no reconnect attempts → C1/C2
   (stale activeEvents; only DEBUG evidence).
7. Growing `pool-*` thread counts → A7.

## Self-healing configuration (verified in this codebase)

Detection:
- `wireFormat.maxInactivityDuration=30000` (default, ON) — negotiated to the
  min of both sides; InactivityIOException → serviceRemote/LocalException →
  bridgeFailed → discovery-agent reconnect. Primary self-heal loop.
- `soWriteTimeout=30000` on the nested tcp URI (WriteTimeoutFilter, default
  DISABLED) — converts a 13-30 min TCP retransmit hang on a stalled peer into a
  fast failure. The single most important missing default for stall healing.
- `keepAlive=true` on the tcp URI — cheap OS-level backstop.

Reconnect:
- `static:` discovery (SimpleDiscoveryAgent): initialReconnectDelay=1000,
  maxReconnectDelay=30000, exponential backoff, maxReconnectAttempts=0 =
  RETRY FOREVER. Do NOT set maxReconnectAttempts > 0 — it permanently disables
  reconnect after the limit.
- Prefer plain `static:(tcp://...)` over `static:(failover:(...))` — failover
  masks transport failure from the bridge (onException never fires while it
  retries), which can strand demand-subscription state; use failover inside
  static only for master/slave pairs.

Bridge configuration:
- `prefetchSize=500` (default 1000) — bounds messages stranded on a hung bridge.
- `advisoryPrefetchSize=100` (default 0 = shares prefetchSize) — decouples
  advisory flow from message flow; advisory prefetch exhaustion silently stops
  new demand-subscription creation.
- `duplex=false` where possible — two simplex connectors heal independently;
  a duplex stall kills both directions and only the originating side reconnects.
- Keep `dispatchAsync=true`, `alwaysSyncSend=true` (defaults) — sync send +
  soWriteTimeout converts hung remotes into detectable failures.
- `staticBridge=true` + staticallyIncludedDestinations removes the advisory
  dependency entirely for fixed topologies.

Broker-side (destination policy / system usage):
- `abortSlowAckConsumerStrategy` with `ignoreNetworkConsumers="false"` and
  `maxTimeSinceLastAck="300000"` — by DEFAULT slow-consumer strategies IGNORE
  network subscriptions (ignoreNetworkConsumers=true), so a hung network
  consumer is never aborted. Aborting it closes the bridge connection and
  triggers reconnect. Keep the threshold well above legitimate flow-control
  pauses to avoid flapping.
- `sendFailIfNoSpaceAfterTimeout="60000"` in systemUsage — converts indefinite
  producer-flow-control blocking of bridge forwards into a failure the bridge
  can react to.

Reference connector:

```xml
<networkConnector name="A-to-B"
    uri="static:(tcp://brokerB:61616?soWriteTimeout=30000&amp;keepAlive=true&amp;wireFormat.maxInactivityDuration=30000)?initialReconnectDelay=1000&amp;maxReconnectDelay=30000&amp;useExponentialBackOff=true"
    prefetchSize="500"
    advisoryPrefetchSize="100"
    dispatchAsync="true"
    alwaysSyncSend="true"
    duplex="false"/>
```

Note: there is no `localRequestTimeout` option on the network bridge in this
codebase (verified by grep) — write-hang protection comes from `soWriteTimeout`.

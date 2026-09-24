# All-Consumer Dispatch Stall Investigation — Status (2026-08-19)

Production incident on ActiveMQ Classic 5.18.7 (Azure Kubernetes, recent OS and
K8s patching): on broker A, **all** message consumption stalls — application
consumers AND network consumers — while new messages continue to arrive.
Reported recovery: restarting a network connector, after which consumption
resumes for all consumer types. No messages dispatched-unacked, nothing visible
on a fresh browse, QueueSize positive and growing.

This document records what has been reviewed, what was ruled out, what was
reproduced, where every artifact lives, and what is still open. Work is on
branch `amq-gh-2196-queue-consumer-starvation` (tests in
`activemq-unit-tests/src/test/java/org/apache/activemq/bugs/`).

---

## 1. Production profile (as stated by the user)

| Item | Value |
|---|---|
| Broker | 5.18.7, AKS, throttled cloud storage (disk I/O throttling) |
| Queue policy | `maxPageSize=64` (default 200 — "the key change"), `maxBrowsePageSize=64`, `useCache=false`, `optimizedDispatch=true`, `memoryLimit=512 MB`, `producerFlowControl=true`, `enableAudit=true`, `maxAuditDepth=10000` |
| Consumers | ~16 app consumers, Spring DMLC, **transacted, 1 message per commit**; 4 network consumers; many producers (up to 64) |
| Bridge | non-duplex, prefetch 100, URI `?wireFormat.maxInactivityDuration=10000&soTimeout=10000&soWriteTimeout=10000&keepAlive=true` |
| Selectors / message groups | **none** on app consumers |
| Scale | dozens-to-hundreds of queues doing the same |
| Transport | nio, `dedicatedTaskRunner=false` (pooled) |

Production evidence in hand: 5 thread dumps (2026-07-02, 60s apart) during a
stall — **zero BLOCKED threads, zero threads in queue dispatch, all pooled
`Task-N` threads idle, all 16 bridge transport threads identical across all 5
snapshots (7.9-day and 23–27-day-old connections: NO flapping, NO reconnect)**.
One transient producer blocked in KahaDB `Journal.write` (throttled disk visible).
Conclusion from the dump: the queue is being woken (arrivals) and **dispatching
nothing** — not blocked, not churning.

---

## 2. Dispatch flow reviewed (Queue.java, 6.x tree; same code shape in 5.18)

Page-in: `iterate()` → `pageInMessages()` → `doPageInForDispatch()`:
- `toPageIn = min(maxPageSize, messages.size())` — **store cursor size**
- lazyDispatch clamp; page-in **gate**: `pagedInPendingSize < maxPageSize`
  (sole condition pre-fix) — `pagedInPendingSize = dispatchPendingList.size()`
- memory gate: `!messages.hasSpace()` → "cursor blocked, no space available to
  page in messages" (70% of queue memoryLimit)
- `haveRealConsumer()`

Dispatch: `doDispatch()` → `doActualDispatch(list)` per message × consumer:
- skip browsers; skip consumers in `fullConsumers`; **`isFull()`** =
  `dispatched.size() - prefetchExtension >= prefetchSize` (prefetchExtension is
  clamped ≥0 — reviewed, cannot go negative)
- dispatch only if `canSelect(s,node)` (selector, exclusive consumer, paused)
  **&& `assignMessageGroup`** **&& `!node.isAcked()`**
- only `isDropped()` nodes are removed when undispatched; full-consumer early
  return when `consumers.size()==fullConsumers.size()`
- `dispatchAsync`: node added to `dispatched` **before** the wire write
  (connection `dispatchQueue`, unbounded)

Wakeup: `Queue.wakeup()` with `optimizedDispatch=true` runs `iterate()` inline
(flag `iterationRunning`, mutex `iteratingMutex`); otherwise pooled
`TaskRunner`. Connections and destinations share **one**
`BrokerService.taskRunnerFactory` (verified lines 2402/2764).

---

## 3. Mechanisms examined, verdicts, and artifacts

### 3.1 Page-in gate with undeliverable messages — REAL, but requires selectors/groups
`NetworkConsumerPriorityDispatchStarvationTest` (11 tests, 3 fail pre-fix / 11 pass
with fix). Key facts established:
- A stalled bridge **alone never starves a compatible (unselective) consumer**
  — the clog self-clears to any consumer with room (pinned).
- Starvation needs pending messages the healthy consumer **cannot** take:
  selector split, or **message groups** owned by a stalled consumer
  (`testStalledGroupOwnersStarveHealthyConsumerOfItsOwnGroups`: 19/64 pre-fix,
  66/64 with fix; prefetch 1 vs 5 makes no difference to the victim).
- Since production has neither, this is **not** the production mechanism as
  originally framed — but see 3.6, which reaches the same gate by another route.
- lazyDispatch=true masks the bridge scenario; default false used.
- A JMS consumer with `consumer.priority=-5` **cannot** model a stalled bridge
  (DELIVERED_ACK → prefetch extension); a real bridge + real remote is required.

### 3.2 Memory gate (large message pinned past 70% high-water) — REAL, ruled out at 512MB
`testLargeMessageHeldByStalledBridgeBlocksCursorOnMemory` captures the broker's
own WARN. With a 512MB limit, 100 held messages would need to average ~3.5MB.
Unlikely.

### 3.3 Frozen network link + bridge — self-heal analysis; **soTimeout bug found**
`testTransportLevelStallSelfHealsWithInactivityMonitor` /
`testSoTimeoutDefeatsInactivityMonitorOnFrozenLink` (in-JVM freezable TCP
proxy). `maxInactivityDuration=10000` detects a black-holed link in ~30s
(initial delay + TWO silent read checks) and the bridge self-heals — **but
adding `soTimeout=10000` (the production URI) DISABLES detection**: soTimeout
makes blocked reads throw every 10s, `TcpTransport.doRun` swallows and retries,
`receiveCounter` increments on read *attempts*, `readCheck()` sees the counter
move and skips ("A receive is in progress") forever. Same freeze: still stalled
at 60s+. **Action: remove `soTimeout` from network connector URIs.** (Separate
upstream bug: count bytes not attempts / ignore timed-out reads.)
Plain app consumer + frozen link: drains (bridge isolation works) — so a frozen
link alone does **not** stall app consumers.

### 3.4 AbortSlowAckConsumerStrategy — works as interim remedy, with a trap
`testAbortSlowAckConsumerStrategyClearsStalledBridgeStarvation`: clears a
bridge-induced stall on the unpatched broker (automated bounce) **only with
`ignoreNetworkConsumers="false"`** — the default exempts bridges entirely.

### 3.5 Candidates examined and ruled out (no reproduction)
- Inline dispatch block via VM transport: default `dispatchAsync=true` → bridge
  end is sync, but the forward is `asyncRequest` and does not block the
  dispatch thread.
- Lost wakeup on the optimizedDispatch path (`OptimizedDispatchLostWakeupProbeTest`):
  46k msgs, 8×8 concurrency, none.
- Store-cursor counter drift to 0 (`StoreCursorPendingCountDriftTest`,
  `BusyQueueCursorDriftStressTest`): **consequence pinned** (inner
  `AbstractStoreCursor.size`==0 with backlog → `toPageIn=0` → total stall,
  one-for-one arrival trickle, consumer churn does not heal, cursor `gc()`
  heals) but **cause not reproducible**: 265k msgs heavy-backlog and 83k msgs
  near-empty production-shaped runs (64 producers, 16 tx consumers, 4 bridges,
  nio, 40 background queues, bursty throttled store) — counters exact every
  sample. Superseded as the leading model by 3.6.
- Blocked outbound consumer writes filling `dispatched`: prediction falsified
  by the July dump (0 `Connection Dispatcher` threads, 0 blocked writes).
- Flapping/trickle: July dump shows stable bridges; not that incident.

### 3.5b messageTTL / origin filtering on network subs ("works like a selector") — real mechanics, NOT a stall
`testRemoteOriginTrafficLowersPhantomThreshold` (commit 0c274fde5f). The
`NetworkBridgeFilter` IS the network sub's `additionalPredicate`, evaluated in
`doActualDispatch`: remote-origin messages (brokerPath contains another broker)
are rejected by every network sub on A (origin check + `messageTTL=1`), and with
`replayWhenNoConsumers=true` replay is suppressed whenever ANY app consumer
*exists* (`hasNoLocalConsumers` ignores fullness). So on A the network subs are
permanently ineligible for inbound traffic. But those messages are fully
deliverable to the app consumers, which carry no such filter: 200 remote-origin
messages with 10 phantoms planted → app consumer 200/200, pending unchanged.
A filter excluding only the network subs cannot starve app consumers; at most a
transient throttle when app consumers are momentarily full. Does not lower the
phantom threshold.

### 3.5c Un-acked advisories on the bridge (count-based acks) — premise true, NOT coupled to queue dispatch
`testUnackedAdvisoriesOnBridgeDoNotCoupleToQueueDispatchOrUnclogOnRecycle`
(commit f98d3f8e50). Premise verified: `ackAdvisory` acks by COUNT only past
`advisoryPrefetchSize(=prefetch when 0) × advisoryAckPercentage(75%)`, so a
quiet bridge holds ~75 advisories un-acked indefinitely and a recycle flushes
them. But with 60 consumer add/remove cycles loading the advisory sub on a
phantom-stalled queue: still 0/200 before the recycle, still 0/200 after it,
phantoms untouched. No coupling: advisory sub is a TOPIC sub on the REMOTE
(own cursor/memory); per-message advisories are disabled in prod policy;
`AdvisoryBroker.removeConsumer` fires the advisory after `super.removeConsumer`
returns, outside region locks. **Untested follow-up:** advisory sub pushed past
prefetch + PFC on the advisory topic → `fireAdvisory`→`Topic.send`→
`waitForSpace` could block the consumer-removal transport thread (a
wedged-bridge secondary effect; `advisoryAckInterval` on the
network-connector-fixes branch is the preventer).

### 3.6 **ACKED PHANTOMS in dispatchPendingList — REPRODUCED with plain consumers**
`AckedPhantomInDispatchPendingListTest` (commits 74e5341460, 1d24072528).
Mechanism (all in the reviewed code):
1. Transacted consumer acks → `Queue.removeMessage` sets `setAcked(true)` at
   ack; `dropped` only on commit's `dropMessage`.
2. Connection dies before commit → `TransactionBroker.removeConnection` rolls
   back first, but `LocalTransaction.rollback()` writes a
   `KahaRollbackCommand` to the **journal before** `fireAfterRollback()`; if
   that write throws (**throttled storage**), `setAcked(false)` never runs;
   TransactionBroker catches (`ERROR Rolling back disconnected client's
   transactions`) and **continues** to remove the subscription.
3. `Queue.removeSubscription` redispatches held messages via
   `dispatchPendingList.addForRedelivery`, filtering `isDropped()` but **not
   `isAcked()`** → acked phantom enters the pending list.
4. `doActualDispatch` refuses acked nodes for every consumer and only removes
   dropped ones; `browse` skips acked. **Nothing ever purges them.** Each is a
   permanent, invisible `maxPageSize` slot.
5. At `maxPageSize` phantoms the page-in gate shuts for **everyone**.

Measured (pre-fix jar, maxPageSize=20): 1 event → 1 phantom, healthy plain
consumer receives 0, browse 0, QueueSize 1. 20 phantoms → healthy plain
consumer **0 of 200**, inflight 0, pending pinned at 20, QueueSize 220. Explains:
plain consumers, `maxPageSize=64` as the surfacing threshold, browse-empty,
idle dispatch threads, cloud-storage sensitivity, disconnect churn from K8s
patching. A browse force-pages the store and momentarily unsticks a burst.

**The `[#2160]` page-in fix on this branch resolves the dispatch symptom**: same
20 phantoms, consumer drains 200/200 (gate opens for a consumer with capacity).
The phantom **leak itself is a separate defect** (don't redispatch acked nodes /
don't strand on rollback failure) — not yet fixed.

**Recovery test — connector recycle does NOT clear a phantom stall**
(`testNetworkConnectorRecycleDoesNotClearPhantomStall`): bridge sub removed
and re-added, app consumer still 0/200, all phantoms untouched. The phantoms
live in the queue, not the bridge.

---

## 4. Current standing

The phantom mechanism (3.6) reproduces **every observable of the stall** with
plain consumers on the unpatched dispatch code, and its dispatch symptom is
resolved by the `[#2160]` fix already on this branch. It does **not** explain
the reported recovery-by-connector-restart, and the July dump's bridge
connections (7.9 / 23 days old) were not being recycled during that window.

Most plausible reconciliation: the operational "connector restart" coincides
with something broader — a broker/pod bounce (which reseeds every queue from
the store and clears all phantoms) — rather than a JMX connector stop/start.
Unconfirmed.

---

## 5. Open questions (decide the next step)

1. **What does the ops "restart the network connector" actually execute on
   broker A?** JMX connector stop/start (→ should not help per 3.6) vs broker /
   pod bounce (→ that is the heal).
2. **Grep broker A logs for `ERROR Rolling back disconnected client's
   transactions`** over the days before a stall — each is one phantom; expect
   accumulation toward `maxPageSize=64`. Confirms/kills 3.6 independently.
3. **`org.apache.activemq.broker.region.Queue` at DEBUG for 30s during a
   stall** (user will capture on next occurrence):
   - `pagedInPendingDispatch.size ≥ 64` with `toPageIn: 0` → page-in gate
     (3.6 phantoms, or another undeliverable source)
   - `toPageIn: 0` with `queueSize > 0` and pending < 64 → cursor size (3.5)
   - `memUsage` ≥ 70% of limit → memory gate (3.2)
   - `toPageIn > 0` yet nothing dispatching → stall is after page-in (unprobed)
4. After a recovery: full drain, or burst-then-stall?

---

## 6. Recommended actions now (independent of final root cause)

- **Remove `soTimeout` from network connector URIs** (3.3) — it silently
  disarms `maxInactivityDuration`.
- **Ship the `[#2160]` page-in gate fix** — resolves the dispatch symptom of 3.6
  and the group/selector cases of 3.1.
- **Fix the phantom leak** (test-first): `Queue.removeSubscription` must not
  redispatch acked-uncommitted nodes, and/or the failed-rollback path must not
  strand `acked=true`.
- Interim on unpatched brokers: `abortSlowAckConsumerStrategy` with
  `ignoreNetworkConsumers="false"` (3.4) for bridge-induced stalls; note it does
  not clear phantoms.
- Consider raising `maxPageSize` back toward the default as mitigation for 3.6
  (raises the phantom threshold) if the fixes cannot ship quickly.

---

## 7b. PRODUCTION CONFIRMATION (2026-08-21) — mechanism revised

Queue DEBUG capture from the live stall (`DR.COMMON`, 5.18.7):
`subscriptions=6, size=364940, toPageIn: 512, Inflight: 0, pagedInMessages.size 548,
pagedInPendingDispatch.size 548, maxPageSize:512, memUsage 20MB`. Decoded against
source: cursor awake (`toPageIn = min(maxPageSize, messages.size())`), memory gate
irrelevant, every paged-in message undispatched, nobody full, gate shut (548 >= 512).

**Recovery report: stopping the network connector let app consumers flow; starting
it let the bridge flow.** This RULES OUT acked phantoms as the production cause
(phantoms are untouched by a connector recycle — proven). `Queue.removeSubscription`
releases only: exclusive-consumer election, the sub's dispatched list (empty at
Inflight 0), and `getMessageGroupOwners().removeConsumer(consumerId)`. With no
exclusive consumers configured, **message groups owned by the network subscription**
is the only mechanism a connector stop can release.

Reproduced on vanilla 5.18.7 in the standalone module:
`NetworkOwnedGroupStarvationTest` — seed groups so the network sub owns them, members
arrive from the remote broker (filter-refused for the owner, ownership-refused for app
consumers), gate shuts at maxPageSize, app 0/50 + bridge 0 with Inflight 0; connector
stop → app drains 90/90; connector start → bridge forwards 30/30. Green in ~10s at
maxPageSize=20; also verified at 64.

**Mitigation PROVEN (stock config):** `messageGroupMapFactoryType="cached?cacheSize=0"`
— LRU eviction is `size() > maximumSize`, so 0 evicts every put, the ownership map is
permanently empty and `assignMessageGroup` is always true. Green control
`testCachedCacheSizeZeroDisablesGroupAffinityAndPreventsStall`: same trap, ownership
never recorded, all members + plain traffic consumed, pending 0, no connector action
(verified at maxPageSize 20 and 64). Cost: per-group ordering off on that policy.
`cacheSize=1` is NOT equivalent (forgets an owner only when another group is touched).

**Decision 2026-08-21 — owner reassignment BACKLOGGED.** Forfeiting a group from an owner
that cannot be dispatched a member (selector / bridge filter) would break the group
ordering contract (old owner may still hold earlier members while a new owner gets
later ones). Not to be built without a new decision. Shipped instead:
`PolicyEntry.useMessageGroups` (default true; false = JMSXGroupID ignored for dispatch,
no ownership recorded, no JMSXGroupFirstForConsumer; configure + runtime update paths)
on branch `amq-policy-use-message-groups`, with `UseMessageGroupsPolicyTest`
(default keeps a group on one consumer; false spreads it and records nothing; the
selector-owner stranding scenario stalls with the default and drains with false).

**CONFIRMED 2026-08-21: `JMSXGroupID` is in use on DR.COMMON.** Together with the
connector stop/start recovery signature this confirms the network-sub-owned-group
mechanism. Optional corroboration: queue MBean `MessageGroups` during a stall (group IDs
→ bridge consumer ID), `JMSXGroupID` on browsed messages, remote-side producers to the
same queue. Fix direction: `assignMessageGroup`/`doActualDispatch` must allow
ownership reassignment when the owner is present but cannot take a member
(filter-rejected); the [#2160] page-in guard only prevents the TOTAL stall.

## 7a. Standalone reproducer module (vanilla 5.18.7, JDK 11, javax.jms)

`~/git/hyte/MERGES_ONLY/activemq-queue-stall-repro` — modelled on
`activemq-unicode-roundtrip`. Embedded 5.18.7 broker with the production policy by
default; `-Dstall.url=` for a live broker (white-box scenarios skip with a reason).
**Pass convention: a test PASSES when it DEMONSTRATES the defect.** All 9 verified
green against vanilla 5.18.7 (3 min at `-Dstall.maxPageSize=20`; also verified at 64):

- `AckedPhantomStallTest` (3, embedded-only) — plain-consumer total stall: 64 phantoms
  → 0/200, inflight 0; connector recycle does not clear it.
- `MessageGroupStarvationTest` (2, **live-capable**) — 41/200 at maxPageSize=64,
  19/200 at 20; prefetch 1 same.
- `NetworkBridgeStallTest` (7, embedded-only) — stalled bridge alone drains (92/92);
  inactivity monitor detects frozen link in ~30s; **production URI with
  `soTimeout` never detects it**. NEW (2026-08-19, hand-crafted stalled-link
  validation): **AbortSlowAckConsumerStrategy is DETECTION-ONLY for bridges** —
  with `ignoreNetworkConsumers=false` + `abortConnection=true` it aborts the hung
  bridge on schedule (busy ~12s, idle ~8s with `ignoreIdleConsumers=false`; idle
  never flagged with the default `true`) but the stop path is a graceful bridge
  stop that bypasses `serviceLocalException`/`fireBridgeFailed`, so
  `discoveryAgent.serviceFailed` never re-schedules the URI and the **bridge never
  reconnects** (backlog stranded 30/500 after 45s). `abortConnection=false` is a
  zombie (sub force-removed, connection up). Positive control through the same
  proxy: URI without `soTimeout`, no strategy → detected 27.5s, reconnected,
  500/500 delivered by 28.6s. **Removing soTimeout is the only complete fix**;
  the strategy can only be a kill-switch paired with an external restarter.
  Manual live-broker validation: `StalledLinkProxyTool`
  (`mvn -q test-compile exec:java -Dexec.args="peerHost:61616 9716"`, `f`=freeze
  dead-socket style, `F`=freeze all, `t`=thaw) — procedure in the module README.

## 7. Artifact index (branch `amq-gh-2196-queue-consumer-starvation`)

| Commit | Content |
|---|---|
| `e0debecc3c` | original test (selector scenario) |
| `1ed5ae8d95` | `[#2160]` page-in gate fix (`Queue.doPageInForDispatch`) |
| `231b86e248` | expanded NetworkConsumerPriorityDispatchStarvationTest: real bridge, bounded page-in, memory gate, abort strategy |
| `4099fb61f0` | transport-stall controls: inactivity self-heal + **soTimeout defeats it** |
| `616c1fb5b5` | message-group selector-free starvation repro |
| `6ef6e8d31e` | prefetch=1 variant |
| `dd66eb391e` | StoreCursorPendingCountDriftTest (consequence pin + cause watchdog) |
| `fbecf19331` | BusyQueueCursorDriftStressTest (production-shaped harness) |
| `74e5341460` | **AckedPhantomInDispatchPendingListTest — plain-consumer total stall reproduced** |
| `1d24072528` | connector recycle does NOT clear phantom stall |
| `0c274fde5f` | remote-origin/TTL-rejected traffic does NOT harden the phantom clog |
| `f98d3f8e50` | un-acked bridge advisories neither cause nor clear the phantom stall on recycle |

Also on other branches: `OptimizedDispatchLostWakeupProbeTest` (uncommitted probe,
in working tree). Related docs: `network-connector-backlog.md`,
`network-connector-review-2026-07.md`, `queuesize-drift-deep-dive.md`.

Replay note: `activemq-unit-tests` resolves sibling modules from `~/.m2` —
`mvn install -pl activemq-broker -DskipTests` (plus `activemq-client` /
`activemq-kahadb-store` where changed) after every checkout before running
tests, else results reflect a stale jar.

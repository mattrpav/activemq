# QueueSize Deep Dive — KahaDB + Transacted Consumers

Investigation of production queueSize errors: KahaDB store, queues configured
`useCache=false` / `maxPageSize≈60`, many producers, Spring DMLC consumers with
local transactions (1 message per commit), multiple network consumers attached.
Symptom: after full drain, `destinationStatistics.getMessages()` > 0 while
browse is empty, inflight is 0, and nothing is dispatched-unacked.

Four parallel code audits (send/duplicate paths, transacted ack paths, network
consumer paths, full accounting map). Line numbers from branch
`amq-dupe-from-store-metrics` (6.3.0-SNAPSHOT tree), July 2026.

## The accounting model

The queue `messages` counter has exactly:
- **4 increment sites**: `Queue.messageSent` (Queue.java:2030) for every
  successful send; startup seeds at Queue.java:334/364/424
  (`store.getMessageCount()` reseed at :424 is the path for store cursors).
- **1 decrement site**: `Queue.dropMessage` (Queue.java:1961-1984), guarded by
  `reference.dropIfLive()` (atomic, per reference object).
- XA-recovery compensation in TransactionBroker.java:181/192 (prepared XA only,
  irrelevant for DMLC local TX).

Queue size inflation ⇔ a counted message whose reference never reaches
`dropMessage()` in live state. `pagedInMessages` is keyed by MessageId (no
double refs); redispatch reuses the same reference object; the duplicate
paged-in handler (Queue.java:2188) *decrements* (queue size goes negative, not positive).

## Verified-balanced paths (no queue size error possible)

- Non-tx and transacted send/consume happy paths; rollback + redelivery +
  re-ack (single decrement via dropIfLive).
- Connection death before commit (TransactionBroker rolls back ALL local TXs
  BEFORE consumers are removed, TransactionBroker.java:337-368).
- Poison ack → DLQ (copy resent to DLQ; source decremented exactly once;
  poison acks cannot be transacted).
- Duplicate/late acks after failover (okForAckAsDispatchDone +
  assertAckMatchesDispatched guards).
- Expiry racing an uncommitted transacted ack (lock + acked gates + one-shot
  CAS: single decrement either way).
- Pure sync duplicate producer sends with `useCache=false`: KahaDB silently
  rejects the row (MessageDatabase.java:1450-1471, returns -1, "Duplicate
  message add attempt rejected"), and the -1 gate in
  `doPendingCursorAdditions` (Queue.java:830-837, "suppressing duplicate
  dispatch") drops the candidate BEFORE messageSent counts it. Same for
  transacted duplicate sends (commit-time index update happens before
  postCommit → gate sees -1).
- Bridge normal consume (INDIVIDUAL_ACK, DemandForwardingBridgeSupport:1269/
  1286 → PrefetchSubscription individual-ack branch → dropMessage once);
  bridge teardown redispatch; NetworkBridgeFilter denials; conditional replay
  (audit rollback at Queue.java:1945-1952 exists for re-arrival).
- Crash between store-commit and afterCommit: restart replays commit and
  reseeds the counter from `store.getMessageCount()` — restart heals
  region-level queue size error.

## Ranked queue size inflation candidates

### D1 — afterCommit chain break skips dropMessage (TOP for this profile)
  **[REPRODUCED + FIXED 2026-07-07 — TransactedAckPostCommitQueueSizeTest;
  fix commit "Isolate synchronization failures in fireAfterCommit and
  fireAfterRollback" on branch amq-tx-post-commit-drift: both methods now
  run every synchronization, log per-sync failures, rethrow the first one
  afterwards; fireBeforeCommit deliberately unchanged (pre-durability).
  Repro injects a one-shot RuntimeException from the subscription pending
  cursor's reset() scoped to afterCommit callers; pre-fix result:
  messages=1, enqueues=1, dequeues=0, store empty, inflight 0.]**
- **Mechanism**: for a transacted ack, two synchronizations are registered in
  order [subSync (PrefetchSubscription.java:403-431), queueSync
  (Queue.java:1929-1943)]. `Transaction.fireAfterCommit`
  (Transaction.java:136-143) has NO per-sync exception isolation — the first
  throw skips all later syncs. subSync.afterCommit calls `wakeup()` and
  `dispatchPending()`; with `useCache=false`, dispatchPending reaches
  `pending.reset()` → `AbstractStoreCursor.fillBatch` (store I/O wrapped in
  RuntimeException, AbstractStoreCursor.java:179-188) **inside every commit**.
  One transient store/cursor exception → store ack already durable
  (index applied before postCommit, MessageDatabase.java:1337-1369,1109-1111)
  but queueSync.dropMessage never runs → **+1 forever**.
  `LocalTransaction.commit:72-79` then calls rollback(): fireAfterRollback →
  `setAcked(false)` leaves a phantom ref in pagedInMessages (browse-visible);
  if the rollback itself throws first, `acked` stays true → **browse-empty**
  variant matching the production fingerprint.
- **Why this profile**: DMLC 1 msg/commit = maximal commit-chain executions;
  useCache=false puts store I/O in every chain.
- **Forensics**: `POST COMMIT FAILED` (Transaction.java:215) / `Store COMMIT
  FAILED` (LocalTransaction.java:73) WARN/ERROR — each occurrence ≈ 1 unit of
  queue size error. Restart heals (reseed).
- **Fix direction**: exception-isolate each synchronization in
  fireAfterCommit (and fireAfterRollback); and/or catch dispatch exceptions
  inside subSync.afterCommit (a dispatch failure must not void accounting);
  consider ordering queueSync (accounting) before subSync (dispatch).

### D2 — expiry decrement lost to swallowed store exception
  **[REPRODUCED + FIXED 2026-07-07 — ExpiredMessageStoreFailureQueueSizeTest;
  fix commit "Retry expiry processing when removing the expired message
  from the store fails" on branch amq-expiry-remove-fail-drift:
  Message.resetProcessAsExpired() added (activemq-client) and
  Queue.messageExpired's catch now resets the CAS so a later sweep retries.
  Repro: ProxyMessageStore throwing IOException once during the periodic
  sweep; pre-fix: messages=1, dequeues=0, storeCount=1 permanently;
  post-fix: retry heals to messages=0, storeCount=0. Known trade-off:
  expired stat + advisory fire once per attempt (expired=2 after one
  retry).]**
- **Mechanism**: expiry is gated by a one-shot CAS
  (`Message.canProcessAsExpired`, Message.java:861-863; RegionBroker.java:776).
  The CAS winner must complete removal+decrement. `Queue.messageExpired`
  (Queue.java:1990-2006) swallows IOException from
  removeMessage/acknowledge ("Failed to remove expired Message from the
  store", :2004) — decrement skipped, CAS consumed, every later encounter
  takes a loser branch that removes the ref from in-memory lists WITHOUT
  decrementing (doBrowseList:1252-1259; doPageInForDispatch:2148-2153 discards
  without decrement; PrefetchSubscription.java:657-667).
- **Variants**: page-in-expiry failure → ref never enters pagedInMessages →
  browse-empty immediately. Store row may remain (remove failed) → the queue size error can
  SURVIVE restart in store count until a fresh Message object gets a new CAS
  and expiry retries successfully.
- **Requires**: TTL'd messages + store hiccup. Forensics: the :2004 ERROR
  line; ExpiredCount incrementing without matching dequeues.
- **Fix direction**: on store-remove failure, do not consume the outcome —
  either still dropMessage (if removal will be retried) or reset/retry the
  expiry; at minimum make the failure loud and accounted.

### D3 — mixed async/sync duplicate-send race (only where cache is ever ON)
  **[REPRODUCED + FIXED 2026-07-07 — DuplicateSendStoreRaceQueueSizeTest;
  fix commit "Reject sync duplicate add racing a pending async add" on
  branch amq-dupe-from-store-metrics: KahaDBStore.addMessage consults
  asyncTaskMap for a pending async add of the same MessageId (sync callers
  identified by null FutureOrSequenceLong) and marks -1 without journaling]**
  Deterministic repro: park KahaDB's queueExecutor (reflection), original sent
  via useAsyncSend producer (counted + cached, index update parked), cursor
  cache force-disabled, duplicate replayed through Broker.send() → sync path
  finds empty index → real sequence → passes the -1 gate → counted again.
  Result after drain: messages=1, enqueues=2, dequeues=1, dupFromStore=1 —
  permanent +1 with exactly-once delivery. NOTE: a sync producer cannot hit
  this (persistent send blocks on the store future); an ASYNC-send producer
  (useAsyncSend=true) is required for the original, which narrows the
  affected population.
  **Proposed fix (store-local)**: KahaDBStore.addMessage (sync path) should
  consult asyncTaskMap for a pending async ADD of the same MessageId and
  treat a hit as a duplicate (set sequence -1) — the existing Queue-side -1
  gate then suppresses the count. Alternative mitigations:
  concurrentStoreAndDispatchQueues=false, larger producer audit.
- **Mechanism** (ruled out on strictly useCache=false queues; applies to
  default-policy queues or cache flapping): original send accepted on the
  ASYNC path (counted, dispatched from cache, index update deferred to
  StoreQueueTask); failover resend on another connection takes the SYNC path
  before the original's task runs → index has no row yet → real sequence →
  passes the -1 gate → tryAddMessageLast with cache disabled does no audit →
  **counted again** (messageSent). Original's deferred task then loses
  ("Duplicate message add attempt rejected" on the async executor thread).
  One row, two counts, one ack → +1. Producer audit does not protect:
  `producerSequenceIdTracker` is a 64-producer LRU (ActiveMQMessageAuditNoSync)
  and application-level reconnects get no audit (ProducerBrokerExchange
  .canDispatch:138-166 armed only on failover reconnect,
  TransportConnection.java:1618-1620).
- **Forensics fingerprint**: async-thread "Duplicate message add attempt
  rejected" WITHOUT a matching "suppressing duplicate dispatch" (Queue:834)
  and WITHOUT duplicateFromStore for the same id.
- **Mitigations**: concurrentStoreAndDispatchQueues=false; raise producer
  audit capacity; connection-level auditing for reconnecting producers.
- **Fix**: KahaDBStore.addMessage (sync path, null FutureOrSequenceLong)
  consults asyncTaskMap for a pending async add of the same MessageId and, on a
  hit, marks the id -1 and returns without journaling; the existing cursor -1
  gate then suppresses the duplicate. Pinned by DuplicateSendStoreRaceQueueSizeTest
  (sync duplicate) and AsyncDuplicateSendStoreRaceQueueSizeTest (async duplicate,
  useCache true and false, suppressed by the cursor audit / -1 gate).
- **useAsyncSend durability caveat (non-transacted)**: the fix suppresses the
  racing sync copy, so that copy no longer survives if the pending async
  original later fails to store. A `useAsyncSend` producer sends one-way
  (responseRequired=false), so a deferred StoreQueueTask failure is NOT reported
  back to it (doMessageSend only observes the task result when responseRequired,
  Queue.java:939) - it learns only if the failure drops the connection, in which
  case failover replays the message. If the store failure is tolerated by the
  IOExceptionHandler without dropping the connection (e.g. no-space with the
  default ignoreNoSpaceErrors=true), the producer is not notified and will not
  retry, so the message can be lost. This is a pre-existing property of
  useAsyncSend that the fix widens only in the race window (pre-fix the sync
  copy was journaled and survived). A sync/alwaysSyncSend producer, sendTimeout
  greater than 0, or the AsyncCallback send form are all notified and can retry.
- **Transacted producers are not exposed to that window**: individual
  transacted sends also go to the broker one-way (ActiveMQSession routes a send
  with a transaction id through asyncSendPacket), but durability is governed by
  the synchronous commit - LocalTransaction.commit returns success only after
  the transaction is durably stored and throws (STORE COMMIT FAILED, XA_RBOTHER)
  on failure, so the producer always learns the outcome and can retry the whole
  transaction. Pinned by TransactedSendDurabilityQueueSizeTest (commit is
  durable-and-delivered-once; rollback stores and delivers nothing).

### D4 — cursor-audit FALSE POSITIVE → duplicateFromStore kills the only copy
  **[REFUTED 2026-07-07 — empirically and by code trace]**
- The theorized mechanism does not exist. Three independent refutations:
  (1) `ActiveMQMessageAuditNoSync.isDuplicate` uses `BitArrayBin.setBit`
  (ActiveMQMessageAuditNoSync.java:172), and `setBit` returns FALSE for
  below-window sequences (negative bin → null array, BitArrayBin.java:114-136;
  negative offset → skipped, :57-67). Only `getBit` has "assume seen"
  semantics for below-window indexes, and the audit does not use it.
  (2) Cursor gc/stop rolls back the audit for every discarded undispatched
  batchList entry (AbstractStoreCursor.java:471-480) — consumer churn does
  not strand audit state.
  (3) End-to-end test `DuplicateFromStoreFalsePositiveTest` (priority-inverted
  recovery: 1 low-priority send followed by 100 high-priority with
  maxAuditDepth=64, useCache=false, maxPageSize=60): all 101 delivered,
  duplicateFromStore=0, counter 0. The test is kept as a regression pin of
  the setBit below-window semantics (changing BitArrayBin to getBit-style
  semantics would cause message loss — this test catches it).
- The residual "duplicateFromStore removes a single-counted copy" scenarios
  all route through D2 first (expiry-CAS swallow leaves an unacked row with
  no audit rollback; a later re-read of that row is then trapped) — D4 has no
  standalone existence. When a live counted twin exists, the books balance
  (verified in DuplicateFromStoreEndToEndTest); a blanket decrement in
  duplicateFromStore remains WRONG (verified -2).

### D5 — non-persistent discard on temp-store-full
FilePendingMessageCursor.discardMessage:589-603 → messageDiscarded (advisory
only): counted at send, no decrement. Only if producers send NON-persistent
messages. Marker: "Discarding message ... due to full temporary storage".

## Related (not this queue size error, but adjacent stuck-queue modes)

- **Acked-zombie page-in-gate jam**: transacted consumer acks then closes
  before commit → acked ref redispatched into dispatchPendingList; dispatch
  skips acked nodes (Queue.java:2300) but they count toward
  `pagedInPendingSize`; with maxPageSize=60 the gate
  (`pagedInPendingSize < maxPageSize`) can jam page-in — REAL messages stuck,
  counter "correct", browse SHOWS them. Distinguish from a queue size error by browse.
- **KahaDB pendingAdditions -1 leak**: every suppressed duplicate leaks a -1
  entry (KahaDBStore.java:546 trackPendingAdd; completion only runs for
  accepted candidates, Queue.java:848-850) — permanently defeats the AMQ-5266
  page-in limiter for the destination (re-opens cursor-past-inflight-add
  race → stuck message). Separate ticket.
- **Dispatch stall on duplicate-exhausted fillBatch**: a batch consumed
  entirely by duplicate rows returns empty and dispatch stalls until an
  external wakeup (observed while building the E2E test).
- **fireAfterCommit lack of isolation** is also the root of D1 — one fix
  covers both.

## Production triage checklist

1. Grep broker logs, count vs queue size error magnitude:
   - `POST COMMIT FAILED` / `Store COMMIT FAILED`  → D1
   - `Failed to remove expired Message from the store` → D2
   - `Duplicate message add attempt rejected` on async executor thread,
     without `suppressing duplicate dispatch` → D3
   - `cursor got duplicate from store` + DuplicateFromStore JMX counter →
     downstream symptom of D2 (D4 standalone refuted)
   - `Discarding message` (temp storage) → D5
2. Does the queue size error survive a broker restart?
   - Cleared by restart → counter-only (D1, D3, D4-consumed-row).
   - Survives restart → store rows exist: D2 (failed remove) or the
     acked-zombie/page-in-gate class → browse with large maxBrowsePageSize.
3. Do the affected queues use TTL? No TTL → D2 out.
4. Is useCache truly false in the applied policy of the affected queues
   (check via JMX DestinationView options / policy)? If any cache flapping →
   D3 in play.
5. Per-queue algebra: track `enqueues - dequeues - messages` over time; D1/D2
   keep it at 0 while messages stays high (dequeue never happened);
   D4 shows enqueues-dequeues > messages... (verify per case).

## Next steps

1. Repro test D1: transacted consumer, one-shot exception injected into
   `dispatchPending()`/cursor reset on the commit thread (custom
   PendingMessageCursor via policy or byteman); assert queueSize stuck at 1,
   store count 0, restart heals. Then fix Transaction.fireAfterCommit
   isolation + subSync hardening, test passes.
2. Repro test D2: TTL messages + store proxy throwing on removeAsyncMessage
   during expiry sweep; assert browse-empty +1 queue size error. Fix messageExpired
   error handling.
3. D4: DONE — refuted; DuplicateFromStoreFalsePositiveTest kept as a
   regression pin of the BitArrayBin setBit below-window semantics.
4. File the pendingAdditions -1 leak as its own KahaDB issue.

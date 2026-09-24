# KahaDB recoverMessages(MessageRecoveryContext) Isolated-Cursor Review

Concurrency review of the `recoverMessages(MessageRecoveryContext)` code path
(AMQ-9773) with `useIsolatedCursor=true`, hunting for bugs that corrupt the
live queue cursor — duplicates from store, or missed/stuck messages — under
concurrent producers, consumers, and recovery operations.

## Affected Classes

- `activemq-kahadb-store/.../store/kahadb/MessageDatabase.java`
  (`MessageOrderIndex`, `MessageOrderIterator`, `stoppedIterating()`)
- `activemq-kahadb-store/.../store/kahadb/KahaDBStore.java`
  (`recoverMessages`, `recover`, `recoverNextMessages`, `recoverRolledBackAcks`,
  `recoverExpired`)

## Architecture Facts (verified)

- `MessageDatabase.indexLock` is a plain exclusive `ReentrantLock`. All store
  recovery, add/remove index updates, and cursor mutations serialize on it, so
  the defects below are **stale state left between lock acquisitions**, not
  simultaneous access races.
- Per-destination shared state on `MessageOrderIndex`: the destination cursor
  (`cursor`) plus three iteration bookmarks (`lastDefaultKey`, `lastHighKey`,
  `lastLowKey`).
- `stoppedIterating()` commits `lastXxxKey + 1` into the destination cursor and
  nulls the bookmarks. The queue live-batch path (`recoverNextMessages`) calls
  it **unconditionally after every batch — including zero-entry batches**.
- Nothing else clears the bookmarks between batches (only `setBatch`,
  `resetCursorPosition`, and iteration itself overwrite them).

---

## Finding 1 (FIXED) — Isolated-cursor iteration pollutes the shared bookmarks

**Severity: high — silent message duplication or message loss on live queues.**

`MessageOrderIterator` is a non-static inner class of `MessageOrderIndex`. Its
`next()` wrote `lastDefaultKey`/`lastHighKey`/`lastLowKey` on the **shared
outer index** regardless of which `MessageOrderCursor` the iteration was given
— the passed cursor only seeds the BTree start positions. A isolated-cursor
browse therefore correctly skipped `stoppedIterating()` but left the shared
bookmarks non-null, pointing at the last *browsed* sequence.

**Failure timeline A — cursor rewind, duplicates from store:**

1. Live cursor at seq 1000; seqs 500–999 are dispatched-but-unacked (still in
   the order index).
2. `recoverMessages(offset=0, max=100, isolated=true)` browses seqs 500–599;
   `lastDefaultKey = 599`.
3. The next live batch iterates **zero entries** (cursor at tail — or the
   iteration is truncated to nothing by the `minPendingAdd()` limiter when a
   concurrent producer has an in-flight async add). The loop body never runs,
   so the stale bookmark survives, and `stoppedIterating()` commits it:
   cursor rewound to 600.
4. The following fill batch re-reads seqs 600–999 from the store. The cursor
   audit flags "cursor got duplicate from store", poison-acks to the DLQ via
   `duplicateFromStore()` and decrements the queue size — persistent queue
   size drift. If the audit depth is exceeded, real duplicate dispatch.

**Failure timeline B — cursor jump-forward, missed messages:** the browse
reads sequences ahead of the live cursor; a zero-entry live batch commits the
bookmark; the cursor skips past messages that were never dispatched. The
queue shows size > 0 but never delivers them until restart/cursor reset.

**Reproduced deterministically** (before fix) by
`KahaDBRecoverMessagesIsolatedCursorTest.testIsolatedCursorBrowseDoesNotCorruptLiveCursor`:
expected 0 re-deliveries, observed 5.

**Fix:** `MessageOrderIterator` now takes a `trackLastKeys` flag.
`iterator(tx)` / `iterator(tx, m)` keep the tracking behavior (they are the
variants paired with `stoppedIterating()` — queue live batches, topic
durable-sub batches, and shared-cursor-mode `recoverMessages`). New
`browseIterator(tx)` / `browseIterator(tx, m)` variants never touch the
bookmarks, and are now used by every iteration that is *not* committed with
`stoppedIterating()`:

| Call site | Purpose |
|---|---|
| `KahaDBStore.recoverMessages` (isolated branch) | offset/range browse — the primary defect |
| `KahaDBStore.recover` | full-recovery scan (resets the cursor; never stopped-iterated) |
| `KahaDBStore.recoverExpired` | topic expiry browse |
| `MessageDatabase.loadStoredDestination` (v<6 and v<3 upgrade scans) | load-time index upgrades |
| `MessageDatabase.getStoredMessageSize` (both overloads) | subscription statistics scans |

The topic path `recoverNextMessages(clientId, sub, …)` intentionally keeps
tracking: it iterates with a per-subscription cursor and *depends* on
`stoppedIterating()` committing the bookmarks — an identity-based fix
(`m == cursor`) would have broken it, which is why the choice is explicit
per call site.

Note the load-time and statistics scans were real secondary instances of the
same class of bug: e.g. after a v<6 upgrade scan, the bookmarks pointed at the
tail of the index, and a first live batch truncated to zero entries by the
pending-add limiter (producer racing broker startup) would have jumped the
cursor past every pre-existing message.

---

## Finding 2 (FIXED) — Isolated browse consumes rolled-back-ack redeliveries

**Severity: high — message loss (stuck message) plus browse-result duplication.**

`recoverMessages(ctx)` unconditionally called `recoverRolledBackAcks(...)`
with the browse context as the listener. That method `iterator.remove()`s each
entry from `rolledBackAcksMap` — the queue of messages whose XA acks rolled
back and which the **live cursor** must redeliver (they sit behind the cursor
position, so normal iteration never reaches them). Effects:

- The rolled-back message was delivered to the *browser* and permanently
  removed from the redelivery map — the live cursor never redelivered it.
  Because `forgetRecoveredAcks(rollback=true)` also increments the store
  message count, the queue showed a message that could never be dispatched.
- The browse result contained the replayed message **and** the same message
  again from its own index scan (it is still in the order index), regardless
  of the requested offset/range — and the replay consumed
  `maxMessageCountReturned` budget.

**Reproduced deterministically** (before fix) by
`testIsolatedCursorBrowseDoesNotConsumeRolledBackAcks`: browse returned 4
messages instead of 3 (one twice), and the subsequent live batch returned 0
instead of redelivering the rolled-back message.

**Fix:** `recoverRolledBackAcks` is now gated on
`!messageRecoveryContext.isUseIsolatedCursor()`. Shared-cursor mode keeps the
replay (it has live-cursor semantics and advances the destination cursor);
isolated-cursor mode is a pure browse.

---

## Finding 3 (VERIFIED SOUND) — XA ack-map synchronization

`ackedAndPreparedMap` / `rolledBackAcksMap` mutators (`trackRecoveredAcks`,
`forgetRecoveredAcks`) and all readers (`recover`, `recoverNextMessages`,
`recoverMessages`, `recoverRolledBackAcks`) acquire the same exclusive
`indexLock`. No change needed.

---

## Finding 4 (DOCUMENTED, NOT CHANGED) — Anchor-not-found fallback semantics

- A `startMessageId` that is not in `messageIdIndex` (e.g. the paging anchor
  was consumed between pages) silently falls back to sequence 0 — a paging
  client restarts from the head of the store and re-reads everything.
- An `endMessageId` that is not found silently becomes
  `start + maxMessageCountReturned`.
- Both `startMessageId`/`offset` and `endMessageId` are **inclusive** bounds
  (the end entry is recovered before the loop breaks), so a pager that passes
  the last-seen id as the next `startMessageId` receives it again.

These are now documented in the `recoverMessages` javadoc. Changing the
fallbacks to fail fast would break `StoreBackup` semantics; if paging clients
need it, a follow-up could add an exclusive-start option and a strict-anchor
mode to `MessageRecoveryContext`.

## Observations (no change)

- The exclusive `indexLock` is held across `loadMessage()` journal reads for
  the entire browse range — a large browse blocks all producers/consumers of
  that destination for its duration. Latency, not correctness; a fix would
  need chunked iteration with lock release between pages.
- Shared-cursor mode ignores the computed start offset entirely (iteration
  begins at the destination cursor). Now noted in the javadoc.
- `recoverRolledBackAcks` replays messages without setting
  `setFutureOrSequenceLong`, unlike both main recovery loops. A cursor
  `setBatch` keyed off such a message would see a null sequence. Pre-existing
  upstream behavior, unchanged by this review; worth an upstream look.
- `TempMessageDatabase` (temp destinations) does not use the bookmark /
  `stoppedIterating()` pattern and is not affected.

## Verification

`KahaDBRecoverMessagesIsolatedCursorTest` (new, activemq-unit-tests
`org.apache.activemq.store.kahadb`):

| Test | Pre-fix | Post-fix |
|---|---|---|
| testIsolatedCursorBrowseDoesNotCorruptLiveCursor | FAIL (5 dupes) | pass |
| testIsolatedCursorBrowseDoesNotSkipLiveMessages | pass (guard) | pass |
| testSharedCursorModeAdvancesLiveCursor | pass (contract) | pass |
| testIsolatedCursorBrowseDoesNotConsumeRolledBackAcks | FAIL (4 vs 3, replay stolen) | pass |

Regression suites, all green post-fix:

- `KahaDBOffsetRecoveryListenerTest` — 8/8 (offset paging incl. walk/repeat + restart consume-all)
- `DuplicateFromStoreQueueSizeDriftTest` + `SendDuplicateFromStoreToDLQTest` — 20/20
- `KahaDBRecoverExpiredTest` + `KahaDBDurableMessageRecoveryTest` — 20/20 (topic
  expiry browse + durable-sub batch paths touched by the fix)
- `activemq-kahadb-store` full module — 93 run, 0 failures (1 pre-existing skip)

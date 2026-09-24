# AtomicBitArrayBin — deep thread-safety review

Branch: `amq-gh-2187-messageaudit-perf`
Scope: `AtomicBitArrayBin` (primary), `ConcurrentMessageAudit` / `CaffeineMessageAudit` (callers), `BitArrayBin` (parity baseline)

## Severity model

| Class | Consequence | Severity |
|---|---|---|
| False-positive duplicate | Legit message reported duplicate → broker drops it → **message loss** | P1 |
| False-negative duplicate | Duplicate reported new → redelivery (tolerable under at-least-once) | P2 |
| Liveness stall | Unbounded spin, transport thread pinned | P2 |
| API-semantics drift | Divergence from synchronized `BitArrayBin` | P3 |

## Original design under review

The reviewed implementation coordinated four shared atomics:

- `AtomicLong origin` — window start (bit index, multiple of 64)
- `AtomicLongArray slotEpoch` — which absolute 64-bit block each ring slot holds
- `AtomicLongArray bits` — the 64 bits per slot
- `AtomicLong lastInOrderBit` — in-order tracker

Slot reclamation was a 3-step protocol: CAS `slotEpoch → CLEARING` (sentinel
`Long.MAX_VALUE`), plain-volatile `bits = 0`, publish `slotEpoch = newEpoch`.
The `epoch` and `bits` words were separate, so no compound check-then-act on
them was atomic.

## Proven invariants (SAFE verdicts)

These hold in both the original and the fixed implementation:

1. **Ring bijectivity.** The window covers exactly `capacity` epochs
   `[originEpoch, originEpoch + capacity)`; for two distinct in-window epochs
   `|E1 − E2| < capacity`, so `E1 % capacity ≠ E2 % capacity`. Two in-window
   epochs never share a slot; a slot collision always involves an epoch that is
   behind the current window.
2. **Origin monotonicity.** `advanceOrigin` CASes from the observed value and
   only when the new origin is strictly greater. A failed CAS implies another
   thread advanced it. Origin never decreases; every "behind window"
   classification against a stale origin snapshot is therefore also true
   against the current origin (fail-safe direction).
3. **Per-slot epoch monotonicity.** A slot's installed epochs form a strictly
   increasing sequence: installation is only reachable when the target epoch
   exceeds the currently observed one. A slot can never return to an epoch it
   left. (This proof is what makes the fixed design's reasoning airtight.)
4. **No arithmetic overflow.** `epoch = index/64 ≤ Long.MAX_VALUE/64`, so
   `epoch*64 + highBit ≤ Long.MAX_VALUE` and `newOriginEpoch*64 ≤ index`.
   The `CLEARING = Long.MAX_VALUE` sentinel could never collide with a
   legitimate epoch (epoch domain tops out at `Long.MAX_VALUE/64`).
5. **Exactly-once first-set.** For N concurrent `setBit(i, true)` on a live
   slot, the bits-CAS linearizes them: exactly one observes the bit clear and
   returns false. (Verified by `testConcurrentSharedIndices`.)
6. **isInOrder atomicity.** `getAndSet` linearizes concurrent calls; the
   reported order is the arrival order at the atomic, matching the
   externally-synchronized upstream semantics call-for-call.

## Confirmed defects

### F1 — Cross-epoch ABA bit plant (P1, message loss) — FIXED

The epoch recheck and the bits CAS were separate operations on separate words.
Schedule:

1. T1 `setBit(i, true)`, epoch E: passes the slot-epoch check, reads
   `oldBits = 0`, is preempted before the CAS.
2. T2 writes a far-ahead index: advances the origin and fully recycles the
   slot (`E → CLEARING → bits=0 → E′`).
3. T1 resumes; its CAS `(0 → 0|mask)` **succeeds against epoch E′'s slot** —
   the value still matches even though the owner changed.

A bit for index `E′·64 + (i mod 64)` is now set although that message was
never audited. When it arrives, `isDuplicate` returns true and the broker
drops it. The clear path (`rollback`) plants in reverse: it erases a legit
E′ record (P2).

**Reproduced live**: `testNoCrossEpochBitPlantDuringRecycle` planted at index
7104 within ~100 rounds on a capacity-2 ring. The fabricated-index detector
(`testGetLastSetIndexNeverFabricatesIndex`, index 73921) tripped on the same
mechanism surfacing through the scanner.

**Fix**: structural — see "Fixed design" below.

### F2 — Behind-window setBit returned true (P1, parity divergence) — FIXED

Upstream `BitArrayBin.setBit` for a behind-window index computes a negative
bin, finds no array, and returns **false** (no-op — the late message is
accepted). The reviewed code returned **true** (reported duplicate). Any
window jump (sequence gap, failover replay, concurrent far-ahead write — no
caller bug required) converted late legitimate messages into dropped ones:

- Concurrent-jump variant: T1 reads origin, T2 advances past T1's epoch,
  T1 retries and classifies behind-window → true (`testBehindWindowSetBitMatchesBitArrayBin`).
- Rollback variant: after an unrelated jump, `rollback` no-ops behind the
  window and the resend was reported duplicate → the rolled-back message
  could never be delivered (`testRollbackThenResendAfterWindowJump`).

**Fix**: behind-window `setBit` is now a no-op returning false, matching
`BitArrayBin` exactly. `getBit` behind-window keeps returning true (also
upstream parity).

### F3 — CLEARING micro-lock: stalled publisher blocks all writers (P2, liveness) — FIXED

The 3-step reclamation was a 2-write micro-lock with no helping protocol: a
thread descheduled (or killed by an async throwable) between publishing
`CLEARING` and publishing the new epoch left the slot permanently in
`CLEARING`; every writer mapping to that slot spun forever in
`ensureSlotEpoch` (`Thread.yield` retry loop) — `isDuplicate` would never
return. The structure was obstruction-prone, not lock-free. Not unit-testable
without fault-injection hooks; confirmed analytically.

**Fix**: structural — reclamation is now a single reference CAS; there is no
intermediate state to stall in. The hot path is genuinely lock-free.

### F4 — getLastSetIndex fabricated indexes (P2/P3) — FIXED

The scan read `slotEpoch[i]` and `bits[i]` non-atomically: a recycle between
the two reads paired an old epoch with new bits and reconstructed an index
that was never set in any epoch (`getLastSeqId` feeds broker recovery paths).
**Reproduced live** by `testGetLastSetIndexNeverFabricatesIndex`.

**Fix**: epoch and bits now live in one immutable-epoch object; every observed
pair is consistent by construction. The scan keeps snapshot semantics
(documented) — it may understate under concurrent recycling, which is the
tolerable direction.

### F5 — getBit misread CLEARING as "seen" (P3) — FIXED

`getBit` observing `CLEARING` (`Long.MAX_VALUE`) took the
`curEpoch > epoch && curEpoch != UNINITIALIZED` branch and returned true for a
bit that was never set — including during first-touch initialization of the
queried epoch itself. Unused by the audit classes (they decide via `setBit`)
but wrong for a public method. Gone with the sentinel.

### F6 — isInOrder(-1) resurrected the UNINITIALIZED sentinel (P3) — FIXED

`IdGenerator.getSequenceFromId` returns -1 for unparseable ids and the audit
classes pass it through. `getAndSet(-1)` restored the initial "anything is in
order" state, so the next arbitrary index was reported in-order
(`testIsInOrderNegativeIndexDoesNotPoison`). Upstream has the same latent bug;
diverging here is deliberate hardening. **Fix**: negative indexes return false
without touching order state.

### F7 — Caller: setMaximumNumberOfProducersToTrack cache swap lost state (P2) — FIXED

`CaffeineMessageAudit` rebuilt the cache and copied entries via `putAll`;
inserts racing the copy (and concurrent resizes racing each other) were lost
wholesale. **Fix**: in-place resize via `cache.policy().eviction().setMaximum()`;
the cache reference is now final. Regression:
`verifySetMaximumDuringTrafficPreservesState` (also covers
`ConcurrentMessageAudit`, whose map was already fixed-reference).

### F8 — Caller: eviction orphans a bin mid-operation (P2) — ACCEPTED (mitigation rejected on measurement)

In both audit classes the bin reference escapes the map's atomicity: between
lookup and `setBit`, eviction (CHM `evictExcess` victim / Caffeine TinyLfu)
can remove the bin; the write lands in an orphan and the next occurrence of
the id is not detected as a duplicate.

A bounded membership-recheck-and-retry mitigation was implemented and
benchmarked, then **rejected on the data**: eviction only fires under
producer oversubscription, and in exactly that regime the recheck fails on
nearly every call — each miss re-runs `computeIfAbsent` plus an eviction
scan while the recreated entry is immediately evicted again. Measured cost:
Mixed R/W 128 producers single-thread 502 ms vs 115 ms (4.4×); eviction
scenario max=64/actual=256 single-thread 400 ms vs 166 ms (2.4×). A guard
like "retry only when below capacity" disables the retry in every case where
eviction can actually occur, proving the mitigation can never fire usefully.

Accepted as residual: the window is nanoseconds, it requires eviction of an
actively-publishing producer (an undersized-limit pathology), and the failure
direction is the tolerable one (duplicate redelivery under at-least-once).
Documented in `markSeen` javadoc in both classes.

## Accepted / documented behaviors (no change)

- **evictExcess overshoot** (`ConcurrentMessageAudit`): concurrent trimmers
  may transiently remove more entries than required; the bound is approximate
  and self-corrects. Documented in javadoc.
- **Stale-epoch install** (original I3): a writer validating against a stale
  origin could install an already-behind-window epoch. In the fixed design the
  install still can happen but is harmless: the object is internally
  consistent, later in-window writers CAS-replace it, and scans skip it.
- **getLastSetIndex snapshot semantics**: may understate vs a concurrent
  writer; upstream under lock was exact. Documented in javadoc.
- **clear() during traffic** (callers): weakly consistent (CHM `clear` /
  Caffeine `invalidateAll` are per-segment); a racing write may survive.
  Matches the lock-free design intent.
- **Anchor difference vs BitArrayBin**: upstream anchors its window at the
  first index seen; the atomic bin implicitly anchors at 0 and advances on
  first far write. Net classifications converge after the first write; no
  observable divergence in the audit call patterns.
- **isInOrder under concurrency**: concurrent in-order pairs may both report
  out-of-order — the atomic linearizes physical arrival order, which is the
  honest answer without external ordering.

## Fixed design (immutable slot holders)

The rewrite replaces the `slotEpoch[]`/`bits[]` pair and the CLEARING protocol
with a ring of immutable-epoch holders:

```java
private static final class Slot {
    final long epoch;                       // fixed at construction
    final AtomicLong bits = new AtomicLong();
}
private final AtomicReferenceArray<Slot> slots;
```

- **Recycling is one reference CAS.** No intermediate states exist, so no
  observer matrix and no stalled-publisher hazard (F3).
- **Stale mutations hit orphans.** A writer that captured the old slot object
  can only mutate the orphan — never the new epoch's bits (F1). The result is
  linearizable: equivalent to the operation completing just before the window
  advanced.
- **Readers see consistent pairs.** epoch and bits come from the same object
  (F4, F5).
- **Installed epochs per slot strictly increase** (proof #3), so the
  install loop terminates and `slot.epoch > epoch` proves the queried epoch is
  behind the current window.

## Verification

- `AtomicBitArrayBinTest`: 25 tests green, including the five repros that
  failed pre-fix (3 deterministic, 2 probabilistic hammers; hammers re-run 3×
  stable). Pre-fix failures observed: parity (deterministic), rollback-resend
  (deterministic), isInOrder poison (deterministic), ABA plant at index 7104,
  fabricated index 73921.
- `MessageAuditPerformanceTest` correctness suite green:
  `verifyCorrectnessEquivalence`, `verifyCorrectnessUnderConcurrency`,
  `verifyEvictionBehavior`, `verifySetMaximumDuringTrafficPreservesState`.
- Benchmark re-run post-rewrite: no regression (see
  `activemq-client/src/test/resources/benchmark-results.html`).

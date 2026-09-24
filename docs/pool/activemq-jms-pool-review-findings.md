# activemq-jms-pool JMS 2.0 Code Review Findings

Review of the JMS 2.0/3.1 pool support (`PooledJMSContext`, `PooledJMSProducer`,
`PooledJMSConsumer`, and the related `PooledSession`/`PooledConnection`/
`PooledConnectionFactory` changes) covering correctness bugs, spec compliance,
thread safety, and test coverage. Every finding below was reproduced with a failing
test before the fix was applied (red/green verified).

## Confirmed and fixed

### F1. `acknowledge()` called `recover()` (correctness, high)

`PooledJMSContext.acknowledge()` delegated to `Session.recover()`, which triggers
redelivery of unacknowledged messages -- the opposite of acknowledging them. In
CLIENT_ACKNOWLEDGE mode, "acknowledged" messages were redelivered to the next consumer.

**Fix:** consumers created by a context report each received message back to the
context; `acknowledge()` invokes `Message.acknowledge()` on the most recent one,
which per JMS acknowledges all messages the session has consumed. Messages delivered
asynchronously via `MessageListener` are not tracked (see known-limitations doc).

**Tests:** `testAcknowledgeAcksConsumedMessages`, `testAcknowledgeWithoutReceiveIsNoOp`

### F2. Exception-type mapping (spec compliance)

`toRuntimeException()` wrapped every `JMSException` as the generic
`JMSRuntimeException`. The spec defines specific unchecked counterparts:
`commit()` on a non-transacted context must throw `IllegalStateRuntimeException`,
`receiveBody` with an inconvertible type must throw `MessageFormatRuntimeException`, etc.

**Fix:** full checked-to-unchecked mapping table in `JmsPoolExceptionSupport.toRuntimeException`
(IllegalState, InvalidClientID, InvalidDestination, InvalidSelector, JMSSecurity,
MessageFormat, MessageNotWriteable, ResourceAllocation, TransactionInProgress,
TransactionRolledBack). Originally homed in `PooledJMSConsumer`; relocated to the
dedicated package-private `JmsPoolExceptionSupport` utility (no behavior change).
The mapping deliberately duplicates the client's `JMSExceptionSupport.convertToJMSRuntimeException`
because activemq-jms-pool depends only on the jakarta.jms API.

**Tests:** `testCommitOnNonTransactedThrowsIllegalStateRuntimeException`,
`testReceiveBodyWrongTypeThrowsMessageFormatRuntimeException`

### F3. `createContext()` after factory `stop()` returned a broken context

`createConnection()` returns `null` once the factory is stopped, so `createContext()`
handed out a `PooledJMSContext` wrapping a null connection that NPE'd on first use.

**Fix:** null-check in both `createContext` variants; throws
`IllegalStateRuntimeException("PooledConnectionFactory is stopped")`.

**Test:** `testCreateContextAfterFactoryStopThrows`

### F4. `start()` after `stop()` permanently broke the connection

`PooledConnection.stop()` sets a `stopped` flag that `assertNotClosed()` treats as
closed, so a stopped connection could never be restarted -- `JMSContext.start()`
threw "Connection closed". JMS requires stop/start to be reversible.

**Fix:** `start()` clears the stopped flag before starting the pool. Note that
`stop()` remains a facade-level pause on this pooled connection proxy; it does not
halt delivery on the shared underlying connection (pre-existing behavior, out of
scope here).

**Test:** `testStartAfterStop`

### F5. Producer accumulation with `useAnonymousProducers=false`

Each `JMSContext.createProducer()` call created a new underlying `MessageProducer`
on the pooled session. `JMSProducer` has no close method, and pooled sessions
outlive contexts, so producers accumulated without bound on the internal session.

**Fix:** the context lazily creates and caches a single anonymous producer, shares
it across all `JMSProducer` instances it hands out, and closes the underlying
producer on context close when the session is not in anonymous-producer mode
(in anonymous mode the producer is owned and cached by the pooled session).
Added `PooledSession.isUseAnonymousProducers()` accessor.

**Test:** `testCreateProducerWithoutAnonymousProducers`

### F6. Property conversion matrix (spec compliance)

`JMSProducer` property getters rejected the spec's numeric widening conversions
(byte readable as short/int/long, float as double), wrapped `NumberFormatException`
from string parses in `MessageFormatRuntimeException` (spec requires NFE to
propagate), and threw NFE instead of NPE for null float/double reads.

**Fix:** per-type conversion methods implementing the spec matrix exactly.

**Tests:** `testNumericPropertyWidening`, `testInvalidPropertyConversions`,
existing `testPropertyStringConversion`

### F7. Thread-safety of context lifecycle

Three races, all confirmed by inspection and fixed together:

- **Double-close:** `closed` was a plain volatile check-then-act; two threads could
  both enter `close()` and double-decrement the shared connection refcount, releasing
  the connection while sibling contexts still used it. Fixed with
  `AtomicBoolean.compareAndSet`.
- **`createContext()` vs `close()` resurrection:** child creation incremented the
  refcount after the closed-check; a concurrent last-reference close could release
  the connection in between. Fixed with a CAS increment-if-positive loop that refuses
  to attach to a released counter.
- **`getSession()` vs `close()`:** lazy session creation could complete after close,
  leaking a session borrowed from the pool. Fixed by re-checking `closed` inside the
  creation monitor and tearing the session down under the same monitor in `close()`.

**Test:** `testConcurrentChildCloseDoesNotReleaseParentConnection` (25-iteration
barrier race); the remaining windows are covered by the lock/CAS structure.

### F8. Connection leak when session close fails (fixed earlier this cycle)

`close()` skipped the refcount decrement and connection release when
`session.close()` threw. Fixed with a try/finally structure that always releases
pool resources before propagating the first failure.

**Test:** `testConnectionReleasedWhenSessionCloseFails`

### F9. XA context broke after its first transaction (correctness, high)

`PooledJMSContext` cached its session for the context's lifetime, but with
`XaConnectionPool` the transaction's `afterCompletion` hook returns the session to
the pool. Any context operation after the first commit/rollback hit the dead
`PooledSession` and threw "The session has already been closed" -- an XA context
was single-use.

**Fix:** `getSession()` detects a pooled session that was closed behind the
context's back, drops it (closing the context's cached producer when it owned
one) and creates a fresh session, which enlists in the current transaction.
Added `PooledSession.isClosed()` accessor. Consumers created on the previous
session end with that transaction -- inherent to the XA session lifecycle.

**Test:** `XAConnectionPoolTest#testCreateContextSpansTransactions` (two
enlist/complete cycles through one context)

### F10. `createProducer()` auto-started the connection (spec behavior)

The spec ties auto-start to JMSConsumer creation. Starting on producer creation
was observable: a consumer created after `setAutoStart(false)` would still
receive messages because an earlier `createProducer()` had already started the
connection.

**Fix:** removed the start from `createProducer()`; consumers and browsers still
auto-start.

**Test:** `testCreateProducerDoesNotStartConnection`

### F11. Property name validation (spec compliance)

`setProperty` accepted null and empty property names, deferring the failure to
send time far from the offending call. The spec requires `IllegalArgumentException`
at set time.

**Fix:** eager name validation in all `setProperty` variants. Property *value*
types are deliberately NOT validated eagerly: the underlying provider performs
the authoritative check when properties are applied to the message at send time,
which preserves provider extensions beyond the spec whitelist (e.g. ActiveMQ's
nested Map/List property support via `nestedMapAndListEnabled`); a provider
rejection surfaces as the spec-correct `MessageFormatRuntimeException` through
the exception mapping (F2).

**Test:** `testSetPropertyNameValidation`

### F12. `setAsync(CompletionListener)` was silently ignored (correctness)

Sends always used the synchronous path regardless of a registered
`CompletionListener`, so the caller's completion callback never fired -- on a
provider without async-send support this presented as a hang in the caller's
completion logic rather than an error.

**Fix:** when a listener is set, `send()` delegates to the underlying
`CompletionListener` send variant. ActiveMQ Classic rejects it loudly with
`UnsupportedOperationException` at send time; capable providers send
asynchronously. `setAsync(null)` restores synchronous sends.

**Test:** `testAsyncSendPassesListenerToProvider`

### F13. Producer sends after context close (backlog P2, JMS 2.0 path)

A `JMSProducer` whose context had closed either failed with the shared 1.1
wrapper's `java.lang.IllegalStateException` (session-built message bodies) or --
worse -- silently sent via the pooled anonymous producer when given a pre-built
`Message`, potentially on a session already loaned to another borrower.

**Fix:** `PooledJMSProducer` checks the backing `PooledSession`'s closed state at
the top of every `send(...)` variant and throws `IllegalStateRuntimeException`.
The JMS 1.1 `PooledProducer` is intentionally unchanged (breaking-change policy;
see backlog P2 for the 7.x scope).

**Test:** `testProducerUseAfterContextCloseThrows` (both paths)

### F14. `getPropertyNames()` returned a modifiable, non-live set (spec compliance)

Jakarta Messaging 3.1 requires `JMSProducer.getPropertyNames()` to return a set
that is backed by the producer (a live view) AND unmodifiable -- mutation
attempts, directly or via the iterator, must throw
`UnsupportedOperationException` with `Collections.unmodifiableSet` semantics.
The implementation returned the raw `keySet()` (callers could mutate producer
state through a read API) and, before any property was set, a detached
`Collections.emptySet()` that never reflected later additions.

**Fix:** always return `Collections.unmodifiableSet(getOrCreateProperties().keySet())`,
which is live and unmodifiable in all states; the only cost is materializing the
property map once per producer on first read. Found during the Jakarta 3.1
compliance review of the squashed `[#2273]` commit.

**Test:** `testGetPropertyNamesIsUnmodifiableLiveView`

## Verified safe / documented behavior

- **Shared consumers:** ActiveMQ Classic's client throws unchecked
  `UnsupportedOperationException` from `createSharedConsumer`/`createSharedDurableConsumer`;
  it passes through the pool unchanged. Documented in the known-limitations doc and
  pinned by `testCreateSharedConsumerUnsupportedByClient`.
- **XA enlistment through `createContext()`:** `XaPooledConnectionFactory.createContext()`
  produces contexts whose sessions enlist in the active transaction via
  `XaConnectionPool`. Pinned by `XAConnectionPoolTest#testCreateContextEnlistsSessionInTransaction`.
- **Sibling factories inherit `createContext()` correctly:** the `activemq-pool` module's
  `XaPooledConnectionFactory` and `JcaPooledConnectionFactory` (ActiveMQ-specific
  `ConnectionPool` subclasses, JCA named-XAResource wrapping) reuse the inherited
  createContext/renewal path unchanged. Pinned by the two-transaction tests
  `org.apache.activemq.pool.XAConnectionPoolTest#testCreateContextSpansTransactions`
  and `#testJcaCreateContextSpansTransactions`. Note for test authors: a TM stub
  reusing one pooled XA session across transactions MUST call `end()` on the branch
  before the next `start()` -- `TransactionContext` correctly rejects a second
  association with `XAER_PROTO` otherwise.
- **`PooledJMSProducer` mutable state is unsynchronized:** acceptable -- the spec
  defines JMSContext and its dependent objects as single-threaded; concurrent use of
  one JMSProducer is outside the supported contract.
- **QoS pass-through (deliveryDelay, async CompletionListener, disableMessageID/
  disableMessageTimestamp):** intentionally stored-and-passed-through; the underlying
  provider throws where unsupported. See the known-limitations doc.
- **`stop()` does not halt delivery on the shared underlying connection:** inherent to
  connection pooling -- the facade cannot stop a connection other borrowers are using.
  The F4 fix makes `stop()`/`start()` reversible on the facade; the delivery-pause
  deviation itself is documented in the known-limitations doc.
- **`receiveBody()` conversion failure consumes the message in auto-ack modes:** the
  generic API cannot un-receive a message, so the spec's leave-for-redelivery behavior
  is only achievable in CLIENT_ACKNOWLEDGE and transacted/XA modes. Documented with
  the per-mode breakdown in the known-limitations doc.
- **Exception mapper fully pinned:** all ten checked-to-unchecked mappings in
  `JmsPoolExceptionSupport.toRuntimeException` (plus the generic fallback for the base
  type and unknown subtypes) are asserted for runtime type, message, error code, and
  cause by `JmsPoolExceptionSupportTest#testToRuntimeExceptionMapsAllSpecTypes`.
- **Transport/broker failure through the JMSContext surface:** pinned by
  `PooledJMSContextBrokerFailureTest` (MockTransport fault injection plus a real
  broker stop/restart). Failed contexts settle into the mapped
  `IllegalStateRuntimeException` (transitional failures may surface as the generic
  `JMSRuntimeException` from `ConnectionFailedException` while the async teardown
  runs), `reconnectOnException` (default) evicts the dead connection, closing a
  failed context still releases its pool references, and the factory hands out
  working contexts afterwards -- including across a broker restart on the same port.
- **Authentication failure through `createContext()`:** pinned by
  `PooledConnectionSecurityExceptionTest#testCreateContextWithInvalidCredentials` --
  the failure surfaces on the first broker-touching operation (session create) as a
  runtime exception with `JMSSecurityException` in the cause chain, and the factory
  still hands out working contexts for valid credentials afterwards. Note: the
  surfaced type is `IllegalStateRuntimeException` rather than
  `JMSSecurityRuntimeException` because the pre-existing
  `ConnectionPool.createSession` wraps all create failures in
  `jakarta.jms.IllegalStateException` before the mapper runs; unwrapping
  `JMSException` subtypes there would restore the precise runtime type for both the
  JMS 1.1 and 2.0 paths, but changes long-standing 1.1 behavior. Tracked as P1 in
  `activemq-jms-pool-backlog.md`.

# activemq-jms-pool / activemq-pool Backlog

Follow-up items from the JMS 2.0/3.1 pool work. See
`activemq-jms-pool-review-findings.md` for the completed review and
`activemq-jms-pool-knownlimitations.md` for accepted behavior.

## P1. ConnectionPool.createSession erases JMSException subtypes (correctness)

`ConnectionPool.createSession(...)` wraps every session-create failure in
`jakarta.jms.IllegalStateException`:

```java
} catch (Exception e) {
    IllegalStateException illegalStateException = new IllegalStateException(e.toString());
    illegalStateException.initCause(e);
    throw illegalStateException;
}
```

Because the wrap happens before the JMS 2.0 exception mapper runs, the precise
exception type is erased. An authentication failure (broker rejects with
`JMSSecurityException`) surfaces as:

- JMS 1.1 path: `jakarta.jms.IllegalStateException` from `Connection.createSession(...)`
- JMS 2.0 path: `IllegalStateRuntimeException` from the first context operation that
  touches the broker -- the spec-correct type would be `JMSSecurityRuntimeException`

The same erasure applies to any other `JMSException` subtype thrown during session
creation (resource allocation, invalid client id, etc.).

**Proposed fix:** rethrow `JMSException` subtypes unchanged before falling back to
the `IllegalStateException` wrap:

```java
} catch (JMSException e) {
    throw e;
} catch (Exception e) {
    // existing wrap for non-JMS failures (pool exhaustion, interruption, ...)
}
```

commons-pool2 propagates `makeObject` failures out of `borrowObject` without
wrapping, so the original `JMSSecurityException` should be directly catchable --
verify when implementing.

**Impact / risk:** improves type fidelity on both the 1.1 and 2.0 paths, but changes
the exception type that long-standing 1.1 clients may be matching on
(`IllegalStateException` -> specific subtype).

**Target: ActiveMQ 7.x, as its own commit.** Changing the exception type thrown from
long-standing JMS 1.1 API paths is a breaking change; land it as part of the full
Jakarta compliance work, not on this branch.

**Tests to update when fixed:**

- `PooledConnectionSecurityExceptionTest#testCreateContextWithInvalidCredentials`
  currently pins the erased behavior (walks the cause chain to find
  `JMSSecurityException`); tighten it to assert `JMSSecurityRuntimeException`
  directly.

## P2. Producer remains partially usable after its context/session closes (correctness)

**Status: FIXED on the JMS 2.0 path (2026-07-31).** `PooledJMSProducer` now checks
the backing pooled session's closed state at the top of every `send(...)` variant
and throws `IllegalStateRuntimeException`. This closes both original defects on the
JMSContext surface:

- Sends that build the message through the session no longer surface the shared
  1.1 wrapper's `java.lang.IllegalStateException`.
- `send(Destination, Message)` with a pre-built `Message` no longer silently
  succeeds against the pooled anonymous producer (which may already be loaned to
  another borrower).

Pinned by `PooledJMSProducerTest#testProducerUseAfterContextCloseThrows` (both
paths, red/green verified).

**Remaining 1.1-path scope -- target: ActiveMQ 7.x, as its own commit** (breaking
exception-signature changes, part of the full Jakarta compliance work, same policy
as P1):

- The JMS 1.1 `PooledProducer` used after its `PooledSession` closes still sends
  silently on the pooled holder (pre-existing behavior); add the equivalent
  closed-state check throwing `jakarta.jms.IllegalStateException`.
- `PooledSession.safeGetSessionHolder()` throws `java.lang.IllegalStateException`
  where the JMS spec calls for `jakarta.jms.IllegalStateException`.

## Other open items (identified, not started)

- Jakarta Messaging TCK run against `PooledConnectionFactory` (definitive spec
  compliance check for the JMSContext/JMSProducer/JMSConsumer groups).
- Prune the shared-subscriptions entry in the known-limitations doc when JMS 3.1
  shared subscription support lands (GH #1715 branch).

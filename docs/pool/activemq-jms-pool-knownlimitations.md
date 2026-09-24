# activemq-jms-pool Known Limitations

## JMS 2.0 / 3.1 Support

The `activemq-jms-pool` module provides JMS 2.0 API surface (`JMSContext`, `JMSProducer`, `JMSConsumer`) via
`PooledJMSContext`, `PooledJMSProducer`, and `PooledJMSConsumer`. These classes delegate to the underlying
JMS `ConnectionFactory` implementation. Because `activemq-jms-pool` is generic (no dependency on `activemq-client`),
behavior depends on what the underlying provider supports.

### Delivery Delay

`JMSProducer.setDeliveryDelay(long)` and `MessageProducer.setDeliveryDelay(long)` store the value locally in the
pooled wrapper. ActiveMQ Classic does not yet implement delivery delay; the underlying `MessageProducer` will
throw an appropriate exception if a non-zero delivery delay is set and the provider does not support it.

### Async Send (CompletionListener)

`JMSProducer.setAsync(CompletionListener)` is wired through the send path: when a listener is set, sends
delegate to the underlying `MessageProducer.send(..., CompletionListener)` variant. ActiveMQ Classic does
not yet support asynchronous sends and rejects the call with `UnsupportedOperationException` at send time;
providers that support JMS 2.0 async sends will send asynchronously and invoke the listener. Setting
`setAsync(null)` restores synchronous sends.

### Disable Message ID

`JMSProducer.setDisableMessageID(boolean)` stores the flag locally. ActiveMQ Classic does not honor
`MessageProducer.setDisableMessageID(true)` -- message IDs are always assigned. The pool passes this
through; the underlying provider determines behavior.

### Disable Message Timestamp

`JMSProducer.setDisableMessageTimestamp(boolean)` stores the flag locally. ActiveMQ Classic may not
support disabling message timestamps. The pool passes this through; the underlying provider determines
behavior.

### Shared and Shared Durable Consumers

`JMSContext.createSharedConsumer(...)` and `JMSContext.createSharedDurableConsumer(...)` delegate to the
underlying `Session`. ActiveMQ Classic does not yet implement shared subscriptions; its client throws an
unchecked `UnsupportedOperationException`, which passes through the pool unchanged. Providers that signal
lack of support with a checked `JMSException` will surface it as the matching `JMSRuntimeException` subtype.

### acknowledge() and MessageListener-delivered messages

`JMSContext.acknowledge()` works by tracking the most recent message received through the context's
`JMSConsumer.receive*()` methods and invoking `Message.acknowledge()` on it (which per JMS acknowledges
all messages the session has consumed). Messages delivered asynchronously to a `MessageListener` are not
tracked -- when consuming via a listener in CLIENT_ACKNOWLEDGE mode, call `acknowledge()` on the delivered
`Message` itself.

### stop() is a facade-level pause, not a delivery stop

The JMS specification requires `Connection.stop()` (and therefore `JMSContext.stop()`) to halt message
delivery until `start()` is called again. A pooled connection is a facade over a shared underlying
connection that other borrowers from the same pool may be using concurrently, so the pool cannot stop
the real connection without disrupting them. `PooledConnection.stop()` therefore only marks the facade
stopped: subsequent operations on that facade are rejected until `start()` clears the flag, but consumers
already created from it may continue to receive messages while "stopped". Applications that need true
per-connection delivery control should use a dedicated (non-pooled) connection for that consumer.

### receiveBody() conversion failures in auto-acknowledge modes

When a message body cannot be converted to the requested type, the specification requires
`JMSConsumer.receiveBody(...)` to throw `MessageFormatRuntimeException` and leave the message available
for redelivery. The generic `jakarta.jms` API offers no way to receive a message body without first
receiving the message, so the pool implements `receiveBody` as `receive()` followed by `Message.getBody()`.
The resulting behavior depends on the acknowledgement mode:

- **AUTO_ACKNOWLEDGE / DUPS_OK_ACKNOWLEDGE:** the message is acknowledged by the receive before the
  conversion fails; the exception is thrown but the message is consumed and is NOT redelivered
  (deviation from the specification).
- **CLIENT_ACKNOWLEDGE:** the message remains unacknowledged; `recover()` makes it redeliverable
  (spec-compliant).
- **Transacted / XA:** the message remains part of the transaction and is redelivered on rollback
  (spec-compliant).

A message that fails conversion still counts as the context's most recent received message for
`acknowledge()` tracking, consistent with it having actually been consumed from the session.

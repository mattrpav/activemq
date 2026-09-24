# Protocol Specification Template

This document defines the standardized multi-document specification structure for building new protocol support in ActiveMQ. Every protocol follows the same directory layout, document numbering, and status conventions.

## Protocol Identifier

Each protocol is assigned a short lowercase identifier (`$id`) used in URI schemes, directory names, document prefixes, and Maven module names.

| Protocol | `$id` | URI Scheme | Doc Directory |
|---|---|---|---|
| IBM MQ (MQFAP) | `mqi` | `mqi://` | `docs/mqi/` |
| MQTT 5.0 | `mqtt5` | `mqtt5://` | `docs/mqtt5/` |
| AMQP 0-9-1 | `ramqp` | `ramqp://` | `docs/ramqp/` |

## Module Structure Convention

Each protocol is organized as an aggregator POM with `client/` and `server/` submodules under a `$id/` directory at the project root. The root POM references only the aggregator directory — individual submodules are never listed in the root POM.

### Directory Layout

```
$id/
  pom.xml              # Aggregator POM (packaging: pom)
  client/
    pom.xml            # Client module
    src/main/java/     # Codec, wire format, client connection
    src/test/java/     # Unit tests (Tier 1)
  server/
    pom.xml            # Server module
    src/main/java/     # Transport connectors, JMX, network connectors
    src/test/java/     # Unit tests (Tier 1)

tests/$id/             # Functional tests (Tier 2) — separate module
it/$id/                # Integration tests (Tier 3) — separate module
```

### Naming Convention

| Component | ArtifactId | Name | Parent |
|---|---|---|---|
| Aggregator | `activemq-$id-parent` | `HYTE :: $ID Parent` | Root project POM |
| Client | `activemq-client-$id` | `HYTE :: $ID Client` | `activemq-$id-parent` |
| Server | `activemq-server-$id` | `HYTE :: $ID Server` | `activemq-$id-parent` |

### Module Roles

| Module | Purpose | Broker Dependency | Key Contents |
|---|---|---|---|
| **Client** | Wire protocol codec and outbound client connection | None (`activemq-client` only, `provided` scope) | Codec (encode/decode), wire format, frame types, data types, client connection, wire format factory |
| **Server** | Broker-side protocol integration | `activemq-broker` (`provided` scope) | Transport factory, transport filter, connection handler, exchange/routing, JMX, network connector/bridge |

The client module MUST have zero broker dependencies. This allows it to be used independently for outbound connections (e.g., in a `NetworkBridge` connecting to an external broker) without pulling in the broker classpath.

### Root POM Module Entry

The root POM lists only the aggregator directory, not individual submodules:

```xml
<modules>
  <!-- other modules -->
  <module>$id</module>
  <!-- other modules -->
</modules>
```

### Aggregator POM

```xml
<parent>
  <groupId>${project.groupId}</groupId>
  <artifactId>${root-project-artifactId}</artifactId>
  <version>${project.version}</version>
</parent>

<artifactId>activemq-$id-parent</artifactId>
<packaging>pom</packaging>
<name>HYTE :: $ID Parent</name>

<modules>
  <module>client</module>
  <module>server</module>
</modules>
```

### Build Order

The server module depends on the client module. When building with `-pl` (project list), install the client first:

```bash
# Install client (required for server compilation)
mvn install -pl $id/client -DskipTests

# Compile server
mvn compile -pl $id/server

# Run all tests for both modules
mvn test -pl $id/client,$id/server

# Full reactor build (resolves order automatically)
mvn compile
```

### Current Protocols

| Protocol | Directory | Aggregator | Client ArtifactId | Server ArtifactId |
|---|---|---|---|---|
| MQTT 5.0 | `mqtt/` | `activemq-mqtt-parent` | `activemq-client-mqtt` | `activemq-server-mqtt` |
| AMQP 0-9-1 | `ramqp/` | `activemq-ramqp-parent` | `activemq-client-ramqp` | `activemq-server-ramqp` |
| IBM MQ (MQFAP) | `activemq-client-mqi/` | — (standalone, pre-convention) | `activemq-client-mqi` | `activemq-transport-mqi` |

MQI predates the aggregator convention. When restructured, it would follow the same `mqi/client/` + `mqi/server/` pattern.

---

## Broker Semantic Mapping Principle

Protocol implementations MUST delegate state management to the ActiveMQ broker wherever
a native broker mechanism exists. Do not create protocol-specific stores, state trackers,
or acknowledgement pipelines that duplicate what the broker already provides. One-off
tracking maps are memory leak risks and diverge from broker-authoritative state.

### Producer Path

| Concern | Broker Mechanism | Protocol Mapping |
|---|---|---|
| **Message acknowledgement** | `responseRequired=true` on the message command → broker returns `Response` (success) or `ExceptionResponse` (failure) after store persistence | Translate `Response` → protocol-native producer ack (e.g., AMQP `Basic.Ack`, MQTT `PUBACK`). Translate `ExceptionResponse` → protocol-native producer nack. |
| **Transactions** | `TransactionInfo` with `BEGIN_TRANSACTION`, `COMMIT_ONE_PHASE`, `ROLLBACK` | Map protocol transaction commands to `TransactionInfo`. Do not buffer messages in the transport filter — let the broker manage transactional state. |
| **Flow control** | `ProducerAck` with `ProducerInfo.windowSize` | Map protocol-native flow control (e.g., AMQP channel flow, MQTT Receive Maximum) to producer window size. |

### Consumer Path

| Concern | Broker Mechanism | Protocol Mapping |
|---|---|---|
| **Message delivery** | `MessageDispatch` from broker to transport | Translate `MessageDispatch` → protocol-native delivery frame (e.g., AMQP `Basic.Deliver`, MQTT `PUBLISH`). |
| **Acknowledgement** | `MessageAck` with ack type (`INDIVIDUAL_ACK_TYPE`, `REDELIVERED_ACK_TYPE`, `POISON_ACK_TYPE`) | Map protocol-native acks to `MessageAck`. Do not track ack state in the transport — the broker is authoritative. |
| **Prefetch** | `ConsumerInfo.prefetchSize` | Map protocol-native prefetch/QoS to `ConsumerInfo.prefetchSize`. |
| **Synchronous pull** | `MessagePull` with `alwaysSignalDone=true` → broker dispatches message or null `MessageDispatch` | Create a temporary prefetch-1 consumer, send `MessagePull`, handle the dispatch. Clean up the consumer after response. |

### What NOT to Build

- **Custom ack trackers** — delivery tag → message ID maps are acceptable for protocol
  translation (mapping protocol sequence numbers to broker message IDs), but the ack
  lifecycle itself must flow through `MessageAck`. Do not implement "ack timeout" or
  "redelivery on ack timeout" in the transport — the broker handles redelivery.
- **Message stores or buffers** — do not buffer messages in the transport filter for
  transaction support, QoS guarantees, or retry logic. The broker's message store is
  authoritative. The only acceptable buffering is content frame assembly (accumulating
  multi-frame message bodies before dispatching as a single `ActiveMQMessage`).
- **Session state managers** — do not maintain parallel session state in the transport.
  Use `SessionInfo`, `ConsumerInfo`, `ProducerInfo` to register state with the broker.
  The broker tracks active consumers, producers, and subscriptions.
- **Custom redelivery logic** — do not implement redelivery counts or delays in the
  transport. Use `RedeliveryPolicy` on the broker side.

### Acceptable Transport-Level State

The transport filter MAY maintain:
- **Protocol-to-broker ID correlation maps** — e.g., AMQP consumer tag → `ConsumerId`,
  AMQP delivery tag → `MessageId`, MQTT packet ID → command correlation ID. These are
  translation lookups, not state ownership.
- **Content frame assembly** — accumulating multi-frame bodies into a complete message.
- **Per-channel/session metadata** — protocol-level channel state (confirm mode enabled,
  current exchange context) that has no broker equivalent.
- **Negotiated protocol parameters** — frame max, heartbeat interval, auth state.

The test: if the broker were to restart and replay its state, would the transport's
maps be reconstructable from broker commands alone? If not, the transport is holding
state it shouldn't own.

### MessageAck Types

ActiveMQ defines several acknowledgement types on `MessageAck`. Protocol implementations
must map their native ack semantics to the correct type — do not invent new ack
flows.

| Ack Type Constant | Value | Meaning | When to Use |
|---|---|---|---|
| `DELIVERED_ACK_TYPE` | 0 | Message delivered to consumer (dispatch ack) | Automatic — broker uses internally for prefetch tracking |
| `STANDARD_ACK_TYPE` | 2 | Acknowledge a range of messages (`firstMessageId` to `lastMessageId`) | Protocol-native batch/range ack (e.g., AMQP `Basic.Ack(multiple=true)`, JMS `CLIENT_ACKNOWLEDGE`) |
| `POISON_ACK_TYPE` | 1 | Poison-pill — message is undeliverable, send to DLQ | Protocol-native reject-without-requeue (e.g., AMQP `Basic.Reject(requeue=false)`, `Basic.Nack(requeue=false)`) |
| `REDELIVERED_ACK_TYPE` | 3 | Negative ack — put message back on queue for redelivery | Protocol-native reject-with-requeue (e.g., AMQP `Basic.Reject(requeue=true)`, MQTT re-publish) |
| `INDIVIDUAL_ACK_TYPE` | 4 | Acknowledge a single specific message | Protocol-native single-message ack (e.g., AMQP `Basic.Ack(multiple=false)`, MQTT `PUBACK`) |
| `UNMATCHED_ACK_TYPE` | 5 | Message did not match any selector — skip without DLQ | Rarely used by protocols; broker-internal for selector-based consumers |
| `EXPIRED_MSG_ACK_TYPE` | 6 | Message expired before delivery | Broker-internal; protocols should not send this type |

**Range vs individual acks:** `STANDARD_ACK_TYPE` uses `firstMessageId` and
`lastMessageId` to cover a contiguous range — this is the batch ack path. Protocols
that support "ack everything up to sequence N" (AMQP `multiple=true`, JMS
`CLIENT_ACKNOWLEDGE`) should use `STANDARD_ACK_TYPE` with the range. Protocols that
ack one message at a time should use `INDIVIDUAL_ACK_TYPE`.

### ActiveMQ Command Identity Model

ActiveMQ encodes connection topology into a composite ID hierarchy. Every message,
consumer, and producer carries its full lineage in its ID. Protocol implementations
create these IDs during setup and must understand the structure for correlation and
debugging.

#### ID Hierarchy

```
ConnectionId  (generated by IdGenerator — globally unique)
  └── SessionId(connectionId, sessionSequence)
        ├── ProducerId(sessionId, producerSequence)
        │     └── MessageId(producerId, messageSequence)
        └── ConsumerId(sessionId, consumerSequence)
```

#### IdGenerator Seed Format

`IdGenerator` produces globally unique connection ID strings:

```
ID:<hostname>-<ephemeralPort>-<timestampMs>-<instanceCount>:<sequence>
```

| Segment | Source | Example |
|---|---|---|
| `ID:` | Fixed prefix | `ID:` |
| `<hostname>` | `InetAddress.getLocalHost().getHostName()` | `broker-01` |
| `<ephemeralPort>` | `ServerSocket(0).getLocalPort()` (or `activemq.idgenerator.port`) | `61616` |
| `<timestampMs>` | `System.currentTimeMillis()` at IdGenerator construction | `1719400000000` |
| `<instanceCount>` | Incrementing counter per JVM (distinguishes multiple IdGenerator instances) | `0` |
| `<sequence>` | Per-IdGenerator `AtomicLong`, incremented on each `generateId()` call | `1` |

Example: `ID:broker-01-61616-1719400000000-0:1`

#### Composite MessageId Format

The `MessageId.toString()` format concatenates the full hierarchy with `:` separators:

```
ID:broker-01-61616-1719400000000-0:1:1:1:42
|____________________________________| | | |___|
 connectionId (from IdGenerator)       | | |   |
   session sequence -------------------+ | |   |
   producer sequence --------------------+ |   |
   message sequence (per-producer) --------+---+
```

| Position (right to left) | Field | Type | Set By |
|---|---|---|---|
| Last segment | `producerSequenceId` | long | `LongSequenceGenerator` per producer |
| Second from last | `producerValue` | long | `LongSequenceGenerator` per session |
| Third from last | `sessionId` | long | Assigned at session creation |
| Remainder | `connectionId` | String | `IdGenerator.generateId()` |

**Parsing:** `MessageId.setValue()` splits from the right on `:` — last segment is
`producerSequenceId`, remainder is parsed by `ProducerId`, which again splits from
right for `producerValue`, then `sessionId`, with the remainder becoming `connectionId`.

#### Derivation

The ID hierarchy is fully derivable — each level can be extracted from a child:

| From | Derive | How |
|---|---|---|
| `MessageId` | `ProducerId` | `messageId.getProducerId()` — drop the last `:sequenceId` segment |
| `MessageId` | Message sequence | `messageId.getProducerSequenceId()` — the last segment |
| `ProducerId` | `SessionId` | `producerId.getParentId()` — drop the last `:producerValue` segment |
| `ProducerId` | Producer sequence | `producerId.getValue()` — the last segment |
| `SessionId` | `ConnectionId` | `sessionId.getParentId()` — drop the last `:sessionId` segment |
| `SessionId` | Session sequence | `sessionId.getValue()` — the last segment |

This means a `MessageId` alone is sufficient to identify the connection, session,
producer, and message sequence — no separate lookup required. The broker uses this
for routing acks, producer flow control, and audit trails. Protocol transports can
store just the `MessageId` in correlation maps and derive the parent IDs on demand.

#### Protocol Mapping Guidelines

Protocol implementations typically use a single `ConnectionId` per transport connection,
a single `SessionId` for all operations (channels/sessions are a protocol-level concept,
not mapped 1:1 to ActiveMQ sessions), and per-channel `ProducerId`/`ConsumerId` instances.

| Protocol Concept | ActiveMQ ID | Scope |
|---|---|---|
| TCP connection | `ConnectionId` | One per transport connection |
| Channel / session / stream | `ProducerId` or `ConsumerId` | One per protocol channel or consumer/producer pair |
| Published message | `MessageId` | Unique per producer, auto-incremented |
| Protocol-native message ID | Store as JMS property (e.g., `JMS_$ID_MessageId`) | Preserved for round-trip fidelity |

Protocol-native sequence numbers (AMQP delivery tags, MQTT packet IDs) are NOT stored
in `MessageId` — they are protocol-level concerns tracked in the transport filter's
correlation maps. The `MessageId` is the broker's authoritative identity for the message.

---

## Documentation Directory Layout

```
docs/$id/
  00-$id-index.md               Index document (entry point, feature matrix)
  01-$id-backlog.md             Feature backlog with status/priority
  02-$id-design.md              Architecture and design decisions
  03-$id-codec.md               Wire protocol / codec specification
  04-$id-transport.md           Transport layer specification
  05-$id-client.md              Client module design
  06-$id-networkconnector.md    Network connector / federation bridge
  07-$id-coding.md              Coding standards and conventions
  08-$id-transforms.md          Message transformation / property mapping
  09-$id-handshake.md           Connection handshake specification
  10-$id-config.md              Configuration reference
  11-$id-tests.md               Unit test strategy
  12-$id-itests.md              Integration test strategy
  13-$id-tools.md               Developer/operator tooling
  14-$id-admin.md               Administrator guide
  15-$id-gaps.md                Feature gap analysis vs reference implementation
  16-$id-guide.md               End-user guide (developer + administrator combined)
  17-$id-internals.md           Engineering reference (internal implementation details)
  [20+-$id-*.md]                Protocol-specific supplementary specs
  research/                     Research findings directory
    $id-research-{topic}.md     Per-feature/capability research documents
```

## Document Classification

| # | Document | Required | Phase |
|---|---|---|---|
| 00 | **Index** | Yes | — |
| 01 | **Backlog** | Yes | — |
| 02 | **Design** | Yes | All |
| 03 | **Codec** | Yes | 1-Codec |
| 04 | **Transport** | Yes | 2-Transport |
| 05 | Client | Optional | 3-Client |
| 06 | Network Connector | Optional | 4-Bridge |
| 07 | Coding Standards | Optional | 1-Codec |
| 08 | **Transforms** | Yes | 2-Transport |
| 09 | **Handshake** | Yes | 2-Transport |
| 10 | **Config** | Yes | 2-Transport |
| 11 | Tests | Optional | 5-Tests |
| 12 | Integration Tests | Optional | 5-Tests |
| 13 | Tools | Optional | 6-Tools |
| 14 | Admin Guide | Optional | 2+ |
| 15 | Gap Analysis | Optional | All |
| 16 | End-User Guide | Optional | 2+ |
| 17 | Engineering Internals | Optional | 2+ |

**Bold** = required for every protocol (8 docs minimum).

Docs 14 and 16 overlap in purpose: use 14 (separate admin guide) during early development, graduate to 16+17 (combined guide + internals) when the protocol matures.

## Status Convention

Every document carries a metadata block immediately after the title:

```markdown
> **Status:** Backlogged | In Progress | Done
> **Phase:** 1-Codec | 2-Transport | 3-Client | 4-Bridge | 5-Tests | 6-Tools
> **Last Updated:** YYYY-MM-DD
```

---

## Document Templates

### 00 — Index (`00-$id-index.md`)

```markdown
# $PROTOCOL_NAME Protocol Support

> **Status:** Backlogged | In Progress | Done
> **Last Updated:** YYYY-MM-DD

## Overview

[1-3 paragraph description of the protocol, its purpose in the ActiveMQ context,
and what "support" means (accept native clients, federation, or both).]

## Related Links

| Resource | URL |
|---|---|
| Protocol Specification | [link] |
| Reference Implementation | [link] |
| Issue Tracker | [link] |

## Protocol Versions

| Version | Protocol Level | Support Status | Notes |
|---|---|---|---|
| [e.g., 5.0] | [e.g., 5] | Implemented / Planned / Not planned | |

## Module Structure

See the [Module Structure Convention](#module-structure-convention) for the standard
directory layout and naming. Protocol-specific module table:

| Module | ArtifactId | Purpose |
|---|---|---|
| Client | `activemq-client-$id` | Codec, wire format, client connection (zero broker deps) |
| Server | `activemq-server-$id` | Transport connectors, JMX, network connectors |

## Feature Matrix

### Server Features (accept native protocol clients)

| Feature | Status | Spec Reference | Document |
|---|---|---|---|
| Connection handshake | Done / In Progress / Backlogged | [section] | [09-$id-handshake.md] |
| Message send | ... | ... | ... |
| Message receive | ... | ... | ... |
| Transactions | ... | ... | ... |
| Pub/Sub | ... | ... | ... |
| SSL/TLS | ... | ... | ... |
| NIO | ... | ... | ... |

### Client Features (connect to external broker)

| Feature | Status | Spec Reference | Document |
|---|---|---|---|
| Outbound connection | ... | ... | [05-$id-client.md] |
| ... | ... | ... | ... |

## Version Compatibility

| Reference Product | Version | Tested | Notes |
|---|---|---|---|
| ... | ... | Yes / No | ... |

## Addressing Model

[REQUIRED. Describe how the source protocol addresses destinations and how
that maps to ActiveMQ's flat Queue/Topic model. Include:]

### [Source protocol] addressing overview

[1-3 paragraphs + ASCII diagram explaining how the protocol addresses
messages: topic-based, queue-based, exchange→binding→queue, etc.]

### Separator and Wildcards

| Concept | $PROTOCOL | ActiveMQ |
|---|---|---|
| Hierarchy separator | [e.g., `/`, `.`, none] | `.` (dot) |
| Single-level wildcard | [e.g., `+`, `*`] | `*` |
| Multi-level wildcard | [e.g., `#`] | `>` |

[Note which translations are required and which are identity mappings.]

**Examples:**

| $PROTOCOL Address / Pattern | ActiveMQ Equivalent | Matches |
|---|---|---|
| [exact match example] | [translated] | Exact match |
| [single-level wildcard example] | [translated] | [what it matches] |
| [multi-level wildcard example] | [translated] | [what it matches] |

### $PROTOCOL → ActiveMQ Destination Mapping

| $PROTOCOL Concept | ActiveMQ Mapping | Notes |
|---|---|---|
| [e.g., queue, topic, exchange] | [ActiveMQQueue / ActiveMQTopic] | [translation rules] |
| ... | ... | ... |

### Name / Topic Translation

[Direction table showing separator and wildcard translation in each direction.
Note any escaping rules for ambiguous characters.]

| Direction | Separator | Single-Level | Multi-Level |
|---|---|---|---|
| **$PROTOCOL → ActiveMQ** (inbound) | [from] → [to] | [from] → [to] | [from] → [to] |
| **ActiveMQ → $PROTOCOL** (outbound) | [to] → [from] | [to] → [from] | [to] → [from] |

## Specification Documents

| # | Document | Status | Phase | Description |
|---|---|---|---|---|
| 01 | [01-$id-backlog.md](01-$id-backlog.md) | ... | — | Feature backlog |
| 02 | [02-$id-design.md](02-$id-design.md) | ... | All | Architecture |
| ... | ... | ... | ... | ... |

## Command / Operation Mapping

| Protocol Operation | ActiveMQ Command | Notes |
|---|---|---|
| ... | ... | ... |

## Message Property Mapping

| Protocol Property | JMS Property | Direction |
|---|---|---|
| ... | ... | Inbound / Outbound / Both |
```

---

### 01 — Backlog (`01-$id-backlog.md`)

```markdown
# $PROTOCOL_NAME Implementation Backlog

> **Status:** In Progress
> **Last Updated:** YYYY-MM-DD

## Current Status

### Implemented

| Feature | Codec | Transport | Tests |
|---|---|---|---|
| ... | ... | ... | Yes / Partial / No |

### In Progress

| Feature | Backlog Item | Assigned To |
|---|---|---|
| ... | ... | ... |

## [Category: e.g., Operations / Packet Types / Methods]

| # | Item | Priority | Complexity | Notes |
|---|---|---|---|---|
| [C1-1] | ... | High / Medium / Low | High / Medium / Low | ... |

### [C1-1] — [Item Name]

**Status:** Not implemented
**Spec Reference:** [section]
**Priority:** High / Medium / Low

[Description of what needs to be implemented.]

**Files affected:**
- `path/to/codec/File.java` — [what changes]
- `path/to/transport/File.java` — [what changes]
```

---

### 02 — Design (`02-$id-design.md`)

```markdown
# $PROTOCOL_NAME Design Plan

> **Status:** Backlogged | In Progress | Done
> **Phase:** All phases
> **Last Updated:** YYYY-MM-DD

## Overview

### Goals
- [...]

### Non-Goals
- [...]

## Module Structure

[ASCII tree of Maven modules. Include SPI registration paths.]

### Dependency Chain

[Which modules depend on which, including ActiveMQ core dependencies.]

## Dependency Analysis

[If replacing or wrapping an existing library, document what and why.
If building from scratch, document why.]

## Transport Architecture

[Which ActiveMQ patterns are reused: TransportFactory, TransportFilter,
WireFormat, SPI discovery.]

## Broker Semantic Mapping

[REQUIRED. How each protocol operation maps to ActiveMQ broker commands.
See the Broker Semantic Mapping Principle in the protocol-spec-template.md
root section for the full requirement.]

### Producer Acknowledgements

[How protocol-native producer acks map to `responseRequired=true` +
`Response`/`ExceptionResponse`. Do NOT build custom ack trackers.]

### Consumer Acknowledgements

[How protocol-native consumer acks map to `MessageAck` with the
appropriate ack type. The broker is authoritative for ack state.]

### Transactions

[How protocol transaction commands map to `TransactionInfo`. Do NOT
buffer messages in the transport — the broker manages transactional state.]

### Synchronous Pull (if applicable)

[How synchronous get/pull maps to temporary consumer + `MessagePull`.
Clean up resources after response.]

### State Boundary

[What state the transport filter maintains (ID correlation maps, content
assembly, negotiated parameters) vs what the broker owns (ack lifecycle,
redelivery, session state, message persistence).]

## Codec Architecture

[Class hierarchy, encoding/decoding patterns, data type system,
protocol version handling.]

## Implementation Phases

### Phase 1: Codec Module
[File listing, key decisions, verification steps.]

### Phase 2: Transport Module
[File listing, connection state machine, verification steps.]

### Phase 3: Client Module (if applicable)
[...]

### Phase 4: Federation / Bridge (if applicable)
[...]

## Verification

[Build and test commands for each phase.]
```

---

### 03 — Codec (`03-$id-codec.md`)

```markdown
# $PROTOCOL_NAME Wire Protocol / Codec Specification

> **Status:** Backlogged | In Progress | Done
> **Phase:** 1-Codec
> **Module:** `$artifactId`
> **Last Updated:** YYYY-MM-DD

## Overview

[Wire protocol description, byte order convention, default port.]

## Frame Format

### Frame Header ([N] bytes)

| Offset | Size | Type | Field | Description |
|---|---|---|---|---|
| 0 | ... | ... | ... | ... |

### Frame Types

| Type ID | Name | Direction | Description |
|---|---|---|---|
| ... | ... | C->S / S->C / Both | ... |

## Data Types

| Type | Wire Format | Java Mapping |
|---|---|---|
| ... | ... | ... |

## Packet / Method Specifications

### [Type Name]

**Direction:** Client -> Server / Server -> Client / Both
**Spec Reference:** [section]

| Offset | Size | Field | Description |
|---|---|---|---|
| ... | ... | ... | ... |

#### Codec API

[Key encode/decode method signatures.]

## Frame and Packet Design

Use Java records for protocol frames and packets where the frame is a decoded,
read-only value. Records provide immutability by default, which prevents accidental
mutation after decode and makes frames safe to pass across threads without
defensive copies.

### When to Use Records

| Use Case | Record? | Rationale |
|---|---|---|
| Decoded inbound frame/packet | Yes | Immutable after decode; passed to handler as-is |
| Method arguments (e.g., `ConnectionStart` fields) | Yes | Fixed set of fields decoded from wire |
| Content header properties | Yes | Decoded property bag, read-only after construction |
| Outbound frame under construction | No (builder or mutable class) | Fields set incrementally before encode |
| Stateful codec context (partial frame assembly) | No | Mutated as bytes arrive |

### Pattern

```java
// Decoded frame — immutable record
public record ConnectionStart(
    int versionMajor,
    int versionMinor,
    FieldTable serverProperties,
    String mechanisms,
    String locales
) {
    public static ConnectionStart decode(ByteBuffer buf) {
        // read fields, validate, return new record
    }

    public ByteBuffer encode() {
        // serialize fields to wire format
    }
}

// Frame envelope — record wrapping type + channel + payload
public record AmqpFrame(int type, int channel, Object payload) {}
```

### Guidelines

1. Prefer records for any decoded protocol unit that does not need post-construction mutation
2. Records naturally enforce equals/hashCode/toString — useful for test assertions
3. For frames with optional fields (e.g., MQTT properties), use `@Nullable` or
   `Optional` in the record components rather than making the class mutable
4. If a frame type has many fields (>6-8 components), consider a record with a
   companion builder for the outbound (encode) path while keeping the record
   itself immutable for the inbound (decode) path
5. Nested records are fine — e.g., a `BasicProperties` record inside a
   `ContentHeader` record

## Codec Validation

All validation occurs during decode, before any buffer allocation or object creation.

### Declared Length Validation (DoS Prevention)

Every length prefix read from the wire MUST be validated against a configurable maximum
before allocating the corresponding buffer. This prevents a malicious client from declaring
a multi-gigabyte payload and exhausting server memory.

| Length Field | Spec Max | Codec Max Constant | Validation |
|---|---|---|---|
| [e.g., frame payload size] | [e.g., 4 GB (u32)] | `MAX_FRAME_PAYLOAD` | Reject if > max; do not allocate |
| [e.g., string length] | [e.g., 255 (u8)] | — | Implicit in type width |
| [e.g., binary data length] | [e.g., 65535 (u16)] | `MAX_BINARY_DATA` | Reject if > max |
| ... | ... | ... | ... |

Rules:
1. Read the length prefix
2. Validate against the configured maximum (or spec-defined maximum if no configuration)
3. Validate that sufficient bytes remain in the frame (length <= remaining bytes)
4. Only then allocate the buffer and read the data

### Collection Size Validation (DoS Prevention)

Every collection count or repeating structure MUST be validated against a configurable
maximum before allocating the collection. This prevents a malicious client from declaring
millions of entries and exhausting memory.

| Collection | Spec Limit | Codec Max Constant | Validation |
|---|---|---|---|
| [e.g., properties list] | [e.g., bounded by remaining length] | `MAX_PROPERTIES` | Reject if count > max |
| [e.g., topic filters in SUBSCRIBE] | [e.g., no spec limit] | `MAX_TOPIC_FILTERS` | Reject if count > max |
| [e.g., field table entries] | [e.g., bounded by table length] | `MAX_TABLE_ENTRIES` | Reject if count > max |
| ... | ... | ... | ... |

Rules:
1. Read the collection count or derive it from the enclosing length
2. Validate against the configured maximum
3. Only then allocate the collection (e.g., `new ArrayList<>(count)`)
4. Validate each element individually during iteration

### Spec-Defined Field Limits

Fields with specification-defined maximum values MUST be validated during decode,
independent of `maxFrameSize`. These are structural protocol limits, not transport-level
size limits.

| Field | Packet/Frame | Spec Max | Validation Action |
|---|---|---|---|
| [e.g., Client ID length] | [e.g., CONNECT] | [e.g., 23 chars (MQTT 3.1)] | Reject with protocol error |
| [e.g., shortstr length] | [e.g., any method] | [e.g., 255 bytes] | Implicit in u8 prefix |
| [e.g., QoS value] | [e.g., PUBLISH] | [e.g., 0-2] | Reject with protocol error |
| ... | ... | ... | ... |

### Remaining Bytes Cross-Check

After reading all fields of a frame or packet, the codec MUST verify that exactly zero
bytes remain (or exactly the expected padding/sentinel). If bytes remain, the frame is
malformed — reject and close the connection. If the codec consumed more bytes than
declared, the length prefix was wrong — reject and close the connection.

## Constants

| Constant | Value | Category | Description |
|---|---|---|---|
| ... | ... | ... | ... |
```

---

### 04 — Transport (`04-$id-transport.md`)

```markdown
# $PROTOCOL_NAME Transport Specification

> **Status:** Backlogged | In Progress | Done
> **Phase:** 2-Transport
> **Module:** `$artifactId`
> **Last Updated:** YYYY-MM-DD

## Transport Matrix

Every protocol MUST implement all four transport variants. A protocol may start with
blocking plain TCP and add the others incrementally, but the target is full coverage.

| Scheme | Factory | I/O Model | Encryption | Status |
|---|---|---|---|---|
| `$id://` | `$IdTransportFactory` | Blocking | None | [Done/Backlogged] |
| `$id+nio://` | `$IdNioTransportFactory` | NIO | None | [Done/Backlogged] |
| `$id+ssl://` | `$IdSslTransportFactory` | Blocking | TLS | [Done/Backlogged] |
| `$id+nio+ssl://` | `$IdNioSslTransportFactory` | NIO | TLS | [Done/Backlogged] |

### Transport Parameter Validation

Parameters from the transport URI MUST be validated at bind/connect time, before the
transport starts accepting connections. Invalid parameters should fail loudly at startup,
not silently at runtime.

#### TCP Parameters

| Parameter | Type | Default | Valid Range | Validation |
|---|---|---|---|---|
| `transport.tcpNoDelay` | boolean | `true` | true/false | Type check |
| `transport.soTimeout` | int | 0 | >= 0 | Reject negative |
| `transport.socketBufferSize` | int | 65536 | > 0 | Reject <= 0 |
| `transport.ioBufferSize` | int | 8192 | > 0 | Reject <= 0 |
| `transport.maximumConnections` | int | — | > 0 | Reject <= 0 |
| `transport.backlog` | int | 5000 | > 0 | Reject <= 0 |

#### Wire Format Parameters

| Parameter | Type | Default | Valid Range | Validation |
|---|---|---|---|---|
| `wireFormat.maxFrameSize` | int | [protocol default] | > 0 | Reject <= 0 |
| [protocol-specific wireformat options] | ... | ... | ... | ... |

#### SSL/TLS Parameters (for `$id+ssl://` and `$id+nio+ssl://`)

| Parameter | Type | Default | Validation |
|---|---|---|---|
| `needClientAuth` | boolean | `false` | Type check |
| `wantClientAuth` | boolean | `false` | Type check |
| `keyStore` | String | — | File must exist if specified |
| `keyStorePassword` | String | — | Required if keyStore set |
| `keyStoreType` | String | `JKS` | Must be valid KeyStore type |
| `trustStore` | String | — | File must exist if specified |
| `trustStorePassword` | String | — | Required if trustStore set |
| `trustStoreType` | String | `JKS` | Must be valid KeyStore type |
| `enabledCipherSuites` | String | — | Comma-separated, each must be supported |
| `enabledProtocols` | String | — | Comma-separated (e.g., `TLSv1.2,TLSv1.3`) |

## Class Hierarchy

[ASCII diagram showing factory and transport inheritance.]

## SPI Registration

[META-INF/services paths and what they resolve to.]

## Transport Filter

### Inbound Path (protocol -> ActiveMQ)

[Data flow: frame unmarshal -> handler dispatch -> command creation.]

### Outbound Path (ActiveMQ -> protocol)

[Data flow: ActiveMQ command -> frame marshal -> wire send.]

## Connection Handler

[State machine for connection lifecycle.]

## Frame Ordering Enforcement

The transport MUST validate that frames arrive in the correct order for the current
connection state. Any frame received out of sequence is a protocol violation — the
transport closes the connection immediately with the appropriate error code.

### Allowed Frames by State

| Connection State | Allowed Frame Types | On Violation |
|---|---|---|
| [e.g., AWAITING_CONNECT] | [e.g., CONNECT only] | [e.g., close without response] |
| [e.g., AWAITING_AUTH] | [e.g., AUTH only] | [e.g., DISCONNECT + close] |
| [e.g., CONNECTED] | [e.g., all except CONNECT] | [e.g., protocol error + close] |
| ... | ... | ... |

Rules:
1. Before dispatching any frame to a handler, check the current connection state
2. If the frame type is not in the allowed set for that state, reject immediately
3. Log the violation at WARN level with the frame type, expected state, and client ID
4. Increment `protocolErrorCount` on the connection
5. Send the appropriate protocol error response (if the protocol allows one at this state)
6. Close the connection

### Content Frame Sequencing (if applicable)

[For protocols that split messages across multiple frame types (e.g., AMQP method →
content header → content body), the transport must enforce that content headers
follow methods that carry content, and that content bodies follow content headers
with the correct cumulative size.]

## Protocol Verifier

[Magic byte detection for protocol identification.]

## Thread Model

[Reader, writer, selector threads.]

## Inactivity Monitor

Each protocol MUST define how connection liveness is detected. There are two approaches:

### Option A: Reuse ActiveMQ's InactivityMonitor

ActiveMQ provides `AbstractInactivityMonitor` / `InactivityMonitor` in
`activemq-client`. It uses `WireFormatInfo` exchange to negotiate timeouts:

| Parameter | Source | Default | Description |
|---|---|---|---|
| `readCheckTime` | `min(local, remote) MaxInactivityDuration` | 30,000 ms | How often to check for read activity |
| `writeCheckTime` | `min(local, remote) MaxInactivityDuration` | 30,000 ms | How often to send a keep-alive if idle |
| `initialDelayTime` | `MaxInactivityDurationInitalDelay` | 30,000 ms | Delay before first check |
| `connectAttemptTimeout` | — | 30,000 ms | Timeout for initial connection handshake |

To reuse: populate `WireFormatInfo.maxInactivityDuration` from the protocol's
keep-alive negotiation, and insert `InactivityMonitor` into the transport filter chain.
The monitor sends `KeepAliveInfo` commands on write idle and closes on read idle.

### Option B: Protocol-Specific Inactivity Monitor

If the protocol has its own heartbeat mechanism (e.g., AMQP heartbeat frames,
MQTT PINGREQ/PINGRESP), implement a custom monitor that:

1. Sends protocol-native heartbeat frames (not `KeepAliveInfo`)
2. Uses the protocol-negotiated interval (from handshake, not `WireFormatInfo`)
3. Closes with the protocol-appropriate error code on timeout

Document which option is used and why:

| Aspect | Decision |
|---|---|
| Monitor type | Reuse `InactivityMonitor` / Custom `$IdInactivityMonitor` |
| Heartbeat frame | [e.g., KeepAliveInfo / PINGREQ / Heartbeat frame type 8] |
| Interval source | [e.g., WireFormatInfo / CONNECT keep-alive / Connection.Tune heartbeat] |
| Timeout multiplier | [e.g., 2x interval / 1.5x interval] |
| Timeout action | [e.g., close silently / DISCONNECT 0x8D / Connection.Close 320] |

## TransportConnection Field Mapping

Each protocol transport MUST populate `TransportConnection` fields during the
connection handshake so that protocol connections are visible and correctly classified
in JMX and the admin console. The `ManagedTransportConnection` subclass registers
JMX MBeans using these fields.

### Required Fields

| TransportConnection Field | Method | Purpose | How to Populate |
|---|---|---|---|
| `connectionId` | `getConnectionId()` | Unique connection identifier | Set from `ConnectionInfo.connectionId` dispatched to broker |
| `remoteAddress` | `getRemoteAddress()` | Client IP:port | Automatic from `transport.getRemoteAddress()` |
| `wireFormatInfo` | `getWireFormatInfo()` | Protocol metadata for JMX | See WireFormatInfo section below |
| `networkConnection` | `isNetworkConnection()` | Bridge vs client connection | Set `true` if connection is from a network connector / federation bridge |
| `slow` | `isSlow()` | Slow consumer detection | Managed by broker; no protocol action needed |
| `statistics` | `getStatistics()` | Byte/message counters | Automatic from transport layer |

### Best-Effort Mapping

Map protocol-specific connection metadata to ActiveMQ concepts:

| Protocol Concept | ActiveMQ Concept | Mapping Strategy |
|---|---|---|
| [e.g., MQTT Client ID] | `ConnectionInfo.clientId` | Direct mapping |
| [e.g., AMQP virtual host] | [context/metadata] | Store as connection property |
| [e.g., auth username] | `ConnectionInfo.userName` | Direct mapping |
| [e.g., protocol version] | `WireFormatInfo.version` | Map to integer version |
| [e.g., client product name] | `WireFormatInfo.providerName` | From client properties |

## WireFormatInfo Population

Each protocol MUST populate a `WireFormatInfo` instance during the connection handshake
and attach it to the `TransportConnection`. This makes protocol metadata visible via
JMX through the connection's MBean.

### Required WireFormatInfo Fields

| Field | Method | Purpose | Source |
|---|---|---|---|
| `magic` | `getMagic()` | Protocol identification | Protocol magic bytes (e.g., `MQTT`, `AMQP`) |
| `version` | `getVersion()` | Protocol version number | From handshake negotiation |
| `maxInactivityDuration` | Property `MaxInactivityDuration` | Keep-alive interval (ms) | From protocol keep-alive negotiation |
| `maxFrameSize` | Property `MaxFrameSize` | Max frame/packet size | From protocol negotiation or config |

### Recommended WireFormatInfo Properties

Populate via `wireFormatInfo.setProperty(key, value)`:

| Property Key | Type | Purpose | Source |
|---|---|---|---|
| `ProviderName` | String | Client library name | From client handshake metadata |
| `ProviderVersion` | String | Client library version | From client handshake metadata |
| `PlatformDetails` | String | Client platform | From client handshake metadata |
| `CacheEnabled` | boolean | Marshalling cache | Protocol-specific |
| `TightEncodingEnabled` | boolean | Compact encoding | Protocol-specific |
| [protocol-specific keys] | ... | ... | ... |

### Population Timing

1. Create `WireFormatInfo` during `WireFormat` construction (set magic, default version)
2. Update version and properties after handshake negotiation completes
3. Call `transportConnection.setWireFormatInfo(info)` before dispatching `ConnectionInfo` to broker
4. The `ManagedTransportConnection` uses this to populate JMX attributes
```

---

### 05 — Client (`05-$id-client.md`)

```markdown
# $PROTOCOL_NAME Client Module

> **Status:** Backlogged | In Progress | Done
> **Phase:** 3-Client
> **Module:** `$artifactId`
> **Last Updated:** YYYY-MM-DD

## Overview

[Purpose: connect to external broker of this protocol type.]

## Client API

[Public method signatures.]

## Connection Lifecycle

[State diagram.]

## Error Handling

[How remote errors surface.]

## Configuration

[Client-side connection options.]
```

---

### 06 — Network Connector (`06-$id-networkconnector.md`)

Server-to-server connectivity MUST use ActiveMQ's `NetworkConnector` infrastructure
rather than standalone bridge beans. `NetworkConnector` provides JMX registration,
broker lifecycle integration (start/stop with the broker), wrapper transport protocols
(`static:`, `failover:`, multicast discovery), destination filtering, and bridge
statistics — none of which should be reimplemented per protocol.

When the remote system speaks a non-OpenWire protocol, `DemandForwardingBridgeSupport`
cannot be used directly (it assumes OpenWire on both sides). Instead, implement a
custom `NetworkConnector` subclass that manages `NetworkBridge` instances using the
protocol's client module. The client module (`activemq-client-$id`) handles the
remote protocol; the bridge translates between ActiveMQ commands on the local side
and protocol-native operations on the remote side.

#### Credential and Configuration Field Mapping

`NetworkBridgeConfiguration` defines standard fields for credentials and connection
parameters. Protocol implementations MUST reuse these fields rather than defining
their own, mapping them to protocol-specific equivalents:

| NetworkBridgeConfiguration Field | Purpose | Protocol Mapping |
|---|---|---|
| `userName` | Local broker authentication | ActiveMQ-side connection credentials |
| `password` | Local broker authentication | ActiveMQ-side connection credentials |
| `remoteUserName` | Remote broker authentication | Map to protocol auth (e.g., AMQP SASL, MQTT username) |
| `remotePassword` | Remote broker authentication | Map to protocol auth (e.g., AMQP SASL, MQTT password) |
| `name` | Connector display name | Used in JMX ObjectName and logging |
| `brokerName` | Local broker name | Bridge identification |
| `staticBridge` | Disable demand forwarding | Use for static binding configuration |
| `prefetchSize` | Consumer prefetch | Map to protocol prefetch (e.g., AMQP QoS prefetch-count) |

Protocol-specific parameters (virtual host, exchange names, queue bindings, TLS
settings for the remote connection) should be passed as URI query parameters on the
network connector URI, e.g.:
`ramqp://rabbitmq:5672?virtualHost=/&remoteUserName=guest&remotePassword=guest`

```markdown
# $PROTOCOL_NAME Network Connector

> **Status:** Backlogged | In Progress | Done
> **Phase:** 4-Bridge
> **Last Updated:** YYYY-MM-DD

## Overview

[How ActiveMQ connects to an external $PROTOCOL broker via NetworkConnector.
Explain why NetworkConnector is used instead of a standalone bridge bean.]

## Architecture

### Class Hierarchy

```
NetworkConnector (ActiveMQ core)
  └── $IdNetworkConnector
        └── manages $IdNetworkBridge implements NetworkBridge
              ├── local side: broker command dispatch (OpenWire)
              └── remote side: $IdClient (protocol-native)
```

### Component Diagram

[ASCII diagram showing local broker ↔ bridge ↔ remote broker,
with protocol translation point labeled.]

## Configuration

### Basic Network Connector

```xml
<networkConnectors>
  <networkConnector name="$id-bridge"
                    uri="$id://remote-host:$port?param=value"
                    remoteUserName="user"
                    remotePassword="pass">
    <staticallyIncludedDestinations>
      <queue physicalName="orders.>"/>
    </staticallyIncludedDestinations>
  </networkConnector>
</networkConnectors>
```

### With Failover

```xml
<networkConnector uri="failover:($id://host1:$port,$id://host2:$port)"/>
```

### URI Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| [protocol-specific params] | ... | ... | ... |

### Credential Mapping

| Config Field | Maps To |
|---|---|
| `userName` / `password` | Local ActiveMQ broker credentials |
| `remoteUserName` / `remotePassword` | Remote $PROTOCOL server credentials |
| [protocol-specific auth] | [e.g., SASL mechanism, client certificate] |

## Message Flow

### Inbound (External -> ActiveMQ)

[Sequence: remote subscribe/consume → protocol message → translate to
ActiveMQMessage → dispatch to local broker destination.]

### Outbound (ActiveMQ -> External)

[Sequence: local broker MessageDispatch → translate to protocol message →
publish to remote broker.]

### Property Translation

[Reference 08-$id-transforms.md for the full mapping table.
Note any bridge-specific properties added (e.g., `JMS_$ID_BridgeSource`).]

## Destination Filtering

[How `staticallyIncludedDestinations`, `dynamicallyIncludedDestinations`,
and `excludedDestinations` map to protocol-native queue/topic/exchange bindings.]

## JMX

[MBean ObjectName pattern, exposed attributes and operations.
These come from NetworkBridge interface implementation.]

## Reconnection and Failover

[How the bridge handles remote connection loss. Use failover transport
for automatic reconnection rather than custom reconnection logic.]

## Statistics

| Metric | Source | Description |
|---|---|---|
| Enqueue count | `NetworkBridgeStatistics` | Messages forwarded to remote |
| Dequeue count | `NetworkBridgeStatistics` | Messages received from remote |
```

---

### 07 — Coding Standards (`07-$id-coding.md`)

```markdown
# $PROTOCOL_NAME Coding Standards

> **Status:** Backlogged | In Progress | Done
> **Phase:** 1-Codec (evolves through all phases)
> **Last Updated:** YYYY-MM-DD

## Package Structure

[Java package naming conventions.]

## Naming Conventions

[Class, constant, method naming patterns.]

## Codec Patterns

[Encode/decode signatures, byte order handling, error handling.]

## Error Handling

[Protocol error representation, reason/error codes, exception hierarchy.]

## Logging Conventions

[Log level usage.]

## Thread Safety

[Concurrency patterns.]
```

---

### 08 — Transforms (`08-$id-transforms.md`)

```markdown
# $PROTOCOL_NAME Message Transformation Specification

> **Status:** Backlogged | In Progress | Done
> **Phase:** 2-Transport
> **Last Updated:** YYYY-MM-DD

## Overview

[How protocol-specific message metadata maps to/from JMS.]

## Property Naming Convention

| Prefix | Source | Description |
|---|---|---|
| `JMS_$ID_*` | Protocol-specific | Vendor properties |
| `JMSX*` | JMS spec | Standard JMS extension properties |
| Standard JMS | JMS spec | JMSCorrelationID, JMSReplyTo, etc. |

## Inbound Transform (Protocol -> ActiveMQ)

### Message Type Selection

[Protocol message type/format -> JMS message type.]

### Header Mapping

| Protocol Field | JMS Property | Type | Notes |
|---|---|---|---|
| ... | ... | ... | ... |

## Outbound Transform (ActiveMQ -> Protocol)

### Header Construction

| JMS Property | Protocol Field | Type | Notes |
|---|---|---|---|
| ... | ... | ... | ... |

## Header Priority

[Precedence when a field appears in multiple sources.]

## Character Encoding

[Charset negotiation and conversion.]
```

---

### 09 — Handshake (`09-$id-handshake.md`)

```markdown
# $PROTOCOL_NAME Connection Handshake Specification

> **Status:** Backlogged | In Progress | Done
> **Phase:** 2-Transport
> **Last Updated:** YYYY-MM-DD

## Overview

[Connection establishment sequence.]

## State Machine

[ASCII state machine diagram.]

| State | Trigger | Action | Next State |
|---|---|---|---|
| ... | ... | ... | ... |

## Wire Capture Reference

[Annotated hex dumps of handshake exchanges from a reference implementation.]

### Frame 1: [Direction] — [Description]

[Hex dump with annotations.]

## Negotiated Parameters

| Parameter | Proposed By | Default | Range | Notes |
|---|---|---|---|---|
| ... | Client / Server | ... | ... | ... |

## Authentication

[Mechanism, credential exchange format.]

## Error Scenarios

[Wrong protocol, auth failure, version mismatch.]
```

---

### 10 — Config (`10-$id-config.md`)

```markdown
# $PROTOCOL_NAME Configuration Reference

> **Status:** Backlogged | In Progress | Done
> **Phase:** 2-Transport
> **Last Updated:** YYYY-MM-DD

## URI Format

```
$id://[host]:[port]?[option=value&...]
```

## Option Reference

### Transport Options

| Option | Type | Default | Description |
|---|---|---|---|
| ... | ... | ... | ... |

### Wire Format Options

| Option | Type | Default | Description |
|---|---|---|---|
| ... | ... | ... | ... |

### Protocol Options

| Option | Type | Default | Description |
|---|---|---|---|
| ... | ... | ... | ... |

### SSL/TLS Options

| Option | Type | Default | Description |
|---|---|---|---|
| ... | ... | ... | ... |

## Broker Configuration Examples

### Basic Transport Connector

```xml
<transportConnectors>
    <transportConnector name="$id" uri="$id://0.0.0.0:$port"/>
</transportConnectors>
```

### With SSL

[...]

## Frame Size Enforcement

[How max frame size is configured and enforced.]

## Destination Policy

[Protocol-specific destination configuration.]
```

---

### 11 — Tests (`11-$id-tests.md`)

```markdown
# $PROTOCOL_NAME Test Plan

> **Status:** Backlogged | In Progress | Done
> **Phase:** 5-Tests
> **Last Updated:** YYYY-MM-DD

## Test Framework

| Aspect | Standard |
|---|---|
| Framework | JUnit 6 |
| Parallel execution | `@Category(ParallelTest.class)` opt-in via `all-parallel` Maven profile |
| Assertions | JUnit 6 `Assertions` + Hamcrest matchers where readability benefits |
| Lifecycle | `@BeforeEach` / `@AfterEach` for per-test broker/resource setup and teardown |
| Naming | `{ClassUnderTest}Test` for unit, `{Feature}Test` for functional |

## Directory Structure

Tests are organized into three tiers by scope and execution cost:

```
$module/
  src/test/java/                        # Tier 1: Unit tests (per-module, fast)
    .../$id/
      codec/                            # Codec encode/decode round-trips
      handler/                          # Handler logic with stub transports
      ...Test.java

test/$id/                               # Tier 2: Functional tests (embedded broker)
  src/test/java/
    .../$id/
      ...Test.java                      # One-time-use embedded BrokerService

it/$id/                                 # Tier 3: Integration tests (external systems)
  src/test/java/
    .../$id/
      client/                           # Cross-client-library compatibility
      auth/                             # LDAP, Kerberos, JAAS integration
      container/                        # Testcontainers-based tests
      ...IT.java
```

| Tier | Location | Scope | Broker | Run Phase |
|---|---|---|---|---|
| 1 — Unit | `$module/src/test/` | Single class, no I/O | None (stubs/mocks) | `mvn test` |
| 2 — Functional | `test/$id/` | Multi-class, embedded broker | Embedded, one-time-use `BrokerService` | `mvn test -pl test/$id` |
| 3 — Integration | `it/$id/` | Cross-system, external services | Embedded + Testcontainers / external | `mvn verify -pl it/$id` |

## Dynamic Port Allocation

All tests that bind server sockets MUST use dynamic port allocation to avoid port
conflicts during parallel execution. Never hard-code port numbers.

```java
// Bind to port 0 — OS assigns an available port
TransportConnector connector = broker.addConnector("$id://0.0.0.0:0");
broker.start();

// Retrieve the actual bound port
String connectUri = connector.getPublishableConnectString();
```

For tests that need the port number before the broker starts, use:

```java
try (ServerSocket ss = new ServerSocket(0)) {
    int port = ss.getLocalPort();
    ss.close();
    // use port immediately — window is small but acceptable for tests
}
```

## Wait and Wait.Condition

Use `Wait.waitFor(Condition)` to poll for asynchronous state transitions instead of
`Thread.sleep()`. This makes tests deterministic and fast.

```java
import org.apache.activemq.util.Wait;

// Wait for a consumer to come online before publishing
assertTrue(Wait.waitFor(() ->
    broker.getDestination(queue).getConsumers().size() == 1,
    5000,   // max wait (ms)
    10      // poll interval (ms) — use 10ms for fast unit tests
));

// Wait for message delivery
assertTrue(Wait.waitFor(() -> received.get() >= expectedCount, 5000, 10));
```

| Parameter | Default | Test Override | Notes |
|---|---|---|---|
| `duration` | 30,000 ms | 5,000 ms typical | Keep short for unit tests |
| `sleepMillis` | 1,000 ms | **10 ms** | Use 10ms for fast polling in unit tests |

### Common Wait Patterns

| What to Wait For | Condition |
|---|---|
| Consumer online | `destination.getConsumers().size() == N` |
| Message delivered | `listener.getReceivedCount() >= N` |
| Connection established | `broker.getTransportConnectors().get(0).getConnections().size() == N` |
| Queue drained | `destination.getDestinationStatistics().getMessages().getCount() == 0` |
| Broker fully started | `broker.isStarted() && !broker.isStopping()` |

## Parallel Test Execution

Tests tagged with `@Category(ParallelTest.class)` run concurrently under the
`all-parallel` Maven profile. Requirements for parallel-safe tests:

1. **Dynamic ports** — no hard-coded ports (see above)
2. **Unique broker names** — use `@TestInfo` or method name as broker name
3. **Unique data directories** — use `@TempDir` or `target/test-data/{testName}`
4. **No static mutable state** — no shared counters, no static broker references
5. **Self-contained teardown** — `@AfterEach` must stop all brokers and close all connections

## Unit Test Coverage (Tier 1)

Full coverage of all codec and handler classes within each module. No embedded broker.

### Codec Tests

| Test Class | Coverage | Pattern |
|---|---|---|
| `$IdDataTypesTest` | Primitive read/write | Round-trip, boundary values, overflow |
| `$IdFrameCodecTest` | Frame envelope | Parse, frame-end validation, malformed input |
| `$IdMethodCodecTest` | Per-method encode/decode | Round-trip for every method/packet type |
| `$IdValidationTest` | Codec validation | Oversized lengths, collection limits, remaining-bytes |

### Codec Test Patterns

1. **Round-trip** — encode a value, decode the bytes, assert equality
2. **Boundary values** — min, max, zero, one-below-max, one-above-max
3. **Malformed input** — truncated frames, wrong type tags, invalid UTF-8
4. **Oversized declarations** — length prefix declaring more bytes than available
5. **Byte order** — verify big-endian encoding for multi-byte integers

### Transport Tests

| Test Class | Coverage | Pattern |
|---|---|---|
| `$IdTransportFilterTest` | Inbound/outbound dispatch | Stub transport, verify command translation |
| `$IdConnectionHandlerTest` | Handshake state machine | Step through states, verify transitions |
| `$IdProtocolVerifierTest` | Magic byte detection | Valid header, partial header, wrong protocol |
| `$IdFrameOrderingTest` | Frame ordering enforcement | Out-of-order frames rejected, error codes |

### Handler Tests

| Test Class | Coverage | Pattern |
|---|---|---|
| `$IdConnectHandlerTest` | Connection handshake | Auth success/failure, parameter negotiation |
| `$IdPublishHandlerTest` | Message publish | QoS levels, retain, topic validation |
| `$IdSubscribeHandlerTest` | Subscription management | Wildcard topics, shared subscriptions |

## Test Utilities

### Stub Transport

A `Stub$IdTransport` that captures outbound frames and replays inbound frames
without a real TCP connection. Used for all Tier 1 tests.

### Frame Builders

Fluent builders for constructing test frames:

```java
// Example: build a valid CONNECT frame for testing
$IdFrame connect = $IdFrameBuilder.connect()
    .clientId("test-client")
    .keepAlive(60)
    .build();
```

### Assertions

Protocol-specific assertion helpers:

```java
// Assert a specific error response was sent
assertDisconnectWithCode(transport.lastOutbound(), 0x82);
assertConnectionClosed(transport, 503);
```

## Running Tests

```bash
# Tier 1: Unit tests for a single module
mvn test -pl $module -am

# Tier 1: All unit tests with parallel execution
mvn test -P all-parallel

# Tier 2: Functional tests with embedded broker
mvn test -pl test/$id -am

# Tier 3: Integration tests
mvn verify -pl it/$id -am

# All tiers
mvn verify -pl $module,test/$id,it/$id -am
```
```

---

### 12 — Integration Tests (`12-$id-itests.md`)

```markdown
# $PROTOCOL_NAME Integration Test Strategy

> **Status:** Backlogged | In Progress | Done
> **Phase:** 5-Tests
> **Last Updated:** YYYY-MM-DD

## Test Scope

Integration tests validate cross-system behavior: real client libraries connecting
to an embedded ActiveMQ broker, external services via Testcontainers, and cross-protocol
message flow. These are Tier 3 tests that run during `mvn verify`.

## Directory Structure

```
it/$id/
  src/test/java/
    .../$id/
      client/           # Client library compatibility
        $ClientLibTest.java
      auth/             # Authentication integration
        LdapAuthIT.java
        JaasAuthIT.java
      container/        # Testcontainers-based
        $RefProductIT.java
      xprotocol/        # Cross-protocol messaging
        $IdToOpenwireIT.java
        OpenwireTo$IdIT.java
      bridge/           # Federation / network connector
        $IdBridgeIT.java
  pom.xml               # Testcontainers, client library dependencies
```

## Embedded Broker Setup

Integration tests use a one-time-use `BrokerService` per test. Dynamic ports,
unique broker names, and `Wait.waitFor()` are mandatory.

```java
@BeforeEach
void setUp(TestInfo info) throws Exception {
    broker = new BrokerService();
    broker.setBrokerName(info.getDisplayName());
    broker.setPersistent(false);
    broker.setUseJmx(false);
    broker.setDataDirectory("target/test-data/" + info.getDisplayName());

    connector = broker.addConnector("$id://0.0.0.0:0");
    broker.start();

    assertTrue(Wait.waitFor(() -> broker.isStarted(), 5000, 10));
    connectUri = connector.getPublishableConnectString();
}

@AfterEach
void tearDown() throws Exception {
    if (broker != null) {
        broker.stop();
        broker.waitUntilStopped();
    }
}
```

## Testcontainers

For testing against reference implementations (RabbitMQ, Mosquitto, IBM MQ),
use Testcontainers with auto-skip when Docker is unavailable.

```java
@Container
static GenericContainer<?> $refProduct = new GenericContainer<>("$image:$tag")
    .withExposedPorts($port)
    .waitingFor(Wait.forListeningPort());

@BeforeAll
static void checkDocker() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker not available — skipping integration tests");
}
```

| Container | Image | Port | Purpose |
|---|---|---|---|
| [e.g., RabbitMQ] | `rabbitmq:4-management` | 5672, 15672 | Client compatibility, federation |
| [e.g., Mosquitto] | `eclipse-mosquitto:2` | 1883, 8883 | Client compatibility |
| [e.g., OpenLDAP] | `osixia/openldap:1.5.0` | 389, 636 | LDAP authentication |
| [e.g., IBM MQ] | `icr.io/ibm-messaging/mq:latest` | 1414 | MQI bridge testing |

## Test Paths

Every protocol must define and test at least three communication paths. Each path
exercises different code paths in the codec, transport, and property mapping layers.

```
Path A: activemq-client-$id    ──►  Reference Server
         (our client)                (real external broker)

Path B: Reference Client       ──►  activemq-server-$id
         (real client library)       (embedded ActiveMQ + $id:// connector)

Path C: activemq-client-$id    ──►  activemq-server-$id
         (our client)                (embedded ActiveMQ + $id:// connector)
```

| Path | Producer | Consumer | What It Validates |
|---|---|---|---|
| **A** | Our client | Reference client (via external broker) | Our encode path against a real broker |
| **B** | Reference client | JMS consumer (via ActiveMQ `$id://`) | Our decode/transform path with real client frames |
| **C** | Our client | JMS consumer (via ActiveMQ `$id://`) | Encode/decode symmetry — round-trip through our own codec |

All three paths must exercise the **full message coverage matrix**. A fixture that
passes on Path A but fails on Path B reveals a decode bug. A fixture that passes on
A and B but fails on C reveals an encode/decode asymmetry.

### Multi-Version Testing

When the reference product has multiple major versions with the same wire protocol
(e.g., RabbitMQ 3.x and 4.x, IBM MQ 9.3 and 9.4), add parallel paths that mirror
Paths A and B against the older version. Path C (our client → our server) does not
need to be repeated since it is version-independent.

```
Path D:  activemq-client-$id    ──►  Reference Server (older version)
Path E:  Reference Client       ──►  activemq-server-$id (via older server)
```

Both containers can run in parallel using Testcontainers. Test classes should extend
a shared abstract base so that the fixture matrix, validation logic, and result
reporting are not duplicated — only the infrastructure setup (which container, which
client version) differs per path.

Note version-specific behavioral differences in a table (e.g., default queue types,
deprecated features, negotiation defaults) and handle them in shared test setup
rather than duplicating test logic.

## Message Coverage Matrix

The coverage matrix crosses **message payload type** x **value tier** x **test path**.
Every cell must pass on all three paths.

### Dimension 1: Message Payload Types

Enumerate every distinct message payload format the protocol supports. Include:
- Text payloads (with content-type or format indicator)
- Binary payloads (raw bytes, no content type)
- Structured payloads (maps, streams, objects — if the protocol supports them)
- Empty / absent body

| # | Type ID | Protocol Indicator | JMS Message Type | Body Content |
|---|---|---|---|---|
| 1 | [e.g., `text-plain`] | [e.g., content-type=`text/plain`, format=`MQSTR`] | TextMessage | [description] |
| 2 | [e.g., `bytes-raw`] | [e.g., no content-type, format=`""`] | BytesMessage | [description] |
| ... | ... | ... | ... | ... |
| N | `empty` | [absent/null] | BytesMessage | No body (0 bytes) |

### Dimension 2: Value Tiers

Each message type must be tested at four value tiers that exercise boundary conditions.

| Tier | Suffix | Purpose | Coverage |
|---|---|---|---|
| **min** | `-min` | All header fields absent, at zero, or at minimum values | Absent/null/zero-default handling. Minimal body (1 byte or 1 char). Tests that decoders handle missing fields without NPE. |
| **max** | `-max` | All header fields populated at maximum values | Every header field set. Body at max payload size (e.g. 64KB). Max-length strings. All header/property types exercised. Tests overflow, large body handling, field count limits. |
| **empty** | `-empty` | Headers populated but body is 0 bytes | Tests body-absent path with properties present. Validates that zero-length body produces a valid message (not null). |
| **full** | `-full` | Production-realistic message | Representative message with typical header values, moderate body size, common header combinations. The "golden path" fixture. |

**Core fixture count:** (number of payload types) x 4 tiers

### Dimension 3: Header / Property Coverage

Each protocol has its own header fields that map to JMS properties. The max and
full tiers must exercise:

#### Property Presence Testing

| Scenario | Test | Expected Behavior |
|---|---|---|
| All properties absent | min tier | JMS properties return null / default values |
| All properties populated | max tier | Every JMS mapping has a non-null value |
| Typical subset populated | full tier | Common properties set, uncommon absent |

#### Null and Empty Value Handling

For every string-typed header field, test:

| Scenario | Test Value | Expected Behavior |
|---|---|---|
| Absent (not set) | Not included in message | JMS property returns null |
| Empty string | `""` (zero-length) | JMS property returns `""` (not null) |
| Populated | `"value"` | JMS property returns `"value"` |

#### Data Type Coverage

For protocols with typed header/property collections (field tables, user properties),
the max tier must include at least one value of each supported type:

| Protocol Type | Test Key | Test Value | Expected JMS Type |
|---|---|---|---|
| string | `hdr_string` | `"hello"` | String |
| integer | `hdr_int` | `42` | int |
| long | `hdr_long` | `9999999999` | long |
| float | `hdr_float` | `3.14` | float |
| double | `hdr_double` | `2.718` | double |
| boolean | `hdr_bool` | `true` | boolean |
| [protocol-specific types] | ... | ... | ... |
| null/void | `hdr_null` | *(null)* | absent or null |

### Dimension 4: Body Boundary Testing

| Size | Purpose | Fixture |
|---|---|---|
| 0 bytes | Empty body handling | `*-empty` tier |
| 1 byte / 1 char | Minimal non-empty | `*-min` tier |
| Typical (256B-1KB) | Normal payload | `*-full` tier |
| At max frame size | Single-frame boundary | `*-max` tier |
| Exceeds max frame size | Multi-frame / fragmented body | Dedicated fixture |
| Large (1MB+) | Stress / memory | Dedicated fixture |

For text bodies, test encoding variants:
- ASCII only
- UTF-8 multibyte (CJK, emoji, accented characters)
- Protocol-specific special characters (XML entities, JSON escapes, etc.)

For binary bodies, test byte patterns:
- All zeros, all 0xFF, sequential 0x00-0xFF, deterministic pattern

### Extra Fixtures

Beyond the core matrix, add protocol-specific fixtures for:
- Encoding edge cases (unicode, charset conversion, escaping)
- Multi-frame body reassembly
- Large payload stress
- Error paths (mandatory return, channel error, malformed content)
- Connection liveness (heartbeat under idle)

### Fixture File Format

Each fixture is a `.properties` file defining expected values for validation:

```properties
# Metadata
fixture.type=[type-id]
fixture.tier=[min|max|empty|full]

# Protocol header fields (absent key = not set on publish)
header.[fieldName]=[value]

# Body
body.type=[text|bytes|empty]
body.text=[expected text content]
body.length=[expected byte length]

# Expected JMS mapping
jms.deliveryMode=[1|2]
jms.priority=[0-9]
jms.correlationId=[value]
jms.type=[value]
jms.property.[name]=[value]
```

## Test Categories

### Client Library Compatibility

Test with real protocol client libraries connecting to the embedded ActiveMQ broker:

| Client Library | Language | Purpose |
|---|---|---|
| [e.g., Paho] | Java | Reference MQTT client |
| [e.g., amqp-client] | Java | Reference AMQP client |

Verify: connect, publish, subscribe, acknowledge, disconnect, reconnect, auth.

### Cross-Protocol Messaging

Publish via one protocol, consume via another. Validates message transformation
correctness end-to-end.

| Producer Protocol | Consumer Protocol | What to Verify |
|---|---|---|
| `$id` | OpenWire (JMS) | Properties mapped correctly, body intact |
| OpenWire (JMS) | `$id` | Outbound transform correct |
| `$id` | `$id` | Native round-trip |

### Authentication Integration

| Auth Backend | Container/Config | What to Verify |
|---|---|---|
| Simple (properties file) | Embedded | Username/password accept/reject |
| LDAP | Testcontainer `openldap` | Bind auth, group-based authorization |
| JAAS | Embedded config | Custom login module |
| Certificate (mTLS) | Embedded SSL | Client cert DN extraction, authorization |

### Federation / Bridge

Test the network connector bridging messages between ActiveMQ and the reference
implementation (or between two ActiveMQ brokers with this protocol).

## Running Integration Tests

```bash
# All integration tests (requires Docker for Testcontainers)
mvn verify -pl it/$id -am

# Skip Testcontainer tests when Docker is unavailable (auto-skip via assumeTrue)
mvn verify -pl it/$id -am

# Specific test category
mvn verify -pl it/$id -am -Dtest="**/client/*"
```
```

---

### 13 — Tools (`13-$id-tools.md`)

```markdown
# $PROTOCOL_NAME Developer / Operator Tooling

> **Status:** Backlogged | In Progress | Done
> **Phase:** 6-Tools
> **Last Updated:** YYYY-MM-DD

## Overview

[Summary of available tools.]

## [Tool Name]

### Purpose

[What the tool does.]

### Usage

[Command-line examples.]

### Output Format

[Example output with annotations.]

## Diagnostic Guides

### [Common Scenario]

[Step-by-step diagnostic procedure.]
```

---

### 14 — Admin Guide (`14-$id-admin.md`)

```markdown
# $PROTOCOL_NAME Administrator's Guide

> **Status:** Backlogged | In Progress | Done
> **Phase:** 2-Transport (evolves through all phases)
> **Last Updated:** YYYY-MM-DD

## Transport Connector Setup

[Basic XML configuration.]

## Protocol-Specific Behaviors

[Differences from other ActiveMQ protocols.]

## Topic / Destination Configuration

[Protocol-specific topic naming, wildcards, policies.]

## Security Configuration

[Authentication, SSL/TLS, authorization.]

## Flow Control

[Producer flow control, prefetch, throttling.]

## Monitoring

[JMX beans, log categories, metrics.]

## Troubleshooting

| Symptom | Cause | Resolution |
|---|---|---|
| ... | ... | ... |
```

---

### 15 — Gap Analysis (`15-$id-gaps.md`)

```markdown
# $PROTOCOL_NAME Feature Gap Analysis

> **Status:** Backlogged | In Progress | Done
> **Phase:** All phases
> **Reference Implementation:** [e.g., IBM MQ 9.x / RabbitMQ 3.x]
> **Last Updated:** YYYY-MM-DD

## Overview

[What is being compared and why.]

## Gap Summary

| Category | Total Gaps | Critical | Non-Critical |
|---|---|---|---|
| ... | ... | ... | ... |

## [Category]

| # | Feature | Reference Behavior | ActiveMQ Status | Priority | Notes |
|---|---|---|---|---|---|
| ... | ... | ... | Implemented / Gap / N/A | ... | ... |

## Not Applicable

[Features that do not apply to ActiveMQ, with rationale.]
```

---

### 16 — End-User Guide (`16-$id-guide.md`)

Use when the protocol is mature enough to consolidate developer and administrator documentation.

```markdown
# ActiveMQ $PROTOCOL_NAME Transport Guide

> **Status:** Backlogged | In Progress | Done
> **Last Updated:** YYYY-MM-DD

## Overview

[What the transport does, how clients connect.]

## Developer Guide

### Supported Operations
[Which API calls are supported and what they map to.]

### Message Property Mapping
[Protocol field -> JMS property tables.]

### Message Format Behavior
[Behavior-level: "your message will be delivered as X".]

### Topic Conventions
[Separator/wildcard translation, topic alias usage.]

### Transaction Support
[Transaction semantics from the client perspective.]

## Administrator Guide

### Transport Connector Setup
[XML configuration.]

### Transport Options
[Option tables.]

### Topic Configuration
[Topic policy, aliases.]

### Destination Policy Configuration
[Flow control, eviction, memory limits.]

### Character Encoding
[Supported charsets, encoding behavior.]

### Behavioral Differences
[Protocol vs ActiveMQ comparison table.]

## Reference

### Server Configuration
[Transport connector URI, destination policies.]

### Client Configuration
[Client connection parameters, supported API calls.]

## Known Limitations

- [Limitation 1]
- [Limitation 2]
```

NO class names, method signatures, or internal code references in this document.

---

### 17 — Engineering Internals (`17-$id-internals.md`)

```markdown
# ActiveMQ $PROTOCOL_NAME Transport — Engineering Reference

> **Status:** Backlogged | In Progress | Done
> **Last Updated:** YYYY-MM-DD

## Module Structure
[Maven module layout, SPI registration, package structure.]

## Message Format Pipeline
[Class-level dispatch flow diagrams.]

## Character Encoding Implementation
[Codec internals, charset resolution, edge cases.]

## Validation and Error Handling
[Structural validation, application errors, content degradation.]

## Connection Handler State Machine
[State diagram, segment type dispatch.]

## Consumer Flow
[Sequence diagram.]

## Producer Flow Control
[Internal flow control implementation.]

## Protocol Error Handling
[Error counters, recoverable vs fatal errors.]

## Key Classes
[Class/module/role reference table.]

## Test Coverage
[Codec tests, fixture pipeline, integration test strategy.]

## Tools
[Internal tooling: frame loggers, proxies, fixture generators.]
```

NO XML config examples in this document (those belong in the guide).

---

## Research Findings Template

```markdown
# $PROTOCOL Research: $TOPIC

> **Status:** Active | Archived (absorbed into spec)
> **Related Spec:** [link to formal spec doc, if any]
> **Last Updated:** YYYY-MM-DD

## Question / Objective

[What are we trying to understand?]

## Findings

### [Finding 1]

[Observation, evidence, wire captures, code references.]

### [Finding 2]

[...]

## Wire Captures / Evidence

[Annotated hex dumps, protocol traces, test output.]

## Reference Implementation Behavior

[How the reference product handles this. Version tested,
configuration used, observed behavior.]

## Design Implications

[What these findings mean for our implementation.]

## Open Questions

[What remains unknown.]

## Sources

[Links to specs, documentation, source code.]
```

### Research Doc Lifecycle

1. **Create** when investigating a new feature or protocol behavior
2. **Update** as findings accumulate from wire captures, testing, code analysis
3. **Reference** from backlog items and spec docs
4. **Archive** when findings are fully absorbed into formal spec docs (set status to "Archived")

Research docs are NOT deleted — they preserve the investigation trail.

---

## Supplementary Specs (20+)

Protocol-specific specs that don't fit the standard categories use numbers 20+. Free-form structure. Examples:

- MQI: `20-mqi-rfh2.md`, `21-mqi-xqh.md`, `22-mqi-dlh.md`, `23-mqi-inactivity.md`
- MQTT5: `20-mqtt5-enforcement.md`, `21-mqtt5-retain-journal.md`

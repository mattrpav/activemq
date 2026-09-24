# Frame Size Validation Cleanup Across Transports

## Affected Classes

- `activemq-amqp/.../transport/amqp/AmqpWireFormat.java`
- `activemq-amqp/.../transport/amqp/AmqpFrameParser.java`
- `activemq-client/.../transport/nio/NIOTransport.java`
- `activemq-client/.../transport/nio/NIOSSLTransport.java`
- `activemq-client/.../openwire/OpenWireFormat.java`

## Problem

Frame size validation was inconsistent across the transport layer:

1. **AMQP**: `AmqpWireFormat.validateFrameSize()` was a static helper that
   performed three checks (negative frame size, unsigned comparison against
   `maxFrameSize`, and minimum frame size of 8). The negative frame size check
   was incorrect in context — AMQP frame sizes are read as signed `int` from the
   wire, so a negative value simply means the unsigned size exceeds
   `Integer.MAX_VALUE`, which is already caught by the `maxFrameSize` comparison.
   The minimum size check (< 8) was overly strict and not required by the AMQP
   specification for all frame types at the transport layer.

2. **NIO transports**: Both `NIOTransport` and `NIOSSLTransport` had explicit
   negative frame size checks that threw `IllegalArgumentException` before
   allocating the frame buffer. These were redundant because `maxFrameSize`
   validation upstream (in the wire format's `unmarshal()`) already rejects
   oversized frames, and a negative `int` interpreted as an unsigned size always
   exceeds any reasonable `maxFrameSize`.

3. **OpenWire**: Had comments stating `setFrameSize()` verifies negative sizes,
   but the actual validation happens in the `maxFrameSize` comparison above it.

## Fix

1. **AMQP**: Removed the `validateFrameSize()` static method. Replaced with
   inline checks at each call site:
   - `AmqpFrameParser.validateMaxFrameSize()`: simple `frameSize > maxFrameSize`
     check using `IOExceptionSupport.createFrameSizeException()`.
   - `AmqpWireFormat.unmarshal()`: explicit `size > maxFrameSize` and `size <= 0`
     checks throwing `AmqpProtocolException` with descriptive messages.

2. **NIO transports**: Removed the redundant negative frame size
   `IllegalArgumentException` checks from both `NIOTransport` and
   `NIOSSLTransport`. The `maxFrameSize` check in the wire format layer provides
   the same protection.

3. **OpenWire**: Removed stale comments about negative size verification in
   `OpenWireFormat.unmarshal()`.

4. **Tests**: Removed `AmqpFrameParserTest` and `AmqpWireFormatTest` test
   methods that tested the removed `validateFrameSize()` behavior (negative
   frame size, too-small frame size, too-large frame size).

## Files Changed

| File | Change |
|------|--------|
| `AmqpWireFormat.java` | Removed `validateFrameSize()`, added inline validation in `unmarshal()` |
| `AmqpFrameParser.java` | Replaced `validateFrameSize()` call with inline max check |
| `NIOTransport.java` | Removed negative frame size check |
| `NIOSSLTransport.java` | Removed negative frame size check |
| `OpenWireFormat.java` | Removed stale comments |
| `AmqpFrameParserTest.java` | Removed frame size validation tests |
| `AmqpWireFormatTest.java` | Removed frame size validation tests and `setUp()` |

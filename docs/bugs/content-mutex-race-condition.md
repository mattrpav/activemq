# Content Mutex Race Condition in Message Types

## Affected Classes

- `activemq-client/.../command/ActiveMQTextMessage.java`
- `activemq-client/.../command/ActiveMQObjectMessage.java`
- `activemq-client/.../command/ActiveMQMapMessage.java`
- `activemq-client/.../command/Message.java` (base class)

## Problem

`ActiveMQTextMessage`, `ActiveMQObjectMessage`, and `ActiveMQMapMessage` use a
dual-representation pattern: each message holds both a serialized wire-format
byte array (`content`) and a deserialized Java object (`text`, `object`, or
`map`). Multiple operations lazily decode or encode between these two forms.

A race condition exists when one thread calls a method that clears the
deserialized form (e.g., `storeContentAndClear()`, `clearUnMarshalledState()`,
`onMessageRolledBack()`, `compress()`) while another thread calls a method that
lazily decodes the content (e.g., `getText()`, `getObject()`, `getContentMap()`).
The clearing thread can null out the deserialized object and then null out
`content` (via `setContent(null)` in the decode path of the other thread),
resulting in permanent data loss where both representations are null.

The `content` field on the base `Message` class was not declared `volatile`,
meaning visibility of writes across threads was not guaranteed even outside the
critical sections.

## Root Cause

The individual operations were not consistently synchronized. Some methods held
no lock at all (e.g., `copy()` in `ActiveMQMapMessage`), and others accessed
both `content` and the deserialized field without atomicity guarantees.

## Fix

1. Added a `protected final Object contentMutex = new Object()` field to the
   base `Message` class to serve as a dedicated lock for all content access.

2. Declared the `content` field as `volatile` on the base `Message` class to
   ensure cross-thread visibility of byte array writes.

3. Wrapped every method that reads or writes the deserialized field or the
   `content` field in a `synchronized (contentMutex)` block across all three
   message types. This covers: `storeContent()`, `storeContentAndClear()`,
   `copy()`, `clearBody()`, `clearUnMarshalledState()`, `onMessageRolledBack()`,
   `compress()`, `getText()`/`getObject()`/`getContentMap()`,
   `setText()`/`setObject()`/`put*()`, `getSize()`, `toString()`,
   `doGetBody()`, and `beforeMarshall()`.

## Not Affected

`ActiveMQBytesMessage` and `ActiveMQStreamMessage` are not vulnerable because
they enforce strict read-only/write-only mode via `dataOut`/`dataIn` lifecycle
and do not use the lazy decode-and-clear pattern.

## Files Changed

| File | Change |
|------|--------|
| `Message.java` | Added `contentMutex` lock object and `volatile` on `content` |
| `ActiveMQTextMessage.java` | Synchronized all content access on `contentMutex` |
| `ActiveMQObjectMessage.java` | Synchronized all content access on `contentMutex` |
| `ActiveMQMapMessage.java` | Synchronized all content access on `contentMutex` |

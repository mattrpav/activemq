# Jakarta Messaging 3.1 work: PR branch split

Built 2026-09-22 from `activemq-wip-jakarta-3.1` (`4068dd1da1`, TCK 903/903) in the worktree
`../activemq-pr-split`. Every branch below is based on `main` at `94715219eb` except the
delivery-delay branch, which stacks on the shared-topic branch (it reads the OpenWire v13
`Message.deliveryTime` field). Backup tags: `backup/wip-jakarta-3.1`, `backup/1715-tck`,
`backup/1715-shared-topics`, `backup/2273-pool`.

## Branches

| Branch | Issue / PR | Commits (oldest first) |
|---|---|---|
| `amq-gh-2273-pool-jmscontext` (rewritten from `-v2`) | #2273, PR open | pool JMS 3.1/2.0 support; async pass-through test made provider-agnostic |
| jbonofre `AMQ-8464` | #1716, his PR | untouched |
| jeanouii `feat/async-send-completion-listener` | his PR | untouched |
| upstream PR #1853 (pradeep85841) | JMSX provider-only properties | replaces our `f9f08f3180`; review suggestion: throw `MessageNotWriteableException` (its description says so, the patch throws `JMSException`) |
| `amq-gh-1715-shared-topics` (rewritten from `-v2`) | #1715, PR open (force-push) | bridge `instanceof` fix; KahaDB ack monotonicity; OpenWire v13; feature (SharedTopicBrokerService/Factory, SharedJMSContext, topic-mismatch rejection, `newContext`/`newChildContext` hooks); TCK runner updates |
| `amq-gh-1717-delivery-delay` (on `amq-gh-1715-shared-topics`) | #1717 | implementation incl. scheduler re-send fix; TCK `setSchedulerSupport(true)`; RA proxy delegation |
| `amq-gh-2602-defer-prefetch-until-started` | #2602 | feature (flag copied in `configureConnection()`); TCK `setDeferPrefetchUntilStarted(true)` |
| `amq-gh-2603-jakarta-exception-types` | #2603 | eager auth probe; admin clientID; session-mode validation; MapMessage char + BytesMessage getBody; TCK strictCompliance + authenticating broker; drop the inert `ts.jtx` exclusion (its test id never matched a TCK test; both real `messageFormatRuntimeExceptionTests` always ran and pass) |
| `amq-gh-2604-producer-send-rules` | #2604 | conversion table (+ `StubTransport` test helper, identical to the 1715 copy); send rules. **Open only after jeanouii's PR merges**, using the reconciled variant below |
| `amq-gh-2604-producer-send-rules-after-completionlistener` | scratch | `main` + jeanouii + #2604 with the reconciliation folded into the send-rules commit (strict-gated guard in the CompletionListener overloads, `sendToProducerDestination` reroute, three `ActiveMQJMS2ContextTest` expectations `UnsupportedOperationException` to `IllegalArgumentException`) |
| `activemq-wip-jakarta-3.1-split` | verification only | `main` + all of the above in merge order + the two order-dependent follow-ups |

## Merge order and order-dependent follow-ups

1. Any time, independent: pool #2273, jbonofre receiveBody, jeanouii CompletionListener, #1853,
   shared topics #1715, defer prefetch #2602, exception types #2603.
2. #1717 after #1715 (stacked).
3. #2604 after jeanouii's PR (use the `-after-completionlistener` commits) and after #1715 for the
   `ts.jtx` exclusion removal.

Follow-ups that depend on which PR lands second (both applied on the integration branch as
`9be17a9e57` and `c7ac402974`):

- receiveBody (#1716) + #2602: `isPullConsumer()` in `receiveBody(Class, long)` and
  `receiveBodyNoWait(Class)` in `ActiveMQMessageConsumer` (2 lines).
- #1715 + #2602 + #2604: remove the `queueReceiveTests` and three `xUnsupportedOperationException*`
  entries the #1715 TCK commit adds to `ts.jtx`.

## Decoupling changes versus the wip branch (the only non-cosmetic deltas of the integration branch)

- `ActiveMQConnectionFactory.newContext(...)` / `ActiveMQContext.newChildContext(int)` protected
  hooks; `SharedTopicConnectionFactory` and `SharedJMSContext` override those instead of
  `createContext(...)`, so #2603's `validateSessionMode` lives only in the base classes.
- `deferPrefetchUntilStarted` copied to the connection in `configureConnection()` instead of the
  overridable `createActiveMQConnection(Transport, JMSStatsImpl)`.
- `PooledJMSProducerTest.testAsyncSendPassesListenerToProvider` accepts a provider with or without
  async send support.
- `InboundMessageProducerProxy` delegates `setDeliveryDelay`/`getDeliveryDelay`.
- `ActiveMQMessage` JMSX check and its test come from #1853 rather than `f9f08f3180`.

## Verification done

- Per branch, in isolation: #1715 client tests (61), broker tests `SharedTopicRegionTest` (16),
  `SharedTopicSubscriptionTest` (18), `SharedDurableTopicSubscriptionTest` (16),
  `ManagedSharedTopicRegionTest` (4), `SharedSubscriptionKeyTest` (13), unit tests
  `SharedSubscriptionJmsTest` (23), `TopicBridgeDemandForwardingTest` (16),
  `TopicConsumerAdvisoryTest` (16), `LastAckMonotonicityTest` (1); #1717
  `DeliveryDelayNonPersistentBrokerTest` (3), `ActiveMQJMS2ContextTest` (25); #2602
  `UnstartedConnectionQueueDispatchTest` (3); #2603 `StrictComplianceAuthenticationTest` (4),
  `StrictComplianceClientIDTest` (2), `ActiveMQJMS2ContextTest` (26), `ActiveMQMessagePropertyTest` (4);
  #2604 `ActiveMQMessageProducerIdentifiedTest` (11), `ActiveMQProducerPropertyTest` (14), and on the
  reconciled variant also `ActiveMQJMS2ContextTest` (26) and `JMSConsumerTest` (103); #2273
  `PooledJMSProducerTest` (22).
- `git diff activemq-wip-jakarta-3.1-split activemq-wip-jakarta-3.1` lists only the files above plus
  cosmetic differences (javadoc placement, import order, comment wording).
- Full Jakarta Messaging 3.1 TCK on `activemq-wip-jakarta-3.1-split` (`c7ac402974`), 2026-09-22:
  **passed 903, failed 0, errors 0**. 903 is the complete standalone suite: the one `ts.jtx` entry
  inherited from `main` never matched a test id, so nothing was ever excluded; it is removed on the
  #2603 branch and on the integration branch, whose `ts.jtx` now holds no exclusions at all. Same
  result as the original `activemq-wip-jakarta-3.1` run.

## Pushing (not done here)

```bash
git push --force-with-lease origin amq-gh-1715-shared-topics
```

```bash
git push --force-with-lease origin amq-gh-2273-pool-jmscontext
```

```bash
git push origin amq-gh-1717-delivery-delay amq-gh-2602-defer-prefetch-until-started amq-gh-2603-jakarta-exception-types amq-gh-2604-producer-send-rules
```

`~/.m2` holds the integration-branch jars (functionally the wip branch). Remove the worktree with
`git worktree remove ../activemq-pr-split` once the branches are pushed.

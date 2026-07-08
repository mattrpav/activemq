/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.bugs;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.region.BaseDestination;
import org.apache.activemq.broker.region.DestinationStatistics;
import org.apache.activemq.broker.region.Queue;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.ProxyMessageStore;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.apache.activemq.test.annotations.ParallelTest;
import org.apache.activemq.util.IOHelper;
import org.apache.activemq.util.Wait;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reproduces queueSize drift candidate D2: the expiry decrement lost to a
 * swallowed store exception (docs/bugs/queuesize-drift-deep-dive.md).
 *
 * Message expiry is gated by a one-shot CAS (Message.canProcessAsExpired,
 * consumed via RegionBroker.isExpired). The CAS winner is responsible for
 * completing removal and the statistics decrement. Queue.messageExpired
 * swallows IOException from removeMessage ("Failed to remove expired
 * Message from the store") AFTER the CAS is consumed and AFTER the expired
 * counter incremented, but BEFORE dropMessage — the only decrement of the
 * queue messages counter — has run.
 *
 * With the CAS consumed, every later encounter of the message takes a
 * CAS-loser branch that discards the reference from in-memory lists WITHOUT
 * decrementing, and the expiry is never retried: the messages counter stays
 * +1 permanently while nothing is browsable, dispatchable, or inflight —
 * the production drift fingerprint.
 *
 * The test wraps the queue's MessageStore with a proxy whose remove throws
 * IOException once during the periodic expiry sweep, then asserts the
 * counter recovers to 0 once the store is healthy again (a later sweep must
 * be able to retry the expiry).
 */
@Category(ParallelTest.class)
public class ExpiredMessageStoreFailureDriftTest {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredMessageStoreFailureDriftTest.class);
    private static final String QUEUE_NAME = "TEST.EXPIRY.STORE.FAIL";

    private BrokerService broker;
    private Connection connection;
    private File dataDir;

    /** MessageStore proxy that fails message removal once while armed. */
    static class FailingRemoveMessageStore extends ProxyMessageStore {
        final AtomicBoolean armed = new AtomicBoolean(false);
        final AtomicInteger throwCount = new AtomicInteger(0);

        FailingRemoveMessageStore(MessageStore delegate) {
            super(delegate);
        }

        private void maybeThrow() throws IOException {
            if (armed.compareAndSet(true, false)) {
                throwCount.incrementAndGet();
                throw new IOException("injected: store failure removing expired message");
            }
        }

        @Override
        public void removeMessage(ConnectionContext context, MessageAck ack) throws IOException {
            maybeThrow();
            super.removeMessage(context, ack);
        }

        @Override
        public void removeAsyncMessage(ConnectionContext context, MessageAck ack) throws IOException {
            maybeThrow();
            super.removeAsyncMessage(context, ack);
        }
    }

    @Before
    public void setUp() throws Exception {
        File baseDir = new File(IOHelper.getDefaultDataDirectory());
        Files.createDirectories(baseDir.toPath());
        dataDir = Files.createTempDirectory(baseDir.toPath(), "ExpiryStoreFail-").toFile();
        dataDir.deleteOnExit();

        broker = new BrokerService();
        broker.setDataDirectoryFile(dataDir);
        broker.setUseJmx(false);
        broker.setDeleteAllMessagesOnStartup(true);
        broker.getSystemUsage().getMemoryUsage().setLimit(64 * 1024 * 1024);

        KahaDBPersistenceAdapter pa = new KahaDBPersistenceAdapter();
        pa.setDirectory(new File(dataDir, "kahadb"));
        broker.setPersistenceAdapter(pa);

        // Production profile plus a fast periodic expiry sweep
        PolicyMap policyMap = new PolicyMap();
        PolicyEntry entry = new PolicyEntry();
        entry.setQueue(">");
        entry.setUseCache(false);
        entry.setMaxPageSize(60);
        entry.setExpireMessagesPeriod(500);
        policyMap.setDefaultEntry(entry);
        broker.setDestinationPolicy(policyMap);

        broker.addConnector("tcp://localhost:0");
        broker.start();
        broker.waitUntilStarted();

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                broker.getTransportConnectors().get(0).getConnectUri());
        connection = factory.createConnection();
        connection.start();
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (broker != null) {
            broker.deleteAllMessages();
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    @Test(timeout = 60_000)
    public void testExpiryRetriedAfterStoreFailure() throws Exception {
        ActiveMQQueue dest = new ActiveMQQueue(QUEUE_NAME);
        Queue queue = (Queue) broker.getDestination(dest);
        DestinationStatistics stats = queue.getDestinationStatistics();

        // Wrap the queue's store so the expiry removal fails exactly once
        FailingRemoveMessageStore failingStore = swapStore(queue);

        // One persistent message with a short TTL; no consumer — the
        // periodic sweep owns the expiry.
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer producer = session.createProducer(dest);
        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
        producer.setTimeToLive(1000);
        producer.send(session.createTextMessage("will-expire"));
        producer.close();
        session.close();

        assertTrue("message should be counted",
                Wait.waitFor(() -> stats.getMessages().getCount() == 1, 5000, 10));

        failingStore.armed.set(true);

        // The sweep fires, consumes the expiry CAS, and hits the injected
        // store failure ("Failed to remove expired Message from the store").
        assertTrue("injected store failure should fire during the expiry sweep",
                Wait.waitFor(() -> failingStore.throwCount.get() >= 1, 10_000, 50));
        assertTrue("message should have been counted as expired",
                Wait.waitFor(() -> stats.getExpired().getCount() >= 1, 5000, 50));

        // Store is healthy again from here on. A correct broker retries the
        // expiry on a later sweep: message removed from the store and the
        // messages counter decremented.
        boolean settled = Wait.waitFor(() -> stats.getMessages().getCount() == 0, 10_000, 100);

        long storeCount = queue.getMessageStore().getMessageCount();
        LOG.info("Final stats: messages={}, enqueues={}, dequeues={}, expired={}, inflight={}, storeCount={}",
                stats.getMessages().getCount(), stats.getEnqueues().getCount(),
                stats.getDequeues().getCount(), stats.getExpired().getCount(),
                stats.getInflight().getCount(), storeCount);

        // Nothing must be browsable/consumable either way (the message is expired)
        Session verifySession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer verifyConsumer = verifySession.createConsumer(dest);
        assertNull("expired message must not be consumable", verifyConsumer.receive(1000));
        verifyConsumer.close();
        verifySession.close();

        assertTrue("QUEUE SIZE DRIFT (D2): expiry store removal failed once and was never " +
                "retried — the expiry CAS was already consumed, so the messages counter is " +
                "stuck at " + stats.getMessages().getCount() + " (store row count " + storeCount +
                ") with nothing consumable, browsable or inflight. Queue.messageExpired must " +
                "reset the message's processAsExpired state when the store removal fails so a " +
                "later sweep can retry", settled);

        assertTrue("store should be empty after the retried expiry",
                Wait.waitFor(() -> {
                    try {
                        return queue.getMessageStore().getMessageCount() == 0;
                    } catch (Exception e) {
                        return false;
                    }
                }, 5000, 100));
    }

    private FailingRemoveMessageStore swapStore(Queue queue) throws Exception {
        Field storeField = BaseDestination.class.getDeclaredField("store");
        storeField.setAccessible(true);
        MessageStore original = (MessageStore) storeField.get(queue);
        FailingRemoveMessageStore failing = new FailingRemoveMessageStore(original);
        storeField.set(queue, failing);
        return failing;
    }
}

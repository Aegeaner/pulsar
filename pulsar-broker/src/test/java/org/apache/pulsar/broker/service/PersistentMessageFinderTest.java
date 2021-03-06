/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service;

import static org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest.retryStrategically;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.bookkeeper.mledger.AsyncCallbacks;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.ManagedCursorImpl;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.bookkeeper.mledger.proto.MLDataFormats.ManagedLedgerInfo.LedgerInfo;
import org.apache.bookkeeper.test.MockedBookKeeperTestCase;
import org.apache.pulsar.broker.service.persistent.PersistentMessageExpiryMonitor;
import org.apache.pulsar.broker.service.persistent.PersistentMessageFinder;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.api.proto.BrokerEntryMetadata;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.intercept.BrokerEntryMetadataInterceptor;
import org.apache.pulsar.common.intercept.BrokerEntryMetadataUtils;
import org.apache.pulsar.common.protocol.ByteBufPair;
import org.apache.pulsar.common.protocol.Commands;
import org.testng.annotations.Test;
import org.testng.collections.Sets;

/**
 */
public class PersistentMessageFinderTest extends MockedBookKeeperTestCase {

    public static byte[] createMessageWrittenToLedger(String msg) throws Exception {
        MessageMetadata messageMetadata = new MessageMetadata()
                    .setPublishTime(System.currentTimeMillis())
                    .setProducerName("createMessageWrittenToLedger")
                    .setSequenceId(1);
        ByteBuf data = UnpooledByteBufAllocator.DEFAULT.heapBuffer().writeBytes(msg.getBytes());

        int msgMetadataSize = messageMetadata.getSerializedSize();
        int payloadSize = data.readableBytes();
        int totalSize = 4 + msgMetadataSize + payloadSize;

        ByteBuf headers = PulsarByteBufAllocator.DEFAULT.heapBuffer(totalSize, totalSize);
        headers.writeInt(msgMetadataSize);
        messageMetadata.writeTo(headers);
        ByteBuf headersAndPayload = ByteBufPair.coalesce(ByteBufPair.get(headers, data));
        byte[] byteMessage = headersAndPayload.nioBuffer().array();
        headersAndPayload.release();
        return byteMessage;
    }

    public static ByteBuf createMessageByteBufWrittenToLedger(String msg) throws Exception {
        MessageMetadata messageMetadata = new MessageMetadata()
                .setPublishTime(System.currentTimeMillis())
                .setProducerName("createMessageWrittenToLedger")
                .setSequenceId(1);
        ByteBuf data = UnpooledByteBufAllocator.DEFAULT.heapBuffer().writeBytes(msg.getBytes());

        int msgMetadataSize = messageMetadata.getSerializedSize();
        int payloadSize = data.readableBytes();
        int totalSize = 4 + msgMetadataSize + payloadSize;

        ByteBuf headers = PulsarByteBufAllocator.DEFAULT.heapBuffer(totalSize, totalSize);
        headers.writeInt(msgMetadataSize);
        messageMetadata.writeTo(headers);
        return ByteBufPair.coalesce(ByteBufPair.get(headers, data));
    }

    public static byte[] appendBrokerTimestamp(ByteBuf headerAndPayloads) throws Exception {
        ByteBuf msgWithEntryMeta =
                Commands.addBrokerEntryMetadata(headerAndPayloads, getBrokerEntryMetadataInterceptors(), 1);
        byte[] byteMessage = msgWithEntryMeta.nioBuffer().array();
        msgWithEntryMeta.release();
        return byteMessage;
    }

    class Result {
        ManagedLedgerException exception = null;
        Position position = null;

        void reset() {
            this.exception = null;
            this.position = null;
        }
    }

    CompletableFuture<Void> findMessage(final Result result, final ManagedCursor c1, final long timestamp) {
        PersistentMessageFinder messageFinder = new PersistentMessageFinder("topicname", c1);

        final CompletableFuture<Void> future = new CompletableFuture<>();
        messageFinder.findMessages(timestamp, new AsyncCallbacks.FindEntryCallback() {
            @Override
            public void findEntryComplete(Position position, Object ctx) {
                result.position = position;
                future.complete(null);
            }

            @Override
            public void findEntryFailed(ManagedLedgerException exception, Optional<Position> failedReadPosition,
                    Object ctx) {
                result.exception = exception;
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    @Test
    void testPersistentMessageFinder() throws Exception {
        final String ledgerAndCursorName = "testPersistentMessageFinder";
        int entriesPerLedger = 2;
        long beginTimestamp = System.currentTimeMillis();

        ManagedLedgerConfig config = new ManagedLedgerConfig();
        config.setRetentionSizeInMB(10);
        config.setMaxEntriesPerLedger(entriesPerLedger);
        config.setRetentionTime(1, TimeUnit.HOURS);
        ManagedLedger ledger = factory.open(ledgerAndCursorName, config);
        ManagedCursorImpl c1 = (ManagedCursorImpl) ledger.openCursor(ledgerAndCursorName);

        ledger.addEntry(createMessageWrittenToLedger("retained1"));
        // space apart message publish times
        Thread.sleep(100);
        ledger.addEntry(createMessageWrittenToLedger("retained2"));
        Thread.sleep(100);
        Position newPosition = ledger.addEntry(createMessageWrittenToLedger("retained3"));
        Thread.sleep(100);
        long timestamp = System.currentTimeMillis();
        Thread.sleep(10);

        ledger.addEntry(createMessageWrittenToLedger("afterresetposition"));

        Position lastPosition = ledger.addEntry(createMessageWrittenToLedger("not-read"));
        List<Entry> entries = c1.readEntries(3);
        c1.markDelete(entries.get(2).getPosition());
        c1.close();
        ledger.close();
        entries.forEach(e -> e.release());
        // give timed ledger trimming a chance to run
        Thread.sleep(1000);

        ledger = factory.open(ledgerAndCursorName, config);
        c1 = (ManagedCursorImpl) ledger.openCursor(ledgerAndCursorName);
        long endTimestamp = System.currentTimeMillis();

        Result result = new Result();

        CompletableFuture<Void> future = findMessage(result, c1, timestamp);
        future.get();
        assertNull(result.exception);
        assertNotNull(result.position);
        assertEquals(result.position, newPosition);

        result.reset();
        future = findMessage(result, c1, beginTimestamp);
        future.get();
        assertNull(result.exception);
        assertNull(result.position);

        result.reset();
        future = findMessage(result, c1, endTimestamp);
        future.get();
        assertNull(result.exception);
        assertNotEquals(result.position, null);
        assertEquals(result.position, lastPosition);

        PersistentMessageFinder messageFinder = new PersistentMessageFinder("topicname", c1);
        final AtomicBoolean ex = new AtomicBoolean(false);
        messageFinder.findEntryFailed(new ManagedLedgerException("failed"), Optional.empty(),
                new AsyncCallbacks.FindEntryCallback() {
                    @Override
                    public void findEntryComplete(Position position, Object ctx) {
                    }

                    @Override
                    public void findEntryFailed(ManagedLedgerException exception, Optional<Position> failedReadPosition,
                            Object ctx) {
                        ex.set(true);
                    }
                });
        assertTrue(ex.get());

        PersistentMessageExpiryMonitor monitor = new PersistentMessageExpiryMonitor("topicname", c1.getName(), c1, null);
        monitor.findEntryFailed(new ManagedLedgerException.ConcurrentFindCursorPositionException("failed"),
                Optional.empty(), null);
        Field field = monitor.getClass().getDeclaredField("expirationCheckInProgress");
        field.setAccessible(true);
        assertEquals(0, field.get(monitor));

        result.reset();
        c1.close();
        ledger.close();
        factory.shutdown();
    }

    @Test
    void testPersistentMessageFinderWithBrokerTimestampForMessage() throws Exception {

        final String ledgerAndCursorName = "publishTime";
        final String ledgerAndCursorNameForBrokerTimestampMessage = "brokerTimestamp";

        ManagedLedgerConfig config = new ManagedLedgerConfig();
        ManagedLedger ledger = factory.open(ledgerAndCursorName, config);
        ManagedCursorImpl cursor = (ManagedCursorImpl) ledger.openCursor(ledgerAndCursorName);
        ledger.addEntry(createMessageWrittenToLedger("message1"));
        // space apart message publish times
        Thread.sleep(100);
        ledger.addEntry(createMessageWrittenToLedger("message2"));
        Thread.sleep(100);
        Position position = ledger.addEntry(createMessageWrittenToLedger("message3"));
        Thread.sleep(100);
        long timestamp = System.currentTimeMillis();

        Result result = new Result();

        CompletableFuture<Void> future = findMessage(result, cursor, timestamp);
        future.get();
        assertNull(result.exception);
        assertNotNull(result.position);
        assertEquals(result.position, position);

        List<Entry> entryList = cursor.readEntries(3);
        for (Entry entry : entryList) {
            // entry has no raw metadata if BrokerTimestampForMessage is disable
            assertNull(Commands.parseBrokerEntryMetadataIfExist(entry.getDataBuffer()));
        }

        result.reset();
        cursor.close();
        ledger.close();

        ManagedLedgerConfig configNew = new ManagedLedgerConfig();
        ManagedLedger ledgerNew = factory.open(ledgerAndCursorNameForBrokerTimestampMessage, configNew);
        ManagedCursorImpl cursorNew = (ManagedCursorImpl) ledgerNew.openCursor(ledgerAndCursorNameForBrokerTimestampMessage);
        // build message which has publish time first
        ByteBuf msg1 = createMessageByteBufWrittenToLedger("message1");
        ByteBuf msg2 = createMessageByteBufWrittenToLedger("message2");
        ByteBuf msg3 = createMessageByteBufWrittenToLedger("message3");
        Thread.sleep(10);
        long timeAfterPublishTime = System.currentTimeMillis();
        Thread.sleep(10);

        // append broker timestamp as entry metadata

        ledgerNew.addEntry(appendBrokerTimestamp(msg1));
        // space apart message publish times
        Thread.sleep(100);
        ledgerNew.addEntry(appendBrokerTimestamp(msg2));
        Thread.sleep(100);
        Position newPosition = ledgerNew.addEntry(appendBrokerTimestamp(msg3));
        Thread.sleep(100);
        long timeAfterBrokerTimestamp = System.currentTimeMillis();


        CompletableFuture<Void> publishTimeFuture = findMessage(result, cursorNew, timeAfterPublishTime);
        publishTimeFuture.get();
        assertNull(result.exception);
        // position should be null, since broker timestamp for message is bigger than timeAfterPublishTime
        assertNull(result.position);

        result.reset();

        CompletableFuture<Void> brokerTimestampFuture = findMessage(result, cursorNew, timeAfterBrokerTimestamp);
        brokerTimestampFuture.get();
        assertNull(result.exception);
        assertNotNull(result.position);
        assertEquals(result.position, newPosition);

        List<Entry> entryListNew = cursorNew.readEntries(4);
        for (Entry entry : entryListNew) {
            // entry should have raw metadata since BrokerTimestampForMessage is enable
            BrokerEntryMetadata brokerMetadata = Commands.parseBrokerEntryMetadataIfExist(entry.getDataBuffer());
            assertNotNull(brokerMetadata);
            assertTrue(brokerMetadata.getBrokerTimestamp() > timeAfterPublishTime);
        }

        result.reset();
        cursorNew.close();
        ledgerNew.close();
        factory.shutdown();
    }

    public static Set<BrokerEntryMetadataInterceptor> getBrokerEntryMetadataInterceptors() {
        Set<String> interceptorNames = new HashSet<>();
        interceptorNames.add("org.apache.pulsar.common.intercept.AppendBrokerTimestampMetadataInterceptor");
        interceptorNames.add("org.apache.pulsar.common.intercept.AppendIndexMetadataInterceptor");
        return BrokerEntryMetadataUtils.loadBrokerEntryMetadataInterceptors(interceptorNames,
                Thread.currentThread().getContextClassLoader());
    }
    /**
     * It tests that message expiry doesn't get stuck if it can't read deleted ledger's entry.
     *
     * @throws Exception
     */
    @Test
    void testMessageExpiryWithNonRecoverableException() throws Exception {

        final String ledgerAndCursorName = "testPersistentMessageExpiryWithNonRecoverableLedgers";
        final int entriesPerLedger = 2;
        final int totalEntries = 10;
        final int ttlSeconds = 1;

        ManagedLedgerConfig config = new ManagedLedgerConfig();
        config.setRetentionSizeInMB(10);
        config.setMaxEntriesPerLedger(entriesPerLedger);
        config.setRetentionTime(1, TimeUnit.HOURS);
        config.setAutoSkipNonRecoverableData(true);
        ManagedLedgerImpl ledger = (ManagedLedgerImpl) factory.open(ledgerAndCursorName, config);
        ManagedCursorImpl c1 = (ManagedCursorImpl) ledger.openCursor(ledgerAndCursorName);

        for (int i = 0; i < totalEntries; i++) {
            ledger.addEntry(createMessageWrittenToLedger("msg" + i));
        }

        List<LedgerInfo> ledgers = ledger.getLedgersInfoAsList();
        LedgerInfo lastLedgerInfo = ledgers.get(ledgers.size() - 1);

        assertEquals(ledgers.size(), totalEntries / entriesPerLedger);

        // this will make sure that all entries should be deleted
        Thread.sleep(TimeUnit.SECONDS.toMillis(ttlSeconds));

        bkc.deleteLedger(ledgers.get(0).getLedgerId());
        bkc.deleteLedger(ledgers.get(1).getLedgerId());
        bkc.deleteLedger(ledgers.get(2).getLedgerId());

        PersistentMessageExpiryMonitor monitor = new PersistentMessageExpiryMonitor("topicname", c1.getName(), c1, null);
        Position previousMarkDelete = null;
        for (int i = 0; i < totalEntries; i++) {
            monitor.expireMessages(1);
            Position previousPos = previousMarkDelete;
            retryStrategically(
                    (test) -> c1.getMarkDeletedPosition() != null && !c1.getMarkDeletedPosition().equals(previousPos),
                    5, 100);
            previousMarkDelete = c1.getMarkDeletedPosition();
        }

        PositionImpl markDeletePosition = (PositionImpl) c1.getMarkDeletedPosition();
        assertEquals(lastLedgerInfo.getLedgerId(), markDeletePosition.getLedgerId());
        assertEquals(lastLedgerInfo.getEntries() - 1, markDeletePosition.getEntryId());

        c1.close();
        ledger.close();
        factory.shutdown();

    }
}

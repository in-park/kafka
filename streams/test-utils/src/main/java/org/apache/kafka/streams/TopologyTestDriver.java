/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.CumulativeSum;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.WindowedCount;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.TopologyException;
import org.apache.kafka.streams.internals.KeyValueStoreFacade;
import org.apache.kafka.streams.internals.QuietStreamsConfig;
import org.apache.kafka.streams.internals.WindowStoreFacade;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.Punctuator;
import org.apache.kafka.streams.processor.StateRestoreListener;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.internals.GlobalProcessorContextImpl;
import org.apache.kafka.streams.processor.internals.GlobalStateManager;
import org.apache.kafka.streams.processor.internals.GlobalStateManagerImpl;
import org.apache.kafka.streams.processor.internals.GlobalStateUpdateTask;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.processor.internals.InternalTopologyBuilder;
import org.apache.kafka.streams.processor.internals.ProcessorContextImpl;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;
import org.apache.kafka.streams.processor.internals.StateDirectory;
import org.apache.kafka.streams.processor.internals.StoreChangelogReader;
import org.apache.kafka.streams.processor.internals.StreamTask;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.SessionStore;
import org.apache.kafka.streams.state.TimestampedKeyValueStore;
import org.apache.kafka.streams.state.TimestampedWindowStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.internals.ThreadCache;
import org.apache.kafka.streams.state.internals.metrics.RocksDBMetricsRecordingTrigger;
import org.apache.kafka.streams.test.TestRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * This class makes it easier to write tests to verify the behavior of topologies created with {@link Topology} or
 * {@link StreamsBuilder}.
 * You can test simple topologies that have a single processor, or very complex topologies that have multiple sources,
 * processors, sinks, or sub-topologies.
 * Best of all, the class works without a real Kafka broker, so the tests execute very quickly with very little overhead.
 * <p>
 * Using the {@code TopologyTestDriver} in tests is easy: simply instantiate the driver and provide a {@link Topology}
 * (cf. {@link StreamsBuilder#build()}) and {@link Properties configs}, {@link #createInputTopic(String, Serializer, Serializer) create}
 * and use a {@link TestInputTopic} to supply an input records to the topology,
 * and then {@link #createOutputTopic(String, Deserializer, Deserializer) create} and use a {@link TestOutputTopic} to read and
 * verify any output records by the topology.
 * <p>
 * Although the driver doesn't use a real Kafka broker, it does simulate Kafka {@link Consumer consumers} and
 * {@link Producer producers} that read and write raw {@code byte[]} messages.
 * You can let {@link TestInputTopic} and {@link TestOutputTopic} to handle conversion
 * form regular Java objects to raw bytes.
 *
 * <h2>Driver setup</h2>
 * In order to create a {@code TopologyTestDriver} instance, you need a {@link Topology} and a {@link Properties config}.
 * The configuration needs to be representative of what you'd supply to the real topology, so that means including
 * several key properties (cf. {@link StreamsConfig}).
 * For example, the following code fragment creates a configuration that specifies a local Kafka broker list (which is
 * needed but not used), a timestamp extractor, and default serializers and deserializers for string keys and values:
 *
 * <pre>{@code
 * Properties props = new Properties();
 * props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091");
 * props.setProperty(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, CustomTimestampExtractor.class.getName());
 * props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
 * props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
 * Topology topology = ...
 * TopologyTestDriver driver = new TopologyTestDriver(topology, props);
 * }</pre>
 *
 * <h2>Processing messages</h2>
 * <p>
 * Your test can supply new input records on any of the topics that the topology's sources consume.
 * This test driver simulates single-partitioned input topics.
 * Here's an example of an input message on the topic named {@code input-topic}:
 *
 * <pre>{@code
 * TestInputTopic<String, String> inputTopic = driver.createInputTopic("input-topic", stringSerdeSerializer, stringSerializer);
 * inputTopic.pipeInput("key1", "value1");
 * }</pre>
 *
 * When {@link TestInputTopic#pipeInput(Object, Object)} is called, the driver passes the input message through to the appropriate source that
 * consumes the named topic, and will invoke the processor(s) downstream of the source.
 * If your topology's processors forward messages to sinks, your test can then consume these output messages to verify
 * they match the expected outcome.
 * For example, if our topology should have generated 2 messages on {@code output-topic-1} and 1 message on
 * {@code output-topic-2}, then our test can obtain these messages using the
 * {@link TestOutputTopic#readKeyValue()}  method:
 *
 * <pre>{@code
 * TestOutputTopic<String, String> outputTopic1 = driver.createOutputTopic("output-topic-1", stringDeserializer, stringDeserializer);
 * TestOutputTopic<String, String> outputTopic2 = driver.createOutputTopic("output-topic-2", stringDeserializer, stringDeserializer);
 *
 * KeyValue<String, String> record1 = outputTopic1.readKeyValue();
 * KeyValue<String, String> record2 = outputTopic2.readKeyValue();
 * KeyValue<String, String> record3 = outputTopic1.readKeyValue();
 * }</pre>
 *
 * Again, our example topology generates messages with string keys and values, so we supply our string deserializer
 * instance for use on both the keys and values. Your test logic can then verify whether these output records are
 * correct.
 * <p>
 * Note, that calling {@code pipeInput()} will also trigger {@link PunctuationType#STREAM_TIME event-time} base
 * {@link ProcessorContext#schedule(Duration, PunctuationType, Punctuator) punctuation} callbacks.
 * However, you won't trigger {@link PunctuationType#WALL_CLOCK_TIME wall-clock} type punctuations that you must
 * trigger manually via {@link #advanceWallClockTime(long)}.
 * <p>
 * Finally, when completed, make sure your tests {@link #close()} the driver to release all resources and
 * {@link org.apache.kafka.streams.processor.Processor processors}.
 *
 * <h2>Processor state</h2>
 * <p>
 * Some processors use Kafka {@link StateStore state storage}, so this driver class provides the generic
 * {@link #getStateStore(String)} as well as store-type specific methods so that your tests can check the underlying
 * state store(s) used by your topology's processors.
 * In our previous example, after we supplied a single input message and checked the three output messages, our test
 * could also check the key value store to verify the processor correctly added, removed, or updated internal state.
 * Or, our test might have pre-populated some state <em>before</em> submitting the input message, and verified afterward
 * that the processor(s) correctly updated the state.
 *
 * @see TestInputTopic
 * @see TestOutputTopic
 */
public class TopologyTestDriver implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(TopologyTestDriver.class);

    private final Time mockWallClockTime;
    private final InternalTopologyBuilder internalTopologyBuilder;

    private final static int PARTITION_ID = 0;
    private final static TaskId TASK_ID = new TaskId(0, PARTITION_ID);
    final StreamTask task;
    private final GlobalStateUpdateTask globalStateTask;
    private final GlobalStateManager globalStateManager;

    private final StateDirectory stateDirectory;
    private final Metrics metrics;
    final ProcessorTopology processorTopology;
    final ProcessorTopology globalTopology;

    private final MockProducer<byte[], byte[]> producer;

    private final Set<String> internalTopics = new HashSet<>();
    private final Map<String, TopicPartition> partitionsByTopic = new HashMap<>();
    private final Map<String, TopicPartition> globalPartitionsByTopic = new HashMap<>();
    private final Map<TopicPartition, AtomicLong> offsetsByTopicPartition = new HashMap<>();

    private final Map<String, Queue<ProducerRecord<byte[], byte[]>>> outputRecordsByTopic = new HashMap<>();
    private final boolean eosEnabled;

    /**
     * Create a new test diver instance.
     * Initialized the internally mocked wall-clock time with {@link System#currentTimeMillis() current system time}.
     *
     * @param topology the topology to be tested
     * @param config   the configuration for the topology
     */
    @SuppressWarnings("WeakerAccess")
    public TopologyTestDriver(final Topology topology,
                              final Properties config) {
        this(topology, config, (Instant) null);
    }

    /**
     * Create a new test diver instance.
     *
     * @deprecated Since 2.4 use {@link #TopologyTestDriver(Topology, Properties, Instant)}
     *
     * @param topology               the topology to be tested
     * @param config                 the configuration for the topology
     * @param initialWallClockTimeMs the initial value of internally mocked wall-clock time
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public TopologyTestDriver(final Topology topology,
                              final Properties config,
                              final long initialWallClockTimeMs) {
        this(topology.internalTopologyBuilder, config, initialWallClockTimeMs);
    }

    /**
     * Create a new test diver instance.
     *
     * @param topology               the topology to be tested
     * @param config                 the configuration for the topology
     * @param initialWallClockTime   the initial value of internally mocked wall-clock time
     */
    @SuppressWarnings("WeakerAccess")
    public TopologyTestDriver(final Topology topology,
                              final Properties config,
                              final Instant initialWallClockTime) {
        this(topology.internalTopologyBuilder, config, initialWallClockTime == null ? System.currentTimeMillis() : initialWallClockTime.toEpochMilli());
    }


    /**
     * Create a new test diver instance.
     *
     * @param builder builder for the topology to be tested
     * @param config the configuration for the topology
     * @param initialWallClockTimeMs the initial value of internally mocked wall-clock time
     */
    private TopologyTestDriver(final InternalTopologyBuilder builder,
                               final Properties config,
                               final long initialWallClockTimeMs) {
        final StreamsConfig streamsConfig = new QuietStreamsConfig(config);
        mockWallClockTime = new MockTime(initialWallClockTimeMs);

        internalTopologyBuilder = builder;
        internalTopologyBuilder.rewriteTopology(streamsConfig);

        processorTopology = internalTopologyBuilder.build(null);
        globalTopology = internalTopologyBuilder.buildGlobalStateTopology();
        final boolean createStateDirectory = processorTopology.hasPersistentLocalStore() ||
            (globalTopology != null && globalTopology.hasPersistentGlobalStore());

        final Serializer<byte[]> bytesSerializer = new ByteArraySerializer();
        producer = new MockProducer<byte[], byte[]>(true, bytesSerializer, bytesSerializer) {
            @Override
            public List<PartitionInfo> partitionsFor(final String topic) {
                return Collections.singletonList(new PartitionInfo(topic, PARTITION_ID, null, null, null));
            }
        };

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        stateDirectory = new StateDirectory(streamsConfig, mockWallClockTime, createStateDirectory);

        final MetricConfig metricConfig = new MetricConfig()
            .samples(streamsConfig.getInt(StreamsConfig.METRICS_NUM_SAMPLES_CONFIG))
            .recordLevel(Sensor.RecordingLevel.forName(streamsConfig.getString(StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG)))
            .timeWindow(streamsConfig.getLong(StreamsConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG), TimeUnit.MILLISECONDS);

        metrics = new Metrics(metricConfig, mockWallClockTime);

        final String threadId = Thread.currentThread().getName();
        final StreamsMetricsImpl streamsMetrics = new StreamsMetricsImpl(
            metrics,
            "test-client",
            StreamsConfig.METRICS_LATEST
        );
        streamsMetrics.setRocksDBMetricsRecordingTrigger(new RocksDBMetricsRecordingTrigger());
        final Sensor skippedRecordsSensor =
            streamsMetrics.threadLevelSensor(threadId, "skipped-records", Sensor.RecordingLevel.INFO);
        final String threadLevelGroup = "stream-metrics";
        skippedRecordsSensor.add(new MetricName("skipped-records-rate",
                                                threadLevelGroup,
                                                "The average per-second number of skipped records",
                                                streamsMetrics.tagMap(threadId)),
                                 new Rate(TimeUnit.SECONDS, new WindowedCount()));
        skippedRecordsSensor.add(new MetricName("skipped-records-total",
                                                threadLevelGroup,
                                                "The total number of skipped records",
                                                streamsMetrics.tagMap(threadId)),
                                 new CumulativeSum());
        final ThreadCache cache = new ThreadCache(
            new LogContext("topology-test-driver "),
            Math.max(0, streamsConfig.getLong(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG)),
            streamsMetrics);
        final StateRestoreListener stateRestoreListener = new StateRestoreListener() {
            @Override
            public void onRestoreStart(final TopicPartition topicPartition, final String storeName, final long startingOffset, final long endingOffset) {}

            @Override
            public void onBatchRestored(final TopicPartition topicPartition, final String storeName, final long batchEndOffset, final long numRestored) {}

            @Override
            public void onRestoreEnd(final TopicPartition topicPartition, final String storeName, final long totalRestored) {}
        };

        for (final InternalTopologyBuilder.TopicsInfo topicsInfo : internalTopologyBuilder.topicGroups().values()) {
            internalTopics.addAll(topicsInfo.repartitionSourceTopics.keySet());
        }

        for (final String topic : processorTopology.sourceTopics()) {
            final TopicPartition tp = new TopicPartition(topic, PARTITION_ID);
            partitionsByTopic.put(topic, tp);
            offsetsByTopicPartition.put(tp, new AtomicLong());
        }
        consumer.assign(partitionsByTopic.values());

        if (globalTopology != null) {
            for (final String topicName : globalTopology.sourceTopics()) {
                final TopicPartition partition = new TopicPartition(topicName, 0);
                globalPartitionsByTopic.put(topicName, partition);
                offsetsByTopicPartition.put(partition, new AtomicLong());
                consumer.updatePartitions(topicName, Collections.singletonList(
                    new PartitionInfo(topicName, 0, null, null, null)));
                consumer.updateBeginningOffsets(Collections.singletonMap(partition, 0L));
                consumer.updateEndOffsets(Collections.singletonMap(partition, 0L));
            }

            globalStateManager = new GlobalStateManagerImpl(
                new LogContext("mock "),
                globalTopology,
                consumer,
                stateDirectory,
                stateRestoreListener,
                streamsConfig);

            final GlobalProcessorContextImpl globalProcessorContext =
                new GlobalProcessorContextImpl(streamsConfig, globalStateManager, streamsMetrics, cache);
            globalStateManager.setGlobalProcessorContext(globalProcessorContext);

            globalStateTask = new GlobalStateUpdateTask(
                globalTopology,
                globalProcessorContext,
                globalStateManager,
                new LogAndContinueExceptionHandler(),
                new LogContext()
            );
            globalStateTask.initialize();
            globalProcessorContext.setRecordContext(new ProcessorRecordContext(0L, -1L, -1, ProcessorContextImpl.NONEXIST_TOPIC, new RecordHeaders()));
        } else {
            globalStateManager = null;
            globalStateTask = null;
        }

        if (!partitionsByTopic.isEmpty()) {
            task = new StreamTask(
                TASK_ID,
                partitionsByTopic.values(),
                processorTopology,
                consumer,
                new StoreChangelogReader(
                    createRestoreConsumer(processorTopology.storeToChangelogTopic()),
                    Duration.ZERO,
                    stateRestoreListener,
                    new LogContext("topology-test-driver ")),
                streamsConfig,
                streamsMetrics,
                stateDirectory,
                cache,
                mockWallClockTime,
                () -> producer);
            task.initializeStateStores();
            task.initializeTopology();
            ((InternalProcessorContext) task.context()).setRecordContext(new ProcessorRecordContext(
                0L,
                -1L,
                -1,
                ProcessorContextImpl.NONEXIST_TOPIC,
                new RecordHeaders()));
        } else {
            task = null;
        }
        eosEnabled = streamsConfig.getString(StreamsConfig.PROCESSING_GUARANTEE_CONFIG).equals(StreamsConfig.EXACTLY_ONCE);
    }

    /**
     * Get read-only handle on global metrics registry.
     *
     * @return Map of all metrics.
     */
    @SuppressWarnings("WeakerAccess")
    public Map<MetricName, ? extends Metric> metrics() {
        return Collections.unmodifiableMap(metrics.metrics());
    }

    /**
     * Send an input message with the given key, value, and timestamp on the specified topic to the topology and then
     * commit the messages.
     *
     * @deprecated Since 2.4 use methods of {@link TestInputTopic} instead
     *
     * @param consumerRecord the record to be processed
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public void pipeInput(final ConsumerRecord<byte[], byte[]> consumerRecord) {
        pipeRecord(consumerRecord.topic(), consumerRecord.timestamp(), consumerRecord.key(), consumerRecord.value(), consumerRecord.headers());
    }

    private void pipeRecord(final ProducerRecord<byte[], byte[]> record) {
        pipeRecord(record.topic(), record.timestamp(), record.key(), record.value(), record.headers());
    }

    private void pipeRecord(final String topic, final Long timestamp, final byte[] key, final byte[] value, final Headers headers) {
        final String topicName = topic;

        if (!internalTopologyBuilder.getSourceTopicNames().isEmpty()) {
            validateSourceTopicNameRegexPattern(topic);
        }
        final TopicPartition topicPartition = getTopicPartition(topicName);
        if (topicPartition != null) {
            final long offset = offsetsByTopicPartition.get(topicPartition).incrementAndGet() - 1;
            task.addRecords(topicPartition, Collections.singleton(new ConsumerRecord<>(
                    topicName,
                    topicPartition.partition(),
                    offset,
                    timestamp,
                    TimestampType.CREATE_TIME,
                    (long) ConsumerRecord.NULL_CHECKSUM,
                    key == null ? ConsumerRecord.NULL_SIZE : key.length,
                    value == null ? ConsumerRecord.NULL_SIZE : value.length,
                    key,
                    value,
                    headers)));

            // Process the record ...
            task.process();
            task.maybePunctuateStreamTime();
            task.commit();
            captureOutputRecords();
        } else {
            final TopicPartition globalTopicPartition = globalPartitionsByTopic.get(topicName);
            if (globalTopicPartition == null) {
                throw new IllegalArgumentException("Unknown topic: " + topicName);
            }
            final long offset = offsetsByTopicPartition.get(globalTopicPartition).incrementAndGet() - 1;
            globalStateTask.update(new ConsumerRecord<>(
                    globalTopicPartition.topic(),
                    globalTopicPartition.partition(),
                    offset,
                    timestamp,
                    TimestampType.CREATE_TIME,
                    (long) ConsumerRecord.NULL_CHECKSUM,
                    key == null ? ConsumerRecord.NULL_SIZE : key.length,
                    value == null ? ConsumerRecord.NULL_SIZE : value.length,
                    key,
                    value,
                    headers));
            globalStateTask.flushState();
        }
    }


    private void validateSourceTopicNameRegexPattern(final String inputRecordTopic) {
        for (final String sourceTopicName : internalTopologyBuilder.getSourceTopicNames()) {
            if (!sourceTopicName.equals(inputRecordTopic) && Pattern.compile(sourceTopicName).matcher(inputRecordTopic).matches()) {
                throw new TopologyException("Topology add source of type String for topic: " + sourceTopicName +
                        " cannot contain regex pattern for input record topic: " + inputRecordTopic +
                        " and hence cannot process the message.");
            }
        }
    }

    private TopicPartition getTopicPartition(final String topicName) {
        final TopicPartition topicPartition = partitionsByTopic.get(topicName);
        if (topicPartition == null) {
            for (final Map.Entry<String, TopicPartition> entry : partitionsByTopic.entrySet()) {
                if (Pattern.compile(entry.getKey()).matcher(topicName).matches()) {
                    return entry.getValue();
                }
            }
        }
        return topicPartition;
    }

    private void captureOutputRecords() {
        // Capture all the records sent to the producer ...
        final List<ProducerRecord<byte[], byte[]>> output = producer.history();
        producer.clear();
        if (eosEnabled && !producer.closed()) {
            producer.initTransactions();
            producer.beginTransaction();
        }
        for (final ProducerRecord<byte[], byte[]> record : output) {
            outputRecordsByTopic.computeIfAbsent(record.topic(), k -> new LinkedList<>()).add(record);

            // Forward back into the topology if the produced record is to an internal or a source topic ...
            final String outputTopicName = record.topic();
            if (internalTopics.contains(outputTopicName) || processorTopology.sourceTopics().contains(outputTopicName)
                || globalPartitionsByTopic.containsKey(outputTopicName)) {
                pipeRecord(record);
            }
        }
    }

    /**
     * Send input messages to the topology and then commit each message individually.
     *
     * @deprecated Since 2.4 use methods of {@link TestInputTopic} instead
     *
     * @param records a list of records to be processed
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public void pipeInput(final List<ConsumerRecord<byte[], byte[]>> records) {
        for (final ConsumerRecord<byte[], byte[]> record : records) {
            pipeInput(record);
        }
    }

    /**
     * Advances the internally mocked wall-clock time.
     * This might trigger a {@link PunctuationType#WALL_CLOCK_TIME wall-clock} type
     * {@link ProcessorContext#schedule(Duration, PunctuationType, Punctuator) punctuations}.
     *
     * @deprecated Since 2.4 use {@link #advanceWallClockTime(Duration)} instead
     *
     * @param advanceMs the amount of time to advance wall-clock time in milliseconds
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public void advanceWallClockTime(final long advanceMs) {
        advanceWallClockTime(Duration.ofMillis(advanceMs));
    }

    /**
     * Advances the internally mocked wall-clock time.
     * This might trigger a {@link PunctuationType#WALL_CLOCK_TIME wall-clock} type
     * {@link ProcessorContext#schedule(Duration, PunctuationType, Punctuator) punctuations}.
     *
     * @param advance the amount of time to advance wall-clock time
     */
    @SuppressWarnings("WeakerAccess")
    public void advanceWallClockTime(final Duration advance) {
        Objects.requireNonNull(advance, "advance cannot be null");
        mockWallClockTime.sleep(advance.toMillis());
        if (task != null) {
            task.maybePunctuateSystemTime();
            task.commit();
        }
        captureOutputRecords();
    }

    /**
     * Read the next record from the given topic.
     * These records were output by the topology during the previous calls to {@link #pipeInput(ConsumerRecord)}.
     *
     * @deprecated Since 2.4 use methods of {@link TestOutputTopic} instead
     *
     * @param topic the name of the topic
     * @return the next record on that topic, or {@code null} if there is no record available
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public ProducerRecord<byte[], byte[]> readOutput(final String topic) {
        final Queue<ProducerRecord<byte[], byte[]>> outputRecords = outputRecordsByTopic.get(topic);
        if (outputRecords == null) {
            return null;
        }
        return outputRecords.poll();
    }

    /**
     * Read the next record from the given topic.
     * These records were output by the topology during the previous calls to {@link #pipeInput(ConsumerRecord)}.
     *
     * @deprecated Since 2.4 use methods of {@link TestOutputTopic} instead
     *
     * @param topic             the name of the topic
     * @param keyDeserializer   the deserializer for the key type
     * @param valueDeserializer the deserializer for the value type
     * @return the next record on that topic, or {@code null} if there is no record available
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public <K, V> ProducerRecord<K, V> readOutput(final String topic,
                                                  final Deserializer<K> keyDeserializer,
                                                  final Deserializer<V> valueDeserializer) {
        final ProducerRecord<byte[], byte[]> record = readOutput(topic);
        if (record == null) {
            return null;
        }
        final K key = keyDeserializer.deserialize(record.topic(), record.key());
        final V value = valueDeserializer.deserialize(record.topic(), record.value());
        return new ProducerRecord<>(record.topic(), record.partition(), record.timestamp(), key, value, record.headers());
    }


    private final Queue<ProducerRecord<byte[], byte[]>> getRecordsQueue(final String topicName) {
        final Queue<ProducerRecord<byte[], byte[]>> outputRecords = outputRecordsByTopic.get(topicName);
        if (outputRecords == null) {
            if (!processorTopology.sinkTopics().contains(topicName)) {
                throw new IllegalArgumentException("Unknown topic: " + topicName);
            }
        }
        return outputRecords;
    }

    /**
     * Create {@link TestInputTopic} to be used for piping records to topic
     * Uses current system time as start timestamp for records.
     * Auto-advance is disabled.
     *
     * @param topicName             the name of the topic
     * @param keySerializer   the Serializer for the key type
     * @param valueSerializer the Serializer for the value type
     * @param <K> the key type
     * @param <V> the value type
     * @return {@link TestInputTopic} object
     */
    public final <K, V> TestInputTopic<K, V> createInputTopic(final String topicName,
                                                              final Serializer<K> keySerializer,
                                                              final Serializer<V> valueSerializer) {
        return new TestInputTopic<K, V>(this, topicName, keySerializer, valueSerializer, Instant.now(), Duration.ZERO);
    }

    /**
     * Create {@link TestInputTopic} to be used for piping records to topic
     * Uses provided start timestamp and autoAdvance parameter for records
     *
     * @param topicName             the name of the topic
     * @param keySerializer   the Serializer for the key type
     * @param valueSerializer the Serializer for the value type
     * @param startTimestamp Start timestamp for auto-generated record time
     * @param autoAdvance autoAdvance duration for auto-generated record time
     * @param <K> the key type
     * @param <V> the value type
     * @return {@link TestInputTopic} object
     */
    public final <K, V> TestInputTopic<K, V> createInputTopic(final String topicName,
                                                              final Serializer<K> keySerializer,
                                                              final Serializer<V> valueSerializer,
                                                              final Instant startTimestamp,
                                                              final Duration autoAdvance) {
        return new TestInputTopic<K, V>(this, topicName, keySerializer, valueSerializer, startTimestamp, autoAdvance);
    }

    /**
     * Create {@link TestOutputTopic} to be used for reading records from topic
     *
     * @param topicName             the name of the topic
     * @param keyDeserializer   the Deserializer for the key type
     * @param valueDeserializer the Deserializer for the value type
     * @param <K> the key type
     * @param <V> the value type
     * @return {@link TestOutputTopic} object
     */
    public final <K, V> TestOutputTopic<K, V> createOutputTopic(final String topicName,
                                                                final Deserializer<K> keyDeserializer,
                                                                final Deserializer<V> valueDeserializer) {
        return new TestOutputTopic<K, V>(this, topicName, keyDeserializer, valueDeserializer);
    }

    @SuppressWarnings("WeakerAccess")
    ProducerRecord<byte[], byte[]> readRecord(final String topic) {
        final Queue<? extends ProducerRecord<byte[], byte[]>> outputRecords = getRecordsQueue(topic);
        if (outputRecords == null) {
            return null;
        }
        return outputRecords.poll();
    }

    @SuppressWarnings("WeakerAccess")
    <K, V> TestRecord<K, V> readRecord(final String topic,
                                         final Deserializer<K> keyDeserializer,
                                         final Deserializer<V> valueDeserializer) {
        final Queue<? extends ProducerRecord<byte[], byte[]>> outputRecords = getRecordsQueue(topic);
        if (outputRecords == null) {
            throw new NoSuchElementException("Uninitialized topic: " + topic);
        }
        final ProducerRecord<byte[], byte[]> record = outputRecords.poll();
        if (record == null) {
            throw new NoSuchElementException("Empty topic: " + topic);
        }
        final K key = keyDeserializer.deserialize(record.topic(), record.key());
        final V value = valueDeserializer.deserialize(record.topic(), record.value());
        return new TestRecord<>(key, value, record.headers(), record.timestamp());
    }

    @SuppressWarnings("WeakerAccess")
    <K, V> void pipeRecord(final String topic,
                           final TestRecord<K, V> record,
                           final Serializer<K> keySerializer,
                           final Serializer<V> valueSerializer,
                           final Instant time) {
        final byte[] serializedKey = keySerializer.serialize(topic, record.headers(), record.key());
        final byte[] serializedValue = valueSerializer.serialize(topic, record.headers(), record.value());
        final long timestamp;
        if (time != null) {
            timestamp = time.toEpochMilli();
        } else if (record.timestamp() != null) {
            timestamp = record.timestamp();
        } else {
            throw new IllegalStateException("Provided `TestRecord` does not have a timestamp and no timestamp overwrite was provided via `time` parameter.");
        }

        pipeRecord(topic, timestamp, serializedKey, serializedValue, record.headers());
    }

    /**
     * Get size of unread record in the topic queue.
     *
     * @param topic
     * @return size of topic queue
     */
    final long getQueueSize(final String topic) {
        final Queue<ProducerRecord<byte[], byte[]>> queue = getRecordsQueue(topic);
        if (queue == null) {
            //Return 0 if not initialized, getRecordsQueue throw exception if non existing topic
            return 0;
        }
        return queue.size();
    }

    /**
     * Verify if the topic queue is empty.
     *
     * @param topic
     * @return true if no more record in the topic queue
     */
    final boolean isEmpty(final String topic) {
        return getQueueSize(topic) == 0;
    }


    /**
     * Get all {@link StateStore StateStores} from the topology.
     * The stores can be a "regular" or global stores.
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     * <p>
     * Note, that {@code StateStore} might be {@code null} if a store is added but not connected to any processor.
     * <p>
     * <strong>Caution:</strong> Using this method to access stores that are added by the DSL is unsafe as the store
     * types may change. Stores added by the DSL should only be accessed via the corresponding typed methods
     * like {@link #getKeyValueStore(String)} etc.
     *
     * @return all stores my name
     * @see #getStateStore(String)
     * @see #getKeyValueStore(String)
     * @see #getTimestampedKeyValueStore(String)
     * @see #getWindowStore(String)
     * @see #getTimestampedWindowStore(String)
     * @see #getSessionStore(String)
     */
    public Map<String, StateStore> getAllStateStores() {
        final Map<String, StateStore> allStores = new HashMap<>();
        for (final String storeName : internalTopologyBuilder.allStateStoreName()) {
            allStores.put(storeName, getStateStore(storeName, false));
        }
        return allStores;
    }

    /**
     * Get the {@link StateStore} with the given name.
     * The store can be a "regular" or global store.
     * <p>
     * Should be used for custom stores only.
     * For built-in stores, the corresponding typed methods like {@link #getKeyValueStore(String)} should be used.
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     *
     * @param name the name of the store
     * @return the state store, or {@code null} if no store has been registered with the given name
     * @throws IllegalArgumentException if the store is a built-in store like {@link KeyValueStore},
     * {@link WindowStore}, or {@link SessionStore}
     *
     * @see #getAllStateStores()
     * @see #getKeyValueStore(String)
     * @see #getTimestampedKeyValueStore(String)
     * @see #getWindowStore(String)
     * @see #getTimestampedWindowStore(String)
     * @see #getSessionStore(String)
     */
    @SuppressWarnings("WeakerAccess")
    public StateStore getStateStore(final String name) throws IllegalArgumentException {
        return getStateStore(name, true);
    }

    private StateStore getStateStore(final String name,
                                     final boolean throwForBuiltInStores) {
        if (task != null) {
            final StateStore stateStore = ((ProcessorContextImpl) task.context()).getStateMgr().getStore(name);
            if (stateStore != null) {
                if (throwForBuiltInStores) {
                    throwIfBuiltInStore(stateStore);
                }
                return stateStore;
            }
        }

        if (globalStateManager != null) {
            final StateStore stateStore = globalStateManager.getGlobalStore(name);
            if (stateStore != null) {
                if (throwForBuiltInStores) {
                    throwIfBuiltInStore(stateStore);
                }
                return stateStore;
            }

        }

        return null;
    }

    private void throwIfBuiltInStore(final StateStore stateStore) {
        if (stateStore instanceof TimestampedKeyValueStore) {
            throw new IllegalArgumentException("Store " + stateStore.name()
                + " is a timestamped key-value store and should be accessed via `getTimestampedKeyValueStore()`");
        }
        if (stateStore instanceof ReadOnlyKeyValueStore) {
            throw new IllegalArgumentException("Store " + stateStore.name()
                + " is a key-value store and should be accessed via `getKeyValueStore()`");
        }
        if (stateStore instanceof TimestampedWindowStore) {
            throw new IllegalArgumentException("Store " + stateStore.name()
                + " is a timestamped window store and should be accessed via `getTimestampedWindowStore()`");
        }
        if (stateStore instanceof ReadOnlyWindowStore) {
            throw new IllegalArgumentException("Store " + stateStore.name()
                + " is a window store and should be accessed via `getWindowStore()`");
        }
        if (stateStore instanceof ReadOnlySessionStore) {
            throw new IllegalArgumentException("Store " + stateStore.name()
                + " is a session store and should be accessed via `getSessionStore()`");
        }
    }

    /**
     * Get the {@link KeyValueStore} or {@link TimestampedKeyValueStore} with the given name.
     * The store can be a "regular" or global store.
     * <p>
     * If the registered store is a {@link TimestampedKeyValueStore} this method will return a value-only query
     * interface. <strong>It is highly recommended to update the code for this case to avoid bugs and to use
     * {@link #getTimestampedKeyValueStore(String)} for full store access instead.</strong>
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     *
     * @param name the name of the store
     * @return the key value store, or {@code null} if no {@link KeyValueStore} or {@link TimestampedKeyValueStore}
     * has been registered with the given name
     * @see #getAllStateStores()
     * @see #getStateStore(String)
     * @see #getTimestampedKeyValueStore(String)
     * @see #getWindowStore(String)
     * @see #getTimestampedWindowStore(String)
     * @see #getSessionStore(String)
     */
    @SuppressWarnings({"unchecked", "WeakerAccess"})
    public <K, V> KeyValueStore<K, V> getKeyValueStore(final String name) {
        final StateStore store = getStateStore(name, false);
        if (store instanceof TimestampedKeyValueStore) {
            log.info("Method #getTimestampedKeyValueStore() should be used to access a TimestampedKeyValueStore.");
            return new KeyValueStoreFacade<>((TimestampedKeyValueStore<K, V>) store);
        }
        return store instanceof KeyValueStore ? (KeyValueStore<K, V>) store : null;
    }

    /**
     * Get the {@link TimestampedKeyValueStore} with the given name.
     * The store can be a "regular" or global store.
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     *
     * @param name the name of the store
     * @return the key value store, or {@code null} if no {@link TimestampedKeyValueStore} has been registered with the given name
     * @see #getAllStateStores()
     * @see #getStateStore(String)
     * @see #getKeyValueStore(String)
     * @see #getWindowStore(String)
     * @see #getTimestampedWindowStore(String)
     * @see #getSessionStore(String)
     */
    @SuppressWarnings({"unchecked", "WeakerAccess"})
    public <K, V> KeyValueStore<K, ValueAndTimestamp<V>> getTimestampedKeyValueStore(final String name) {
        final StateStore store = getStateStore(name, false);
        return store instanceof TimestampedKeyValueStore ? (TimestampedKeyValueStore<K, V>) store : null;
    }

    /**
     * Get the {@link WindowStore} or {@link TimestampedWindowStore} with the given name.
     * The store can be a "regular" or global store.
     * <p>
     * If the registered store is a {@link TimestampedWindowStore} this method will return a value-only query
     * interface. <strong>It is highly recommended to update the code for this case to avoid bugs and to use
     * {@link #getTimestampedWindowStore(String)} for full store access instead.</strong>
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     *
     * @param name the name of the store
     * @return the key value store, or {@code null} if no {@link WindowStore} or {@link TimestampedWindowStore}
     * has been registered with the given name
     * @see #getAllStateStores()
     * @see #getStateStore(String)
     * @see #getKeyValueStore(String)
     * @see #getTimestampedKeyValueStore(String)
     * @see #getTimestampedWindowStore(String)
     * @see #getSessionStore(String)
     */
    @SuppressWarnings({"unchecked", "WeakerAccess"})
    public <K, V> WindowStore<K, V> getWindowStore(final String name) {
        final StateStore store = getStateStore(name, false);
        if (store instanceof TimestampedWindowStore) {
            log.info("Method #getTimestampedWindowStore() should be used to access a TimestampedWindowStore.");
            return new WindowStoreFacade<>((TimestampedWindowStore<K, V>) store);
        }
        return store instanceof WindowStore ? (WindowStore<K, V>) store : null;
    }

    /**
     * Get the {@link TimestampedWindowStore} with the given name.
     * The store can be a "regular" or global store.
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     *
     * @param name the name of the store
     * @return the key value store, or {@code null} if no {@link TimestampedWindowStore} has been registered with the given name
     * @see #getAllStateStores()
     * @see #getStateStore(String)
     * @see #getKeyValueStore(String)
     * @see #getTimestampedKeyValueStore(String)
     * @see #getWindowStore(String)
     * @see #getSessionStore(String)
     */
    @SuppressWarnings({"unchecked", "WeakerAccess"})
    public <K, V> WindowStore<K, ValueAndTimestamp<V>> getTimestampedWindowStore(final String name) {
        final StateStore store = getStateStore(name, false);
        return store instanceof TimestampedWindowStore ? (TimestampedWindowStore<K, V>) store : null;
    }

    /**
     * Get the {@link SessionStore} with the given name.
     * The store can be a "regular" or global store.
     * <p>
     * This is often useful in test cases to pre-populate the store before the test case instructs the topology to
     * {@link #pipeInput(ConsumerRecord) process an input message}, and/or to check the store afterward.
     *
     * @param name the name of the store
     * @return the key value store, or {@code null} if no {@link SessionStore} has been registered with the given name
     * @see #getAllStateStores()
     * @see #getStateStore(String)
     * @see #getKeyValueStore(String)
     * @see #getTimestampedKeyValueStore(String)
     * @see #getWindowStore(String)
     * @see #getTimestampedWindowStore(String)
     */
    @SuppressWarnings({"unchecked", "WeakerAccess"})
    public <K, V> SessionStore<K, V> getSessionStore(final String name) {
        final StateStore store = getStateStore(name, false);
        return store instanceof SessionStore ? (SessionStore<K, V>) store : null;
    }

    /**
     * Close the driver, its topology, and all processors.
     */
    @SuppressWarnings("WeakerAccess")
    public void close() {
        if (task != null) {
            task.close(true, false);
        }
        if (globalStateTask != null) {
            try {
                globalStateTask.close();
            } catch (final IOException e) {
                // ignore
            }
        }
        captureOutputRecords();
        if (!eosEnabled) {
            producer.close();
        }
        stateDirectory.clean();
    }

    static class MockTime implements Time {
        private final AtomicLong timeMs;
        private final AtomicLong highResTimeNs;

        MockTime(final long startTimestampMs) {
            this.timeMs = new AtomicLong(startTimestampMs);
            this.highResTimeNs = new AtomicLong(startTimestampMs * 1000L * 1000L);
        }

        @Override
        public long milliseconds() {
            return timeMs.get();
        }

        @Override
        public long nanoseconds() {
            return highResTimeNs.get();
        }

        @Override
        public long hiResClockMs() {
            return TimeUnit.NANOSECONDS.toMillis(nanoseconds());
        }

        @Override
        public void sleep(final long ms) {
            if (ms < 0) {
                throw new IllegalArgumentException("Sleep ms cannot be negative.");
            }
            timeMs.addAndGet(ms);
            highResTimeNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
        }

        @Override
        public void waitObject(final Object obj, final Supplier<Boolean> condition, final long timeoutMs) {
            throw new UnsupportedOperationException();
        }

    }

    private MockConsumer<byte[], byte[]> createRestoreConsumer(final Map<String, String> storeToChangelogTopic) {
        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.LATEST) {
            @Override
            public synchronized void seekToEnd(final Collection<TopicPartition> partitions) {}

            @Override
            public synchronized void seekToBeginning(final Collection<TopicPartition> partitions) {}

            @Override
            public synchronized long position(final TopicPartition partition) {
                return 0L;
            }
        };

        // for each store
        for (final Map.Entry<String, String> storeAndTopic : storeToChangelogTopic.entrySet()) {
            final String topicName = storeAndTopic.getValue();
            // Set up the restore-state topic ...
            // consumer.subscribe(new TopicPartition(topicName, 0));
            // Set up the partition that matches the ID (which is what ProcessorStateManager expects) ...
            final List<PartitionInfo> partitionInfos = new ArrayList<>();
            partitionInfos.add(new PartitionInfo(topicName, PARTITION_ID, null, null, null));
            consumer.updatePartitions(topicName, partitionInfos);
            consumer.updateEndOffsets(Collections.singletonMap(new TopicPartition(topicName, PARTITION_ID), 0L));
        }
        return consumer;
    }
}
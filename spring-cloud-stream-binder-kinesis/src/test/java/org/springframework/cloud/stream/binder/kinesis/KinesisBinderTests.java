/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kinesis;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;
import com.amazonaws.services.kinesis.model.Record;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.ShardIteratorType;
import com.amazonaws.services.kinesis.model.StreamDescription;
import com.amazonaws.services.kinesis.model.StreamStatus;
import org.assertj.core.api.Condition;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.BDDMockito;
import org.mockito.stubbing.Answer;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.PartitionCapableBinderTests;
import org.springframework.cloud.stream.binder.Spy;
import org.springframework.cloud.stream.binder.TestUtils;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binding.MessageConverterConfigurer;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.aws.inbound.kinesis.KinesisShardOffset;
import org.springframework.integration.aws.inbound.kinesis.ListenerMode;
import org.springframework.integration.aws.support.AwsRequestFailureException;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.NullChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * The tests for Kinesis Binder.
 *
 * @author Artem Bilan
 * @author Jacob Severson
 * @author Arnaud Lecollaire
 */
public class KinesisBinderTests extends
		PartitionCapableBinderTests<KinesisTestBinder, ExtendedConsumerProperties<KinesisConsumerProperties>,
				ExtendedProducerProperties<KinesisProducerProperties>> {

	private static final String CLASS_UNDER_TEST_NAME = KinesisBinderTests.class
			.getSimpleName();

	/**
	 * Class rule for the {@link LocalKinesisResource}.
	 */
	@ClassRule
	public static LocalKinesisResource localKinesisResource = new LocalKinesisResource();

	/**
	 * Class rule for the {@link LocalDynamoDbResource}.
	 */
	@ClassRule
	public static LocalDynamoDbResource localDynamoDbResource = new LocalDynamoDbResource();

	public KinesisBinderTests() {
		this.timeoutMultiplier = 10D;
	}

	@Test
	@Override
	public void testClean() {
	}

	@Test
	public void testAutoCreateStreamForNonExistingStream() throws Exception {
		KinesisTestBinder binder = getBinder();
		DirectChannel output = createBindableChannel("output", new BindingProperties());
		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		Date testDate = new Date();
		consumerProperties.getExtension().setShardIteratorType(
				ShardIteratorType.AT_TIMESTAMP.name() + ":" + testDate.getTime());
		String testStreamName = "nonexisting" + System.currentTimeMillis();
		Binding<?> binding = binder.bindConsumer(testStreamName, "test", output,
				consumerProperties);
		binding.unbind();

		DescribeStreamResult streamResult = localKinesisResource.getResource()
				.describeStream(testStreamName);
		String createdStreamName = streamResult.getStreamDescription().getStreamName();
		int createdShards = streamResult.getStreamDescription().getShards().size();
		String createdStreamStatus = streamResult.getStreamDescription()
				.getStreamStatus();

		assertThat(createdStreamName).isEqualTo(testStreamName);
		assertThat(createdShards).isEqualTo(consumerProperties.getInstanceCount()
				* consumerProperties.getConcurrency());
		assertThat(createdStreamStatus).isEqualTo(StreamStatus.ACTIVE.toString());

		KinesisShardOffset shardOffset = TestUtils.getPropertyValue(binding,
				"lifecycle.streamInitialSequence", KinesisShardOffset.class);
		assertThat(shardOffset.getIteratorType())
				.isEqualTo(ShardIteratorType.AT_TIMESTAMP);
		assertThat(shardOffset.getTimestamp()).isEqualTo(testDate);
	}

	@Test
	@Override
	@SuppressWarnings("unchecked")
	public void testAnonymousGroup() throws Exception {
		KinesisTestBinder binder = getBinder();
		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = createProducerProperties();
		DirectChannel output = createBindableChannel("output",
				createProducerBindingProperties(producerProperties));

		Binding<MessageChannel> producerBinding = binder.bindProducer(
				String.format("defaultGroup%s0", getDestinationNameDelimiter()), output,
				producerProperties);

		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		consumerProperties.setConcurrency(2);
		consumerProperties.setInstanceCount(3);
		consumerProperties.setInstanceIndex(0);

		QueueChannel input1 = new QueueChannel();
		Binding<MessageChannel> binding1 = binder.bindConsumer(
				String.format("defaultGroup%s0", getDestinationNameDelimiter()), null,
				input1, consumerProperties);

		consumerProperties.setInstanceIndex(1);

		QueueChannel input2 = new QueueChannel();
		Binding<MessageChannel> binding2 = binder.bindConsumer(
				String.format("defaultGroup%s0", getDestinationNameDelimiter()), null,
				input2, consumerProperties);

		String testPayload1 = "foo-" + UUID.randomUUID().toString();
		output.send(MessageBuilder.withPayload(testPayload1)
				.setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN)
				.build());

		Message<byte[]> receivedMessage1 = (Message<byte[]>) receive(input1);
		assertThat(receivedMessage1).isNotNull();
		assertThat(new String(receivedMessage1.getPayload())).isEqualTo(testPayload1);

		Message<byte[]> receivedMessage2 = (Message<byte[]>) receive(input2);
		assertThat(receivedMessage2).isNotNull();
		assertThat(new String(receivedMessage2.getPayload())).isEqualTo(testPayload1);

		binding2.unbind();

		String testPayload2 = "foo-" + UUID.randomUUID().toString();
		output.send(MessageBuilder.withPayload(testPayload2)
				.setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN)
				.build());

		binding2 = binder.bindConsumer(
				String.format("defaultGroup%s0", getDestinationNameDelimiter()), null,
				input2, consumerProperties);
		String testPayload3 = "foo-" + UUID.randomUUID().toString();
		output.send(MessageBuilder.withPayload(testPayload3)
				.setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN)
				.build());

		receivedMessage1 = (Message<byte[]>) receive(input1);
		assertThat(receivedMessage1).isNotNull();
		assertThat(new String(receivedMessage1.getPayload())).isEqualTo(testPayload2);
		receivedMessage1 = (Message<byte[]>) receive(input1);
		assertThat(receivedMessage1).isNotNull();
		assertThat(new String(receivedMessage1.getPayload())).isNotNull();

		receivedMessage2 = (Message<byte[]>) receive(input2);
		assertThat(receivedMessage2).isNotNull();
		assertThat(new String(receivedMessage2.getPayload())).isEqualTo(testPayload1);

		receivedMessage2 = (Message<byte[]>) receive(input2);
		assertThat(receivedMessage2).isNotNull();
		assertThat(new String(receivedMessage2.getPayload())).isEqualTo(testPayload2);

		receivedMessage2 = (Message<byte[]>) receive(input2);
		assertThat(receivedMessage2).isNotNull();
		assertThat(new String(receivedMessage2.getPayload())).isEqualTo(testPayload3);

		producerBinding.unbind();
		binding1.unbind();
		binding2.unbind();
	}

	@Test
	@Override
	public void testPartitionedModuleJava() throws Exception {
		KinesisTestBinder binder = getBinder();

		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		consumerProperties.setConcurrency(2);
		consumerProperties.setInstanceCount(3);
		consumerProperties.setInstanceIndex(0);
		consumerProperties.setPartitioned(true);

		final List<Message<?>> results = new ArrayList<>();
		final CountDownLatch receiveLatch = new CountDownLatch(3);

		MessageHandler receivingHandler = (message) -> {
			results.add(message);
			receiveLatch.countDown();
		};

		DirectChannel input0 = createBindableChannelInternal("test.input0J", new BindingProperties(), true);
		input0.subscribe(receivingHandler);

		Binding<MessageChannel> input0Binding = binder.bindConsumer("partJ.0",
				"testPartitionedModuleJava", input0, consumerProperties);

		consumerProperties.setInstanceIndex(1);

		DirectChannel input1 = createBindableChannelInternal("test.input1J", new BindingProperties(), true);
		input1.subscribe(receivingHandler);

		Binding<MessageChannel> input1Binding = binder.bindConsumer("partJ.0",
				"testPartitionedModuleJava", input1, consumerProperties);

		consumerProperties.setInstanceIndex(2);

		DirectChannel input2 = createBindableChannelInternal("test.input2J", new BindingProperties(), true);
		input2.subscribe(receivingHandler);

		Binding<MessageChannel> input2Binding = binder.bindConsumer("partJ.0",
				"testPartitionedModuleJava", input2, consumerProperties);

		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = createProducerProperties();

		producerProperties.setPartitionKeyExtractorName("partitionSupport");
		producerProperties.setPartitionSelectorName("partitionSupport");
		producerProperties.setPartitionCount(3);

		DirectChannel output = createBindableChannelInternal("test.output",
				createProducerBindingProperties(producerProperties), false);

		Binding<MessageChannel> outputBinding = binder.bindProducer("partJ.0", output,
				producerProperties);
		if (usesExplicitRouting()) {
			Object endpoint = extractEndpoint(outputBinding);
			assertThat(getEndpointRouting(endpoint))
					.contains(getExpectedRoutingBaseDestination("partJ.0",
							"testPartitionedModuleJava") + "-' + headers['"
							+ BinderHeaders.PARTITION_HEADER + "']");
		}

		output.send(new GenericMessage<>(2));
		output.send(new GenericMessage<>(1));
		output.send(new GenericMessage<>(0));

		assertThat(receiveLatch.await(20, TimeUnit.SECONDS)).isTrue();

		assertThat(results).extracting("payload").containsExactlyInAnyOrder(
				"0".getBytes(), "1".getBytes(), "2".getBytes());

		input0Binding.unbind();
		input1Binding.unbind();
		input2Binding.unbind();
		outputBinding.unbind();
	}

	@Test
	@Override
	public void testPartitionedModuleSpEL() throws Exception {
		KinesisTestBinder binder = getBinder();

		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		consumerProperties.setConcurrency(2);
		consumerProperties.setInstanceIndex(0);
		consumerProperties.setInstanceCount(3);
		consumerProperties.setPartitioned(true);

		final List<Message<?>> results = new ArrayList<>();
		final CountDownLatch receiveLatch = new CountDownLatch(3);

		MessageHandler receivingHandler = (message) -> {
			results.add(message);
			receiveLatch.countDown();
		};

		DirectChannel input0 = createBindableChannel("test.input0S",
				new BindingProperties());
		input0.subscribe(receivingHandler);

		Binding<MessageChannel> input0Binding = binder.bindConsumer("part.0",
				"testPartitionedModuleSpEL", input0, consumerProperties);

		consumerProperties.setInstanceIndex(1);

		DirectChannel input1 = createBindableChannel("test.input1S",
				new BindingProperties());
		input1.subscribe(receivingHandler);

		Binding<MessageChannel> input1Binding = binder.bindConsumer("part.0",
				"testPartitionedModuleSpEL", input1, consumerProperties);

		consumerProperties.setInstanceIndex(2);

		DirectChannel input2 = createBindableChannel("test.input2S",
				new BindingProperties());
		input2.subscribe(receivingHandler);

		Binding<MessageChannel> input2Binding = binder.bindConsumer("part.0",
				"testPartitionedModuleSpEL", input2, consumerProperties);

		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = createProducerProperties();
		producerProperties.setPartitionKeyExpression(
				spelExpressionParser.parseExpression("headers.partitionKey"));
		producerProperties.setPartitionCount(3);

		DirectChannel output = createBindableChannel("test.output",
				createProducerBindingProperties(producerProperties));

		Binding<MessageChannel> outputBinding = binder.bindProducer("part.0", output,
				producerProperties);
		try {
			Object endpoint = extractEndpoint(outputBinding);
			checkRkExpressionForPartitionedModuleSpEL(endpoint);
		}
		catch (UnsupportedOperationException ignored) {
		}

		Message<Integer> message2 = MessageBuilder.withPayload(2)
				.setHeader("partitionKey", 2)
				.setHeader(IntegrationMessageHeaderAccessor.CORRELATION_ID, "foo")
				.setHeader(IntegrationMessageHeaderAccessor.SEQUENCE_NUMBER, 42)
				.setHeader(IntegrationMessageHeaderAccessor.SEQUENCE_SIZE, 43).build();
		output.send(message2);
		output.send(MessageBuilder.withPayload(1).setHeader("partitionKey", 1).build());
		output.send(MessageBuilder.withPayload(0).setHeader("partitionKey", 0).build());

		assertThat(receiveLatch.await(20, TimeUnit.SECONDS)).isTrue();

		assertThat(results).extracting("payload").containsExactlyInAnyOrder(
				"0".getBytes(), "1".getBytes(), "2".getBytes());

		Condition<Message<?>> correlationHeadersForPayload2 = new Condition<Message<?>>() {

			@Override
			public boolean matches(Message<?> value) {
				IntegrationMessageHeaderAccessor accessor = new IntegrationMessageHeaderAccessor(
						value);
				return "foo".equals(accessor.getCorrelationId())
						&& 42 == accessor.getSequenceNumber()
						&& 43 == accessor.getSequenceSize();
			}

		};

		Condition<Message<?>> payloadIs2 = new Condition<Message<?>>() {

			@Override
			public boolean matches(Message<?> value) {
				return new String((byte[]) value.getPayload()).equals("2");
			}

		};

		assertThat(results).filteredOn(payloadIs2).areExactly(1,
				correlationHeadersForPayload2);

		input0Binding.unbind();
		input1Binding.unbind();
		input2Binding.unbind();
		outputBinding.unbind();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testProducerErrorChannel() throws Exception {
		KinesisTestBinder binder = getBinder();

		final RuntimeException putRecordException = new RuntimeException(
				"putRecordRequestEx");
		final AtomicReference<Object> sent = new AtomicReference<>();
		AmazonKinesisAsync amazonKinesisMock = mock(AmazonKinesisAsync.class);
		BDDMockito
				.given(amazonKinesisMock.putRecordAsync(any(PutRecordRequest.class),
						any(AsyncHandler.class)))
				.willAnswer((Answer<Future<PutRecordResult>>) (invocation) -> {
					PutRecordRequest request = invocation.getArgument(0);
					sent.set(request.getData());
					AsyncHandler<?, ?> handler = invocation.getArgument(1);
					handler.onError(putRecordException);
					return mock(Future.class);
				});

		new DirectFieldAccessor(binder.getBinder()).setPropertyValue("amazonKinesis",
				amazonKinesisMock);

		ExtendedProducerProperties<KinesisProducerProperties> producerProps = createProducerProperties();
		producerProps.setErrorChannelEnabled(true);
		DirectChannel moduleOutputChannel = createBindableChannel("output",
				createProducerBindingProperties(producerProps));
		Binding<MessageChannel> producerBinding = binder.bindProducer("ec.0",
				moduleOutputChannel, producerProps);

		ApplicationContext applicationContext = TestUtils.getPropertyValue(
				binder.getBinder(), "applicationContext", ApplicationContext.class);
		SubscribableChannel ec = applicationContext.getBean("ec.0.errors",
				SubscribableChannel.class);
		final AtomicReference<Message<?>> errorMessage = new AtomicReference<>();
		final CountDownLatch latch = new CountDownLatch(1);
		ec.subscribe((message) -> {
			errorMessage.set(message);
			latch.countDown();
		});

		String messagePayload = "oops";
		moduleOutputChannel.send(new GenericMessage<>(messagePayload.getBytes()));

		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(errorMessage.get()).isInstanceOf(ErrorMessage.class);
		assertThat(errorMessage.get().getPayload())
				.isInstanceOf(AwsRequestFailureException.class);
		AwsRequestFailureException exception = (AwsRequestFailureException) errorMessage
				.get().getPayload();
		assertThat(exception.getCause()).isSameAs(putRecordException);
		assertThat(((PutRecordRequest) exception.getRequest()).getData())
				.isSameAs(sent.get());
		producerBinding.unbind();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testBatchListener() throws Exception {
		KinesisTestBinder binder = getBinder();
		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = createProducerProperties();
		DirectChannel output = createBindableChannel("output",
				createProducerBindingProperties(producerProperties));

		Binding<MessageChannel> outputBinding = binder.bindProducer("testBatchListener",
				output, producerProperties);

		for (int i = 0; i < 3; i++) {
			output.send(new GenericMessage<>(i));
		}

		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		consumerProperties.getExtension().setListenerMode(ListenerMode.batch);
		consumerProperties.setUseNativeDecoding(true);

		QueueChannel input = new QueueChannel();
		Binding<MessageChannel> inputBinding = binder.bindConsumer("testBatchListener",
				null, input, consumerProperties);

		Message<List<?>> receivedMessage = (Message<List<?>>) receive(input);
		assertThat(receivedMessage).isNotNull();
		assertThat(receivedMessage.getPayload().size()).isEqualTo(3);

		receivedMessage.getPayload().forEach((r) -> {
			assertThat(r).isInstanceOf(Record.class);
		});

		outputBinding.unbind();
		inputBinding.unbind();
	}

	@Test
	@Ignore("Kinesalite doesn't support updateShardCount. Test only against real AWS Kinesis")
	public void testPartitionCountIncreasedIfAutoAddPartitionsSet() {
		KinesisBinderConfigurationProperties configurationProperties = new KinesisBinderConfigurationProperties();

		String stream = "existing" + System.currentTimeMillis();

		AmazonKinesisAsync amazonKinesis = localKinesisResource.getResource();
		amazonKinesis.createStream(stream, 1);

		List<Shard> shards = describeStream(stream);

		assertThat(shards.size()).isEqualTo(1);

		configurationProperties.setMinShardCount(6);
		configurationProperties.setAutoAddShards(true);
		KinesisTestBinder binder = getBinder(configurationProperties);

		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		Binding<?> binding = binder.bindConsumer(stream, "test", new NullChannel(),
				consumerProperties);
		binding.unbind();

		shards = describeStream(stream);

		assertThat(shards.size()).isEqualTo(6);
	}

	private List<Shard> describeStream(String stream) {
		AmazonKinesisAsync amazonKinesis = localKinesisResource.getResource();

		String exclusiveStartShardId = null;

		DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest()
				.withStreamName(stream);

		List<Shard> shardList = new ArrayList<>();

		while (true) {
			DescribeStreamResult describeStreamResult;

			describeStreamRequest.withExclusiveStartShardId(exclusiveStartShardId);
			describeStreamResult = amazonKinesis.describeStream(describeStreamRequest);
			StreamDescription streamDescription = describeStreamResult
					.getStreamDescription();
			if (StreamStatus.ACTIVE.toString()
					.equals(streamDescription.getStreamStatus())) {
				shardList.addAll(streamDescription.getShards());

				if (streamDescription.getHasMoreShards()) {
					exclusiveStartShardId = shardList.get(shardList.size() - 1)
							.getShardId();
					continue;
				}
				else {
					return shardList;
				}
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
	}

	@Override
	protected boolean usesExplicitRouting() {
		return false;
	}

	@Override
	protected String getClassUnderTestName() {
		return CLASS_UNDER_TEST_NAME;
	}

	@Override
	protected KinesisTestBinder getBinder() {
		return getBinder(new KinesisBinderConfigurationProperties());
	}

	private KinesisTestBinder getBinder(
			KinesisBinderConfigurationProperties kinesisBinderConfigurationProperties) {
		if (this.testBinder == null) {
			this.testBinder = new KinesisTestBinder(localKinesisResource.getResource(),
					localDynamoDbResource.getResource(),
					kinesisBinderConfigurationProperties);
			this.timeoutMultiplier = 20;
		}
		return this.testBinder;
	}

	@Override
	protected ExtendedConsumerProperties<KinesisConsumerProperties> createConsumerProperties() {
		ExtendedConsumerProperties<KinesisConsumerProperties> kafkaConsumerProperties = new ExtendedConsumerProperties<>(
				new KinesisConsumerProperties());
		// set the default values that would normally be propagated by Spring Cloud Stream
		kafkaConsumerProperties.setInstanceCount(1);
		kafkaConsumerProperties.setInstanceIndex(0);
		kafkaConsumerProperties.getExtension()
				.setShardIteratorType(ShardIteratorType.TRIM_HORIZON.name());
		return kafkaConsumerProperties;
	}

	@Override
	protected ExtendedProducerProperties<KinesisProducerProperties> createProducerProperties() {
		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = new ExtendedProducerProperties<>(
				new KinesisProducerProperties());
		producerProperties.setPartitionKeyExpression(new LiteralExpression("1"));
		producerProperties.getExtension().setSync(true);
		return producerProperties;
	}

	private DirectChannel createBindableChannelInternal(String channelName,
			BindingProperties bindingProperties, boolean inputChannel) {

		MessageConverterConfigurer messageConverterConfigurer = getBinder()
				.getApplicationContext().getBean(MessageConverterConfigurer.class);

		DirectChannel channel = new DirectChannel();
		channel.setBeanName(channelName);
		if (inputChannel) {
			messageConverterConfigurer.configureInputChannel(channel, channelName);
		}
		else {
			messageConverterConfigurer.configureOutputChannel(channel, channelName);
		}
		return channel;
	}

	@Override
	public Spy spyOn(String name) {
		throw new UnsupportedOperationException("'spyOn' is not used by Kinesis tests");
	}

}

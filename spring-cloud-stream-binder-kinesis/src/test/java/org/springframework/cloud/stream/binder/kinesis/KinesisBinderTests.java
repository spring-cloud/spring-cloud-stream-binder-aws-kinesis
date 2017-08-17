/*
 * Copyright 2017 the original author or authors.
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.StreamStatus;

import org.assertj.core.api.Condition;
import org.junit.ClassRule;
import org.junit.Test;

import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.PartitionCapableBinderTests;
import org.springframework.cloud.stream.binder.PartitionTestSupport;
import org.springframework.cloud.stream.binder.Spy;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.FixedSubscriberChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.GenericMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Artem Bilan
 * @author Jacob Severson
 *
 */
public class KinesisBinderTests
		extends PartitionCapableBinderTests<KinesisTestBinder, ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>> {

	private final String CLASS_UNDER_TEST_NAME = KinesisBinderTests.class.getSimpleName();

	@ClassRule
	public static LocalKinesisResource localKinesisResource = new LocalKinesisResource();

	@Test
	@Override
	public void testClean() throws Exception {
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testAutoCreateStreamForNonExistingStream() throws Exception {
		Binder binder = getBinder();
		DirectChannel output = new DirectChannel();
		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		String testStreamName = "nonexisting" + System.currentTimeMillis();
		Binding<?> binding = binder.bindConsumer(testStreamName, "test", output, consumerProperties);
		binding.unbind();

		DescribeStreamResult streamResult = localKinesisResource.getResource().describeStream(testStreamName);
		String createdStreamName = streamResult.getStreamDescription().getStreamName();
		int createdShards = streamResult.getStreamDescription().getShards().size();
		String createdStreamStatus = streamResult.getStreamDescription().getStreamStatus();

		assertThat(createdStreamName).isEqualTo(testStreamName);
		assertThat(createdShards).isEqualTo(consumerProperties.getInstanceCount() * consumerProperties.getConcurrency());
		assertThat(createdStreamStatus).isEqualTo(StreamStatus.ACTIVE.toString());
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

		MessageHandler receivingHandler = new MessageHandler() {

			@Override
			public void handleMessage(Message<?> message) throws MessagingException {
				results.add(message);
				receiveLatch.countDown();
			}

		};
		FixedSubscriberChannel input0 = new FixedSubscriberChannel(receivingHandler);
		input0.setBeanName("test.input0J");
		Binding<MessageChannel> input0Binding = binder.bindConsumer("partJ.0", "testPartitionedModuleJava", input0, consumerProperties);
		consumerProperties.setInstanceIndex(1);

		FixedSubscriberChannel input1 = new FixedSubscriberChannel(receivingHandler);
		input1.setBeanName("test.input1J");
		Binding<MessageChannel> input1Binding = binder.bindConsumer("partJ.0", "testPartitionedModuleJava", input1, consumerProperties);
		consumerProperties.setInstanceIndex(2);

		FixedSubscriberChannel input2 = new FixedSubscriberChannel(receivingHandler);
		input2.setBeanName("test.input2J");
		Binding<MessageChannel> input2Binding = binder.bindConsumer("partJ.0", "testPartitionedModuleJava", input2, consumerProperties);

		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = createProducerProperties();
		producerProperties.setPartitionKeyExtractorClass(PartitionTestSupport.class);
		producerProperties.setPartitionSelectorClass(PartitionTestSupport.class);
		producerProperties.setPartitionCount(3);
		DirectChannel output = createBindableChannel("output", createProducerBindingProperties(producerProperties));
		output.setBeanName("test.output");
		Binding<MessageChannel> outputBinding = binder.bindProducer("partJ.0", output, producerProperties);
		if (usesExplicitRouting()) {
			Object endpoint = extractEndpoint(outputBinding);
			assertThat(getEndpointRouting(endpoint)).contains(getExpectedRoutingBaseDestination("partJ.0", "testPartitionedModuleJava")
					+ "-' + headers['" + BinderHeaders.PARTITION_HEADER + "']");
		}

		output.send(new GenericMessage<>(2));
		output.send(new GenericMessage<>(1));
		output.send(new GenericMessage<>(0));


		assertThat(receiveLatch.await(20, TimeUnit.SECONDS)).isTrue();

		assertThat(results).extracting("payload").containsExactlyInAnyOrder(0, 1, 2);

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

		MessageHandler receivingHandler = new MessageHandler() {

			@Override
			public void handleMessage(Message<?> message) throws MessagingException {
				results.add(message);
				receiveLatch.countDown();
			}

		};

		FixedSubscriberChannel input0 = new FixedSubscriberChannel(receivingHandler);
		input0.setBeanName("test.input0S");
		Binding<MessageChannel> input0Binding = binder.bindConsumer("part.0", "testPartitionedModuleSpEL", input0, consumerProperties);
		consumerProperties.setInstanceIndex(1);

		FixedSubscriberChannel input1 = new FixedSubscriberChannel(receivingHandler);
		input1.setBeanName("test.input1S");
		Binding<MessageChannel> input1Binding = binder.bindConsumer("part.0", "testPartitionedModuleSpEL", input1, consumerProperties);
		consumerProperties.setInstanceIndex(2);

		FixedSubscriberChannel input2 = new FixedSubscriberChannel(receivingHandler);
		input2.setBeanName("test.input2S");
		Binding<MessageChannel> input2Binding = binder.bindConsumer("part.0", "testPartitionedModuleSpEL", input2, consumerProperties);

		ExtendedProducerProperties<KinesisProducerProperties> producerProperties = createProducerProperties();
		producerProperties.setPartitionKeyExpression(spelExpressionParser.parseExpression("payload"));
		producerProperties.setPartitionSelectorExpression(spelExpressionParser.parseExpression("hashCode()"));
		producerProperties.setPartitionCount(3);

		DirectChannel output = createBindableChannel("output", createProducerBindingProperties(producerProperties));
		output.setBeanName("test.output");
		Binding<MessageChannel> outputBinding = binder.bindProducer("part.0", output, producerProperties);
		try {
			Object endpoint = extractEndpoint(outputBinding);
			checkRkExpressionForPartitionedModuleSpEL(endpoint);
		}
		catch (UnsupportedOperationException ignored) {
		}

		Message<Integer> message2 = MessageBuilder.withPayload(2)
				.setHeader(IntegrationMessageHeaderAccessor.CORRELATION_ID, "foo")
				.setHeader(IntegrationMessageHeaderAccessor.SEQUENCE_NUMBER, 42)
				.setHeader(IntegrationMessageHeaderAccessor.SEQUENCE_SIZE, 43).build();
		output.send(message2);
		output.send(new GenericMessage<>(1));
		output.send(new GenericMessage<>(0));

		assertThat(receiveLatch.await(20, TimeUnit.SECONDS)).isTrue();

		assertThat(results).extracting("payload").containsExactlyInAnyOrder(0, 1, 2);

		Condition<Message<?>> correlationHeadersForPayload2 = new Condition<Message<?>>() {

			@Override
			public boolean matches(Message<?> value) {
				IntegrationMessageHeaderAccessor accessor = new IntegrationMessageHeaderAccessor(value);
				return "foo".equals(accessor.getCorrelationId()) && 42 == accessor.getSequenceNumber()
						&& 43 == accessor.getSequenceSize();
			}

		};

		Condition<Message<?>> payloadIs2 = new Condition<Message<?>>() {

			@Override
			public boolean matches(Message<?> value) {
				return value.getPayload().equals(2);
			}

		};

		assertThat(results).filteredOn(payloadIs2).areExactly(1, correlationHeadersForPayload2);

		input0Binding.unbind();
		input1Binding.unbind();
		input2Binding.unbind();
		outputBinding.unbind();
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
	protected KinesisTestBinder getBinder() throws Exception {
		if (this.testBinder == null) {
			this.testBinder = new KinesisTestBinder(localKinesisResource.getResource(),
					new KinesisBinderConfigurationProperties());
			this.timeoutMultiplier = 20;
		}
		return this.testBinder;
	}

	@Override
	protected ExtendedConsumerProperties<KinesisConsumerProperties> createConsumerProperties() {
		ExtendedConsumerProperties<KinesisConsumerProperties> kafkaConsumerProperties =
				new ExtendedConsumerProperties<>(new KinesisConsumerProperties());
		// set the default values that would normally be propagated by Spring Cloud Stream
		kafkaConsumerProperties.setInstanceCount(1);
		kafkaConsumerProperties.setInstanceIndex(0);
		return kafkaConsumerProperties;
	}

	@Override
	protected ExtendedProducerProperties<KinesisProducerProperties> createProducerProperties() {
		ExtendedProducerProperties<KinesisProducerProperties> producerProperties =
				new ExtendedProducerProperties<>(new KinesisProducerProperties());
		producerProperties.setPartitionKeyExpression(new LiteralExpression("1"));
		producerProperties.getExtension().setSync(true);
		return producerProperties;
	}

	@Override
	public Spy spyOn(String name) {
		throw new UnsupportedOperationException("'spyOn' is not used by Kinesis tests");
	}

}

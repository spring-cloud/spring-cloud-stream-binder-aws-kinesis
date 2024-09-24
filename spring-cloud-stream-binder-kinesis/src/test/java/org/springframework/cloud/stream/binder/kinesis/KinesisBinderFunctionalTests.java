/*
 * Copyright 2021-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.inbound.kinesis.KinesisShardOffset;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.support.json.JacksonJsonUtils;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Artem Bilan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.cloud.stream.bindings.eventConsumerBatchProcessingWithHeaders-in-0.consumer.multiplex=true",
				"spring.cloud.stream.bindings.eventConsumerBatchProcessingWithHeaders-in-0.destination=some_other_stream, " + KinesisBinderFunctionalTests.KINESIS_STREAM,
				"spring.cloud.stream.kinesis.bindings.eventConsumerBatchProcessingWithHeaders-in-0.consumer.idleBetweenPolls = 1",
				"spring.cloud.stream.kinesis.bindings.eventConsumerBatchProcessingWithHeaders-in-0.consumer.listenerMode = batch",
				"spring.cloud.stream.kinesis.bindings.eventConsumerBatchProcessingWithHeaders-in-0.consumer.checkpointMode = manual",
				"spring.cloud.stream.bindings.eventConsumerBatchProcessingWithHeaders-in-0.consumer.useNativeDecoding = true",
				"spring.cloud.stream.kinesis.binder.headers = event.eventType",
				"spring.cloud.stream.kinesis.binder.autoAddShards = true"})
@DirtiesContext
@DisabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
public class KinesisBinderFunctionalTests implements LocalstackContainerTest {

	static final String KINESIS_STREAM = "test_stream";

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CountDownLatch messageBarrier;

	@Autowired
	private AtomicReference<Message<List<?>>> messageHolder;

	@Autowired
	private BindingService bindingService;

	@SuppressWarnings("unchecked")
	@Test
	void testKinesisFunction() throws JsonProcessingException, InterruptedException {
		PutRecordsRequest.Builder putRecordsRequest =
				PutRecordsRequest.builder()
						.streamName(KINESIS_STREAM);

		List<PutRecordsRequestEntry> putRecordsRequestEntryList = new ArrayList<>();

		for (int i = 0; i < 10; i++) {
			Message<String> eventMessages =
					MessageBuilder.withPayload("Message" + i)
							.setHeader("event.eventType", "createEvent")
							.build();
			PutRecordsRequestEntry putRecordsRequestEntry =
					PutRecordsRequestEntry.builder()
							.partitionKey("1")
							.data(SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(eventMessages)))
							.build();
			putRecordsRequestEntryList.add(putRecordsRequestEntry);
		}
		putRecordsRequest.records(putRecordsRequestEntryList);

		LocalstackContainerTest.kinesisClient().putRecords(putRecordsRequest.build());

		assertThat(this.messageBarrier.await(60, TimeUnit.SECONDS)).isTrue();

		Message<List<?>> message = this.messageHolder.get();
		assertThat(message.getHeaders())
				.containsKeys(AwsHeaders.CHECKPOINTER,
						AwsHeaders.SHARD,
						AwsHeaders.RECEIVED_STREAM)
				.doesNotContainKeys(AwsHeaders.STREAM, AwsHeaders.PARTITION_KEY);

		List<?> payload = message.getPayload();
		assertThat(payload).hasSize(10);

		Object item = payload.get(0);

		assertThat(item).isInstanceOf(GenericMessage.class);

		Message<?> messageFromBatch = (Message<?>) item;

		assertThat(messageFromBatch.getPayload()).isEqualTo("Message0");
		assertThat(messageFromBatch.getHeaders())
				.containsEntry("event.eventType", "createEvent");

		List<Binding<?>> consumerBindings =
				this.bindingService.getConsumerBindings("eventConsumerBatchProcessingWithHeaders-in-0");

		assertThat(consumerBindings).hasSize(1);

		Binding<?> binding = consumerBindings.get(0);

		KinesisMessageDrivenChannelAdapter kinesisMessageDrivenChannelAdapter =
				TestUtils.getPropertyValue(binding, "lifecycle", KinesisMessageDrivenChannelAdapter.class);

		Map<KinesisShardOffset, ?> shardConsumers =
				TestUtils.getPropertyValue(kinesisMessageDrivenChannelAdapter, "shardConsumers", Map.class);
		assertThat(shardConsumers)
				.hasSize(2)
				.hasKeySatisfying(keySatisfyingCondition(KINESIS_STREAM))
				.hasKeySatisfying(keySatisfyingCondition("some_other_stream"));
	}

	private static Condition<KinesisShardOffset> keySatisfyingCondition(String streamName) {
		return new Condition<>() {

			@Override
			public boolean matches(KinesisShardOffset value) {
				return value.getStream().equals(streamName);
			}

		};
	}

	@Configuration
	@EnableAutoConfiguration
	static class TestConfiguration {

		@Bean
		public LockRegistry lockRegistry() {
			return new DefaultLockRegistry();
		}

		@Bean
		public ConcurrentMetadataStore checkpointStore() {
			return new SimpleMetadataStore();
		}

		@Bean
		public ObjectMapper objectMapper() {
			return JacksonJsonUtils.messagingAwareMapper();
		}

		@Bean
		public AtomicReference<Message<List<Message<?>>>> messageHolder() {
			return new AtomicReference<>();
		}

		@Bean
		public CountDownLatch messageBarrier() {
			return new CountDownLatch(1);
		}

		@Bean
		public Consumer<Message<List<Message<?>>>> eventConsumerBatchProcessingWithHeaders() {
			return eventMessages -> {
				messageHolder().set(eventMessages);
				messageBarrier().countDown();
			};
		}

	}

}

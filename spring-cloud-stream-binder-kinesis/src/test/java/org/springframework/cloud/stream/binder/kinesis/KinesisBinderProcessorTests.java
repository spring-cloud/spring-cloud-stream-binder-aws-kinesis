/*
 * Copyright 2016-2019 the original author or authors.
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

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import cloud.localstack.docker.LocalstackDockerExtension;
import cloud.localstack.docker.annotation.LocalstackDockerProperties;
import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.MonoProcessor;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.aws.autoconfigure.context.ContextResourceLoaderAutoConfiguration;
import org.springframework.cloud.aws.autoconfigure.context.ContextStackAutoConfiguration;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.binder.EmbeddedHeaderUtils;
import org.springframework.cloud.stream.binder.MessageValues;
import org.springframework.cloud.stream.config.ProducerMessageHandlerCustomizer;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.annotation.Transformer;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.outbound.KinesisMessageHandler;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests for the processor SCSt application against local Kinesis and DynamoDB.
 *
 * @author Artem Bilan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {"spring.cloud.stream.bindings.input.group = " + KinesisBinderProcessorTests.CONSUMER_GROUP,
				"spring.cloud.stream.bindings." + KinesisBinderProcessorTests.TestSource.TO_PROCESSOR_OUTPUT
						+ ".destination = " + Processor.INPUT,
				"spring.cloud.stream.kinesis.bindings.input.consumer.idleBetweenPolls = 1",
				"spring.cloud.stream.kinesis.binder.headers = foo",
				"spring.cloud.stream.kinesis.binder.checkpoint.table = checkpointTable",
				"spring.cloud.stream.kinesis.binder.locks.table = lockTable", "cloud.aws.region.static=eu-west-2"})
@EnabledIfEnvironmentVariable(named = EnvironmentHostNameResolver.DOCKER_HOST_NAME, matches = ".+")
@ExtendWith(LocalstackDockerExtension.class)
@LocalstackDockerProperties(randomizePorts = true, hostNameResolver = EnvironmentHostNameResolver.class,
		services = {"kinesis", "dynamodb"})
@DirtiesContext
class KinesisBinderProcessorTests {

	static final String CONSUMER_GROUP = "testGroup";

	private static AmazonKinesisAsync AMAZON_KINESIS;

	private static AmazonDynamoDBAsync DYNAMO_DB;

	@Autowired
	private TestSource testSource;

	@Autowired
	private PollableChannel fromProcessorChannel;

	@Autowired
	private SubscribableChannel errorChannel;

	@Autowired
	@Qualifier(Processor.INPUT + "." + CONSUMER_GROUP + ".errors")
	private SubscribableChannel consumerErrorChannel;

	@Autowired
	private ProcessorConfiguration config;

	@Autowired
	private HealthEndpoint healthEndpoint;

	@BeforeAll
	static void setup() {
		AMAZON_KINESIS = ExtendedDockerTestUtils.getClientKinesisAsync();
		DYNAMO_DB = ExtendedDockerTestUtils.getClientDynamoDbAsync();
	}

	@Test
	@SuppressWarnings("unchecked")
	void testProcessorWithKinesisBinder() throws Exception {
		assertThat(this.healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
		assertThat(this.healthEndpoint.healthForPath("binders").getStatus()).isEqualTo(Status.UP);

		Message<String> testMessage = MessageBuilder.withPayload("foo").setHeader("foo", "BAR").build();
		this.testSource.toProcessorOutput().send(testMessage);

		Message<byte[]> receive = (Message<byte[]>) this.fromProcessorChannel.receive(10_000);
		assertThat(receive).isNotNull();

		MessageValues messageValues = EmbeddedHeaderUtils.extractHeaders(receive, true);

		assertThat(messageValues.getPayload()).isEqualTo("FOO".getBytes());

		assertThat(messageValues.getHeaders().get(MessageHeaders.CONTENT_TYPE))
				.isEqualTo(MediaType.APPLICATION_JSON_VALUE);

		assertThat(messageValues.getHeaders().get(AwsHeaders.RECEIVED_STREAM)).isEqualTo(Processor.OUTPUT);
		assertThat(messageValues.getHeaders().get("foo")).isEqualTo("BAR");
		assertThat(messageValues.getHeaders()).containsKey(IntegrationMessageHeaderAccessor.SOURCE_DATA);

		BlockingQueue<Message<?>> errorMessages = new LinkedBlockingQueue<>();

		this.errorChannel.subscribe(errorMessages::add);
		this.consumerErrorChannel.subscribe(errorMessages::add);

		this.testSource.toProcessorOutput().send(new GenericMessage<>("junk"));

		Message<?> errorMessage1 = errorMessages.poll(10, TimeUnit.SECONDS);
		Message<?> errorMessage2 = errorMessages.poll(10, TimeUnit.SECONDS);
		assertThat(errorMessage1).isNotNull();
		assertThat(errorMessage2).isNotNull();
		assertThat(errorMessage1).isSameAs(errorMessage2);
		assertThat(errorMessages).isEmpty();

		PutRecordResult putRecordResult = this.config.resultMonoProcessor.block(Duration.ofSeconds(10));
		assertThat(putRecordResult).isNotNull().extracting(PutRecordResult::getSequenceNumber).isNotNull();
	}

	/**
	 * Test configuration.
	 */
	@EnableBinding({Processor.class, TestSource.class})
	@EnableAutoConfiguration(
			exclude = {ContextResourceLoaderAutoConfiguration.class, ContextStackAutoConfiguration.class})
	static class ProcessorConfiguration {

		private MonoProcessor<PutRecordResult> resultMonoProcessor = MonoProcessor.create();

		@Bean(destroyMethod = "")
		public AmazonDynamoDBAsync dynamoDB() {
			return DYNAMO_DB;
		}

		@Bean(destroyMethod = "")
		public AmazonKinesisAsync amazonKinesis() {
			return AMAZON_KINESIS;
		}

		@Transformer(inputChannel = Processor.INPUT, outputChannel = Processor.OUTPUT)
		public String transform(Message<String> message) {
			String payload = message.getPayload();
			if (!"junk".equals(payload)) {
				return payload.toUpperCase();
			}
			else {
				throw new IllegalStateException("Invalid payload: " + payload);
			}
		}

		@Bean(name = Processor.INPUT + "." + CONSUMER_GROUP + ".errors")
		public SubscribableChannel consumerErrorChannel() {
			return new PublishSubscribeChannel();
		}

		@Bean
		public MessageProducer kinesisMessageDriverChannelAdapter() {
			KinesisMessageDrivenChannelAdapter kinesisMessageDrivenChannelAdapter = new KinesisMessageDrivenChannelAdapter(
					amazonKinesis(), Processor.OUTPUT);
			kinesisMessageDrivenChannelAdapter.setOutputChannel(fromProcessorChannel());
			kinesisMessageDrivenChannelAdapter.setConverter(null);
			kinesisMessageDrivenChannelAdapter.setBindSourceRecord(true);

			DirectFieldAccessor dfa = new DirectFieldAccessor(kinesisMessageDrivenChannelAdapter);
			dfa.setPropertyValue("describeStreamBackoff", 10);
			dfa.setPropertyValue("consumerBackoff", 10);
			dfa.setPropertyValue("idleBetweenPolls", 1);

			return kinesisMessageDrivenChannelAdapter;
		}

		@Bean
		public PollableChannel fromProcessorChannel() {
			return new QueueChannel();
		}

		@Bean
		public ProducerMessageHandlerCustomizer<KinesisMessageHandler> kinesisMessageHandlerCustomizer() {
			return (handler, destinationName) -> handler.setAsyncHandler(asyncHandler());
		}

		@Bean
		public AsyncHandler<PutRecordRequest, PutRecordResult> asyncHandler() {
			return new AsyncHandler<PutRecordRequest, PutRecordResult>() {

				@Override
				public void onError(Exception exception) {

				}

				@Override
				public void onSuccess(PutRecordRequest request, PutRecordResult putRecordsResult) {
					ProcessorConfiguration.this.resultMonoProcessor.onNext(putRecordsResult);
					ProcessorConfiguration.this.resultMonoProcessor.onComplete();
				}

			};
		}

	}

	/**
	 * The SCSt contract for testing.
	 */
	interface TestSource {

		String TO_PROCESSOR_OUTPUT = "toProcessorOutput";

		@Output(TO_PROCESSOR_OUTPUT)
		MessageChannel toProcessorOutput();

	}

}

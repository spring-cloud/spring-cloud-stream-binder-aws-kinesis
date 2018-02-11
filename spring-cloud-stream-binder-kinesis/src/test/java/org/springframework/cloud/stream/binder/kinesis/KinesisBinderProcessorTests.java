/*
 * Copyright 2018 the original author or authors.
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

import com.amazonaws.services.kinesis.AmazonKinesisAsync;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.aws.autoconfigure.context.ContextResourceLoaderAutoConfiguration;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binder.EmbeddedHeaderUtils;
import org.springframework.cloud.stream.binder.MessageValues;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.annotation.Transformer;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.outbound.KinesisMessageHandler;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.metadata.MetadataStore;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Artem Bilan
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
public class KinesisBinderProcessorTests {

	@ClassRule
	public static LocalKinesisResource localKinesisResource = new LocalKinesisResource();

	@Autowired
	private MessageChannel toProcessorChannel;

	@Autowired
	private PollableChannel fromProcessorChannel;

	@Test
	@SuppressWarnings("unchecked")
	public void testProcessorWithKinesisBinder() throws Exception {
		this.toProcessorChannel.send(new GenericMessage<>("foo"));

		Message<byte[]> receive = (Message<byte[]>) this.fromProcessorChannel.receive(10_000);
		assertThat(receive).isNotNull();

		MessageValues messageValues = EmbeddedHeaderUtils.extractHeaders(receive, true);

		assertThat(messageValues.getPayload()).isEqualTo("FOO".getBytes());

		assertThat(messageValues.getHeaders().get(MessageHeaders.CONTENT_TYPE))
				.isEqualTo(MediaType.APPLICATION_JSON_VALUE);

		assertThat(messageValues.getHeaders().get(AwsHeaders.RECEIVED_STREAM)).isEqualTo(Processor.OUTPUT);
	}

	@EnableBinding(Processor.class)
	@EnableAutoConfiguration(exclude = ContextResourceLoaderAutoConfiguration.class)
	public static class ProcessorConfiguration {

		@Bean
		public AmazonKinesisAsync amazonKinesis() {
			return localKinesisResource.getResource();
		}

		@Bean
		public MetadataStore kinesisCheckpointStore() {
			return new SimpleMetadataStore();
		}

		@Transformer(inputChannel = Processor.INPUT, outputChannel = Processor.OUTPUT)
		public String transform(Message<String> message) {
			return message.getPayload().toUpperCase();
		}

		@Bean
		@ServiceActivator(inputChannel = "toProcessorChannel")
		public MessageHandler kinesisMessageHandler() {
			KinesisMessageHandler kinesisMessageHandler = new KinesisMessageHandler(amazonKinesis());
			kinesisMessageHandler.setStream(Processor.INPUT);
			kinesisMessageHandler.setConverter(source -> source.toString().getBytes());
			kinesisMessageHandler.setPartitionKey("foo");
			return kinesisMessageHandler;
		}

		@Bean
		public MessageProducer kinesisMessageDriverChannelAdapter() {
			KinesisMessageDrivenChannelAdapter kinesisMessageDrivenChannelAdapter = new KinesisMessageDrivenChannelAdapter(
					amazonKinesis(), Processor.OUTPUT);
			kinesisMessageDrivenChannelAdapter.setOutputChannel(fromProcessorChannel());
			kinesisMessageDrivenChannelAdapter.setConverter(null);
			return kinesisMessageDrivenChannelAdapter;
		}

		@Bean
		public PollableChannel fromProcessorChannel() {
			return new QueueChannel();
		}

	}

}

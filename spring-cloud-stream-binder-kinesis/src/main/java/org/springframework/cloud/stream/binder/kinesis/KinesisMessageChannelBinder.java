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

import com.amazonaws.services.kinesis.AmazonKinesisAsync;

import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.ExtendedPropertiesBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.integration.aws.inbound.kinesis.CheckpointMode;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.inbound.kinesis.KinesisShardOffset;
import org.springframework.integration.aws.inbound.kinesis.ListenerMode;
import org.springframework.integration.aws.outbound.KinesisMessageHandler;
import org.springframework.integration.core.MessageProducer;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.util.Assert;

/**
 *
 * @author Peter Oates
 * @author Artem Bilan
 */
public class KinesisMessageChannelBinder extends
		AbstractMessageChannelBinder<ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>, KinesisStreamProvisioner>
		implements ExtendedPropertiesBinder<MessageChannel, KinesisConsumerProperties, KinesisProducerProperties> {

	// not using currently but expect to include global properties here if
	// required
	private final KinesisBinderConfigurationProperties configurationProperties;

	// not currently utilising any extended properties - not sure if I will need
	// these
	private KinesisExtendedBindingProperties extendedBindingProperties = new KinesisExtendedBindingProperties();

	private final AmazonKinesisAsync amazonKinesis;

	public KinesisMessageChannelBinder(AmazonKinesisAsync amazonKinesis,
			KinesisBinderConfigurationProperties configurationProperties,
			KinesisStreamProvisioner provisioningProvider) {
		super(false, null, provisioningProvider);
		Assert.notNull(amazonKinesis, "'amazonKinesis' must not be null");
		Assert.notNull(configurationProperties, "'configurationProperties' must not be null");
		this.configurationProperties = configurationProperties;
		this.amazonKinesis = amazonKinesis;
	}

	@Override
	public KinesisConsumerProperties getExtendedConsumerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedConsumerProperties(channelName);
	}

	@Override
	public KinesisProducerProperties getExtendedProducerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedProducerProperties(channelName);
	}

	// below are the main methods to implement - these will create the message
	// handlers used by the application
	// to put and consume messages
	@Override
	protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
			ExtendedProducerProperties<KinesisProducerProperties> producerProperties) throws Exception {

		KinesisMessageHandler kinesisMessageHandler = new KinesisMessageHandler(this.amazonKinesis);
		kinesisMessageHandler.setSync(producerProperties.getExtension().isSync());
		kinesisMessageHandler.setStream(destination.getName());
		// tidy up partition key
		kinesisMessageHandler.setPartitionKey("name");
		kinesisMessageHandler.setBeanFactory(getBeanFactory());

		return kinesisMessageHandler;
	}

	@Override
	protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) throws Exception {

		KinesisMessageDrivenChannelAdapter adapter =
				new KinesisMessageDrivenChannelAdapter(this.amazonKinesis, destination.getName());

		// explicitly setting the offset - LATEST is the default but added here
		// so can be configured later
		adapter.setStreamInitialSequence(KinesisShardOffset.latest());
		// need to move these properties to the appropriate properties class
		adapter.setCheckpointMode(CheckpointMode.record);
		adapter.setListenerMode(ListenerMode.record);
		adapter.setStartTimeout(properties.getExtension().getConnectTimeout());
		// Let the start timeout interrupt startup instead of eating the ResourceNotFoundException
		adapter.setDescribeStreamRetries(properties.getExtension().getConnectTimeout() + 10);
		adapter.setConcurrency(10);

		// Deffer byte[] conversion to the ReceivingHandler
		adapter.setConverter(null);

		return adapter;
	}

}

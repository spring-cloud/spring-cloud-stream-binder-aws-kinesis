package org.springframework.cloud.stream.binder.kinesis;

import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.ExtendedPropertiesBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisTopicProvisioner;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;

import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClientBuilder;

import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.metadata.MetadataStore;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.PollableChannel;
import org.springframework.integration.aws.inbound.kinesis.CheckpointMode;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.inbound.kinesis.KinesisShardOffset;
import org.springframework.integration.aws.inbound.kinesis.ListenerMode;
import org.springframework.integration.aws.outbound.KinesisMessageHandler;
import org.springframework.integration.channel.QueueChannel;

/**
 * 
 * @author Peter Oates
 */
public class KinesisMessageChannelBinder extends
		AbstractMessageChannelBinder<ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>, KinesisTopicProvisioner>
		implements ExtendedPropertiesBinder<MessageChannel, KinesisConsumerProperties, KinesisProducerProperties> {

	// not using currently but expect to include global properties here if
	// required
	private final KinesisBinderConfigurationProperties configurationProperties;

	// not currently utilising any extended properties - not sure if I will need
	// these
	private KinesisExtendedBindingProperties extendedBindingProperties = new KinesisExtendedBindingProperties();

	private AmazonKinesisAsync amazonKinesis;

	public KinesisMessageChannelBinder(KinesisBinderConfigurationProperties configurationProperties,
			KinesisTopicProvisioner provisioningProvider) {
		super(false, null, provisioningProvider);
		this.configurationProperties = configurationProperties;

		this.amazonKinesis = AmazonKinesisAsyncClientBuilder.defaultClient();

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

		KinesisMessageHandler kinesisMessageHandler = new KinesisMessageHandler(amazonKinesis);

		kinesisMessageHandler.setStream(destination.getName());
		// tidy up partition key
		kinesisMessageHandler.setPartitionKey("name");
		kinesisMessageHandler.setBeanFactory(this.getBeanFactory());

		return kinesisMessageHandler;
	}

	@Override
	protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) throws Exception {

		KinesisMessageDrivenChannelAdapter adapter = new KinesisMessageDrivenChannelAdapter(amazonKinesis,
				destination.getName());

		adapter.setOutputChannel(kinesisChannel());
		adapter.setCheckpointStore(checkpointStore());

		// explicitly setting the offset - LATEST is the default but added here
		// so can be configured later
		adapter.setStreamInitialSequence(KinesisShardOffset.latest());
		// need to move these properties to the appropriate properties class
		adapter.setCheckpointMode(CheckpointMode.record);
		adapter.setListenerMode(ListenerMode.record);
		adapter.setStartTimeout(10000);
		adapter.setDescribeStreamRetries(1);
		adapter.setConcurrency(10);

		return adapter;
	}

	public PollableChannel kinesisChannel() {
		return new QueueChannel();
	}

	public MetadataStore checkpointStore() {
		SimpleMetadataStore simpleMetadataStore = new SimpleMetadataStore();
		return simpleMetadataStore;
	}

}

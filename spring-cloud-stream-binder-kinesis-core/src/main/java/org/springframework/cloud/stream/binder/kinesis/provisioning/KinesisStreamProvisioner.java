package org.springframework.cloud.stream.binder.kinesis.provisioning;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.cloud.stream.provisioning.ProvisioningProvider;

/**
 * 
 * @author Peter Oates
 *
 */
public class KinesisStreamProvisioner implements ProvisioningProvider<ExtendedConsumerProperties<KinesisConsumerProperties>,
ExtendedProducerProperties<KinesisProducerProperties>>, InitializingBean {

	private final static Log logger = LogFactory.getLog(KinesisStreamProvisioner.class);
	
	private final KinesisBinderConfigurationProperties configurationProperties;
	
	public KinesisStreamProvisioner(KinesisBinderConfigurationProperties kinesisBinderconfigurationProperties) {
		this.configurationProperties = kinesisBinderconfigurationProperties;	
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public ProducerDestination provisionProducerDestination(String name,
			ExtendedProducerProperties<KinesisProducerProperties> properties) throws ProvisioningException {
		
		KinesisProducerDestination producer = new KinesisProducerDestination(name);
		if (logger.isInfoEnabled()) {
			logger.info(String.format("Producer: " + producer));
		}
		
		
		return producer;
	}

	@Override
	public ConsumerDestination provisionConsumerDestination(String name, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) throws ProvisioningException {
		
		KinesisConsumerDestination consumer = new KinesisConsumerDestination(name);
		if (logger.isInfoEnabled()) {
			logger.info(String.format("Consumer: " + consumer));
		}
		return consumer;
	}

	
	
	private static final class KinesisProducerDestination implements ProducerDestination {

		private final String streamName;

		private final int shards;

		KinesisProducerDestination(String streamName) {
			this(streamName, 0);
		}

		KinesisProducerDestination(String streamName, Integer shards) {
			this.streamName = streamName;
			this.shards = shards;
		}

		@Override
		public String getName() {
			return this.streamName;
		}

		@Override
		public String getNameForPartition(int shard) {
			return this.streamName;
		}

		@Override
		public String toString() {
			return "KinesisProducerDestination{" +
					"streamName='" + this.streamName + '\'' +
					", shards=" + this.shards +
					'}';
		}
	}
	
	
	private static final class KinesisConsumerDestination implements ConsumerDestination {

		private final String streamName;

		private final int shards;

		private final String dlqName;

		KinesisConsumerDestination(String streamName) {
			this(streamName, 0, null);
		}

		KinesisConsumerDestination(String streamName, int shards) {
			this(streamName, shards, null);
		}

		KinesisConsumerDestination(String streamName, Integer shards, String dlqName) {
			this.streamName = streamName;
			this.shards = shards;
			this.dlqName = dlqName;
		}

		@Override
		public String getName() {
			return this.streamName;
		}

		@Override
		public String toString() {
			return "KinesisConsumerDestination{" +
					"streamName='" + streamName + '\'' +
					", shards=" + shards +
					", dlqName='" + dlqName + '\'' +
					'}';
		}

	}
	
	
}

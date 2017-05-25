package org.springframework.cloud.stream.binder.kinesis.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class KinesisTopicProvisioner implements ProvisioningProvider<ExtendedConsumerProperties<KinesisConsumerProperties>,
ExtendedProducerProperties<KinesisProducerProperties>>, InitializingBean {

	private final static Logger log = LoggerFactory.getLogger(KinesisTopicProvisioner.class);
	
	private final KinesisBinderConfigurationProperties configurationProperties;
	
	public KinesisTopicProvisioner(KinesisBinderConfigurationProperties kinesisBinderconfigurationProperties) {
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
		log.info(String.format("Producer: %s", producer));
		
		
		return producer;
	}

	@Override
	public ConsumerDestination provisionConsumerDestination(String name, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) throws ProvisioningException {
		
		KinesisConsumerDestination consumer = new KinesisConsumerDestination(name);
		log.info(String.format("Consumer: %s", consumer));
		
		return consumer;
	}

	
	
	private static final class KinesisProducerDestination implements ProducerDestination {

		private final String producerDestinationName;

		private final int partitions;

		KinesisProducerDestination(String destinationName) {
			this(destinationName, 0);
		}

		KinesisProducerDestination(String destinationName, Integer partitions) {
			this.producerDestinationName = destinationName;
			this.partitions = partitions;
		}

		@Override
		public String getName() {
			return producerDestinationName;
		}

		@Override
		public String getNameForPartition(int partition) {
			return producerDestinationName;
		}

		@Override
		public String toString() {
			return "KinesisProducerDestination{" +
					"producerDestinationName='" + producerDestinationName + '\'' +
					", partitions=" + partitions +
					'}';
		}
	}
	
	
	private static final class KinesisConsumerDestination implements ConsumerDestination {

		private final String consumerDestinationName;

		private final int partitions;

		private final String dlqName;

		KinesisConsumerDestination(String consumerDestinationName) {
			this(consumerDestinationName, 0, null);
		}

		KinesisConsumerDestination(String consumerDestinationName, int partitions) {
			this(consumerDestinationName, partitions, null);
		}

		KinesisConsumerDestination(String consumerDestinationName, Integer partitions, String dlqName) {
			this.consumerDestinationName = consumerDestinationName;
			this.partitions = partitions;
			this.dlqName = dlqName;
		}

		@Override
		public String getName() {
			return this.consumerDestinationName;
		}

		@Override
		public String toString() {
			return "KinesisConsumerDestination{" +
					"consumerDestinationName='" + consumerDestinationName + '\'' +
					", partitions=" + partitions +
					", dlqName='" + dlqName + '\'' +
					'}';
		}

	}
	
	
}

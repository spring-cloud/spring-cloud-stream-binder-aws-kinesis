package org.springframework.cloud.stream.binder.kinesis.properties;

/**
 * @author Peter Oates
 */
public class KinesisBindingProperties {

	private KinesisConsumerProperties consumer = new KinesisConsumerProperties();

	private KinesisProducerProperties producer = new KinesisProducerProperties();

	public KinesisConsumerProperties getConsumer() {
		return consumer;
	}

	public void setConsumer(KinesisConsumerProperties consumer) {
		this.consumer = consumer;
	}

	public KinesisProducerProperties getProducer() {
		return producer;
	}

	public void setProducer(KinesisProducerProperties producer) {
		this.producer = producer;
	}
}

package org.springframework.cloud.stream.binder.kinesis.properties;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.stream.binder.ExtendedBindingProperties;

/**
 * 
 * @author Peter Oates
 *
 */
@ConfigurationProperties("spring.cloud.stream.kinesis")
public class KinesisExtendedBindingProperties 
		implements ExtendedBindingProperties<KinesisConsumerProperties, KinesisProducerProperties> {

	private Map<String, KinesisBindingProperties> bindings = new HashMap<>();

	public Map<String, KinesisBindingProperties> getBindings() {
		return this.bindings;
	}

	public void setBindings(Map<String, KinesisBindingProperties> bindings) {
		this.bindings = bindings;
	}
	
	@Override
	public KinesisConsumerProperties getExtendedConsumerProperties(String channelName) {
		if (this.bindings.containsKey(channelName) && this.bindings.get(channelName).getConsumer() != null) {
			return this.bindings.get(channelName).getConsumer();
		}
		else {
			return new KinesisConsumerProperties();
		}
	}

	@Override
	public KinesisProducerProperties getExtendedProducerProperties(String channelName) {
		if (this.bindings.containsKey(channelName) && this.bindings.get(channelName).getProducer() != null) {
			return this.bindings.get(channelName).getProducer();
		}
		else {
			return new KinesisProducerProperties();
		}
	}

}

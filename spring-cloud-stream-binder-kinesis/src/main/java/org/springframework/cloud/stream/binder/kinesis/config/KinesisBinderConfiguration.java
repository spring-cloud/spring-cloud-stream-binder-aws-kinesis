package org.springframework.cloud.stream.binder.kinesis.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.kinesis.KinesisMessageChannelBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisTopicProvisioner;
import org.springframework.cloud.stream.config.codec.kryo.KryoCodecAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.codec.Codec;

/**
 * 
 * @author Peter Oates
 *
 */
@Configuration
@ConditionalOnMissingBean(Binder.class)
@Import({KryoCodecAutoConfiguration.class, PropertyPlaceholderAutoConfiguration.class})
@EnableConfigurationProperties({KinesisBinderConfigurationProperties.class, KinesisExtendedBindingProperties.class})
public class KinesisBinderConfiguration {

	
	protected static final Log logger = LogFactory.getLog(KinesisBinderConfiguration.class);

	@Autowired
	private Codec codec;

	@Autowired
	private KinesisBinderConfigurationProperties configurationProperties;


	@Bean
	KinesisTopicProvisioner provisioningProvider() {
		return new KinesisTopicProvisioner(this.configurationProperties);
	}

	@Bean
	KinesisMessageChannelBinder kinesisMessageChannelBinder() {
		
		KinesisMessageChannelBinder kinesisMessageChannelBinder = new KinesisMessageChannelBinder(
				this.configurationProperties, provisioningProvider());
		kinesisMessageChannelBinder.setCodec(this.codec);
		
		return kinesisMessageChannelBinder;
	}


	
}

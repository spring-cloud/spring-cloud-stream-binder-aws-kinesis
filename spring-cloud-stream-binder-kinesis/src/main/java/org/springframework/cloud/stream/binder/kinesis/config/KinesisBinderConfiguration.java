package org.springframework.cloud.stream.binder.kinesis.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.KinesisMessageChannelBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.codec.Codec;

/**
 * 
 * @author Peter Oates
 *
 */
//@Configuration
//@ConditionalOnMissingBean(Binder.class)
//@Import({KryoCodecAutoConfiguration.class, PropertyPlaceholderAutoConfiguration.class})
@EnableConfigurationProperties({KinesisBinderConfigurationProperties.class, KinesisExtendedBindingProperties.class})
public class KinesisBinderConfiguration {

	@Autowired
	private Codec codec;

	@Autowired
	private KinesisBinderConfigurationProperties configurationProperties;


	@Bean
	KinesisStreamProvisioner provisioningProvider() {
		return new KinesisStreamProvisioner(this.configurationProperties);
	}

	@Bean
	KinesisMessageChannelBinder kinesisMessageChannelBinder() {
		
		KinesisMessageChannelBinder kinesisMessageChannelBinder = new KinesisMessageChannelBinder(
				this.configurationProperties, provisioningProvider());
		kinesisMessageChannelBinder.setCodec(this.codec);
		
		return kinesisMessageChannelBinder;
	}


	
}

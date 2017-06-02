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

import org.springframework.cloud.stream.binder.AbstractTestBinder;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.integration.codec.kryo.PojoCodec;

import com.amazonaws.services.kinesis.AmazonKinesisAsync;

/**
 * @author Artem Bilan
 *
 */
public class KinesisTestBinder
		extends AbstractTestBinder<KinesisMessageChannelBinder, ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>> {

	public KinesisTestBinder(AmazonKinesisAsync amazonKinesis,
			KinesisBinderConfigurationProperties kinesisBinderConfigurationProperties) {
		KinesisStreamProvisioner provisioningProvider =
				new KinesisStreamProvisioner(amazonKinesis, kinesisBinderConfigurationProperties);

		KinesisMessageChannelBinder binder =
				new KinesisMessageChannelBinder(amazonKinesis, kinesisBinderConfigurationProperties, provisioningProvider);

		binder.setCodec(new PojoCodec());
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();
		binder.setApplicationContext(context);

		setBinder(binder);
	}

	@Override
	public void cleanup() {

	}

}

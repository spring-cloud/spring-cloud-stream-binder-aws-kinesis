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

package org.springframework.cloud.stream.binder.kinesis.provisioning;

import com.amazonaws.services.kinesis.AmazonKinesis;

import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.cloud.stream.provisioning.ProvisioningProvider;
import org.springframework.util.Assert;

/**
 * The {@link ProvisioningProvider} implementation for Amazon Kinesis.
 *
 * @author Peter Oates
 * @author Artem Bilan
 *
 */
public class KinesisStreamProvisioner
		implements
		ProvisioningProvider<ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>> {

	private final AmazonKinesis amazonKinesis;

	private final KinesisBinderConfigurationProperties configurationProperties;

	public KinesisStreamProvisioner(AmazonKinesis amazonKinesis,
			KinesisBinderConfigurationProperties kinesisBinderConfigurationProperties) {
		Assert.notNull(amazonKinesis, "'amazonKinesis' must not be null");
		Assert.notNull(kinesisBinderConfigurationProperties, "'kinesisBinderConfigurationProperties' must not be null");
		this.amazonKinesis = amazonKinesis;
		this.configurationProperties = kinesisBinderConfigurationProperties;
	}

	@Override
	public ProducerDestination provisionProducerDestination(String name,
			ExtendedProducerProperties<KinesisProducerProperties> properties) throws ProvisioningException {

		KinesisProducerDestination producer = new KinesisProducerDestination(name);

		return producer;
	}

	@Override
	public ConsumerDestination provisionConsumerDestination(String name, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) throws ProvisioningException {

		KinesisConsumerDestination consumer = new KinesisConsumerDestination(name);

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

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

import org.junit.ClassRule;
import org.junit.Test;

import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.PartitionCapableBinderTests;
import org.springframework.cloud.stream.binder.Spy;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * @author Artem Bilan
 * @author Jacob Severson
 *
 */
public class KinesisBinderTests
		extends PartitionCapableBinderTests<KinesisTestBinder, ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>> {

	private final String CLASS_UNDER_TEST_NAME = KinesisBinderTests.class.getSimpleName();

	@ClassRule
	public static LocalKinesisResource localKinesisResource = new LocalKinesisResource();

	@Test
	@SuppressWarnings("unchecked")
	public void testAutoCreateStreamForNonExistingStream() throws Exception {
		Binder binder = getBinder();
		RetryTemplate metatadataRetrievalRetryOperations = new RetryTemplate();
		metatadataRetrievalRetryOperations.setRetryPolicy(new SimpleRetryPolicy());
		FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
		backOffPolicy.setBackOffPeriod(1000);
		DirectChannel output = new DirectChannel();
		ExtendedConsumerProperties<KinesisConsumerProperties> consumerProperties = createConsumerProperties();
		String testTopicName = "nonexisting" + System.currentTimeMillis();
		Binding<?> binding = binder.bindConsumer(testTopicName, "test", output, consumerProperties);
		binding.unbind();
	}

	@Override
	protected boolean usesExplicitRouting() {
		return false;
	}

	@Override
	protected String getClassUnderTestName() {
		return CLASS_UNDER_TEST_NAME;
	}

	@Override
	protected KinesisTestBinder getBinder() throws Exception {
		if (this.testBinder == null) {
			this.testBinder =
					new KinesisTestBinder(localKinesisResource.getResource(),
							new KinesisBinderConfigurationProperties());
		}
		return this.testBinder;
	}

	@Override
	protected ExtendedConsumerProperties<KinesisConsumerProperties> createConsumerProperties() {
		final ExtendedConsumerProperties<KinesisConsumerProperties> kafkaConsumerProperties =
				new ExtendedConsumerProperties<>(new KinesisConsumerProperties());
		// set the default values that would normally be propagated by Spring Cloud Stream
		kafkaConsumerProperties.setInstanceCount(1);
		kafkaConsumerProperties.setInstanceIndex(0);
		return kafkaConsumerProperties;
	}

	@Override
	protected ExtendedProducerProperties<KinesisProducerProperties> createProducerProperties() {
		ExtendedProducerProperties<KinesisProducerProperties> producerProperties =
				new ExtendedProducerProperties<>(new KinesisProducerProperties());
		producerProperties.getExtension().setSync(true);
		return producerProperties;
	}

	@Override
	public Spy spyOn(String name) {
		throw new UnsupportedOperationException("'spyOn' is not used by Kinesis tests");
	}

}

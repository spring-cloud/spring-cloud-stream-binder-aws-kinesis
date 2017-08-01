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

import java.util.Collections;
import java.util.List;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.*;
import org.junit.Test;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * @author Jacob Severson
 */
public class KinesisStreamProvisionerTests {

	@Test
	public void testProvisionProducerSuccessfulWithExistingStream() {
		AmazonKinesis amazonKinesisMock = mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);
		ExtendedProducerProperties<KinesisProducerProperties> extendedProducerProperties
				= new ExtendedProducerProperties<>(new KinesisProducerProperties());
		String name = "test-stream";

		DescribeStreamResult describeStreamResult =
				describeStreamResultWithShards(Collections.singletonList(new Shard()));

		when(amazonKinesisMock.describeStream(name)).thenReturn(describeStreamResult);

		ProducerDestination destination = provisioner.provisionProducerDestination(name, extendedProducerProperties);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		assertThat(destination.getName(), is(name));
	}

	@Test
	public void testProvisionConsumerSuccessfulWithExistingStream() {
		AmazonKinesis amazonKinesisMock = mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);

		ExtendedConsumerProperties<KinesisConsumerProperties> extendedConsumerProperties
				= new ExtendedConsumerProperties<>(new KinesisConsumerProperties());

		String name = "test-stream";
		String group = "test-group";

		DescribeStreamResult describeStreamResult =
				describeStreamResultWithShards(Collections.singletonList(new Shard()));

		when(amazonKinesisMock.describeStream(name)).thenReturn(describeStreamResult);

		ConsumerDestination destination =
				provisioner.provisionConsumerDestination(name, group, extendedConsumerProperties);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		assertThat(destination.getName(), is(name));
	}

	@Test
	public void testProvisionProducerSuccessfulWithNewStream() {
		AmazonKinesis amazonKinesisMock = mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);
		ExtendedProducerProperties<KinesisProducerProperties> extendedProducerProperties
				= new ExtendedProducerProperties<>(new KinesisProducerProperties());
		String name = "test-stream";
		Integer shards = 1;

		when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));
		when(amazonKinesisMock.createStream(name, shards)).thenReturn(new CreateStreamResult());

		ProducerDestination destination = provisioner.provisionProducerDestination(name, extendedProducerProperties);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		verify(amazonKinesisMock, times(1)).createStream(name, shards);
		assertThat(destination.getName(), is(name));
	}

	@Test
	public void testProvisionConsumerSuccessfulWithNewStream() {
		AmazonKinesis amazonKinesisMock = mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);
		int instanceCount = 1;
		int concurrency = 1;

		ExtendedConsumerProperties<KinesisConsumerProperties> extendedConsumerProperties
				= new ExtendedConsumerProperties<>(new KinesisConsumerProperties());
		extendedConsumerProperties.setInstanceCount(instanceCount);
		extendedConsumerProperties.setConcurrency(concurrency);

		String name = "test-stream";
		String group = "test-group";

		when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));
		when(amazonKinesisMock.createStream(name, instanceCount * concurrency)).thenReturn(new CreateStreamResult());

		ConsumerDestination destination = provisioner.provisionConsumerDestination(name, group, extendedConsumerProperties);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		verify(amazonKinesisMock, times(1)).createStream(name, instanceCount * concurrency);
		assertThat(destination.getName(), is(name));
	}

	private static DescribeStreamResult describeStreamResultWithShards(List<Shard> shards) {
		return new DescribeStreamResult()
				.withStreamDescription(
						new StreamDescription()
								.withShards(shards)
				);
	}

}

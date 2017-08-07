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
import com.amazonaws.services.kinesis.model.CreateStreamResult;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.StreamDescription;
import org.hamcrest.core.Is;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;

/**
 * @author Jacob Severson
 */
public class KinesisStreamProvisionerTests {

	@Test
	public void testProvisionProducerSuccessfulWithExistingStream() {
		AmazonKinesis amazonKinesisMock = Mockito.mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);
		ExtendedProducerProperties<KinesisProducerProperties> extendedProducerProperties
				= new ExtendedProducerProperties<>(new KinesisProducerProperties());
		String name = "test-stream";

		DescribeStreamResult describeStreamResult =
				describeStreamResultWithShards(Collections.singletonList(new Shard()));

		Mockito.when(amazonKinesisMock.describeStream(name)).thenReturn(describeStreamResult);

		ProducerDestination destination = provisioner.provisionProducerDestination(name, extendedProducerProperties);

		Mockito.verify(amazonKinesisMock, Mockito.times(1)).describeStream(name);
		Assert.assertThat(destination.getName(), Is.is(name));
	}

	@Test
	public void testProvisionConsumerSuccessfulWithExistingStream() {
		AmazonKinesis amazonKinesisMock = Mockito.mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);

		ExtendedConsumerProperties<KinesisConsumerProperties> extendedConsumerProperties
				= new ExtendedConsumerProperties<>(new KinesisConsumerProperties());

		String name = "test-stream";
		String group = "test-group";

		DescribeStreamResult describeStreamResult =
				describeStreamResultWithShards(Collections.singletonList(new Shard()));

		Mockito.when(amazonKinesisMock.describeStream(name)).thenReturn(describeStreamResult);

		ConsumerDestination destination =
				provisioner.provisionConsumerDestination(name, group, extendedConsumerProperties);

		Mockito.verify(amazonKinesisMock, Mockito.times(1)).describeStream(name);
		Assert.assertThat(destination.getName(), Is.is(name));
	}

	@Test
	public void testProvisionProducerSuccessfulWithNewStream() {
		AmazonKinesis amazonKinesisMock = Mockito.mock(AmazonKinesis.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(amazonKinesisMock, binderProperties);
		ExtendedProducerProperties<KinesisProducerProperties> extendedProducerProperties
				= new ExtendedProducerProperties<>(new KinesisProducerProperties());
		String name = "test-stream";
		Integer shards = 1;

		Mockito.when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));
		Mockito.when(amazonKinesisMock.createStream(name, shards)).thenReturn(new CreateStreamResult());

		ProducerDestination destination = provisioner.provisionProducerDestination(name, extendedProducerProperties);

		Mockito.verify(amazonKinesisMock, Mockito.times(1)).describeStream(name);
		Mockito.verify(amazonKinesisMock, Mockito.times(1)).createStream(name, shards);
		Assert.assertThat(destination.getName(), Is.is(name));
	}

	@Test
	public void testProvisionConsumerSuccessfulWithNewStream() {
		AmazonKinesis amazonKinesisMock = Mockito.mock(AmazonKinesis.class);
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

		Mockito.when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));
		Mockito.when(amazonKinesisMock.createStream(name, instanceCount * concurrency)).thenReturn(new CreateStreamResult());

		ConsumerDestination destination = provisioner.provisionConsumerDestination(name, group, extendedConsumerProperties);

		Mockito.verify(amazonKinesisMock, Mockito.times(1)).describeStream(name);
		Mockito.verify(amazonKinesisMock, Mockito.times(1)).createStream(name, instanceCount * concurrency);
		Assert.assertThat(destination.getName(), Is.is(name));
	}

	private static DescribeStreamResult describeStreamResultWithShards(List<Shard> shards) {
		return new DescribeStreamResult()
				.withStreamDescription(
						new StreamDescription()
								.withShards(shards)
				);
	}

}

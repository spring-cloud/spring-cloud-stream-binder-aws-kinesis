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
import static org.mockito.Mockito.*;

/**
 * @author Jacob Severson
 */
public class KinesisStreamProvisionerTests {

	@Test
	public void testProvisionProducerSuccessful() {
		String name = "test-stream";
		Boolean autoCreateStream = true;

		KinesisStreamHandler kinesisStreamHandlerMock = mock(KinesisStreamHandler.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		binderProperties.setAutoCreateStreams(true);
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(kinesisStreamHandlerMock, binderProperties);

		when(kinesisStreamHandlerMock.createOrUpdate(name, 1, autoCreateStream))
				.thenReturn(new KinesisStream(name, 1));

		ProducerDestination destination = provisioner.provisionProducerDestination(name,
						new ExtendedProducerProperties<>(new KinesisProducerProperties()));

		verify(kinesisStreamHandlerMock, times(1)).createOrUpdate(name, 1, autoCreateStream);
		assertThat(destination.getName(), is(name));
	}

	@Test(expected = RuntimeException.class)
	public void testProvisionProducerUnsuccessful() {
		String name = "test-stream";
		Integer shards = 2;
		Boolean autoCreateStream = true;

		KinesisStreamHandler kinesisStreamHandlerMock = mock(KinesisStreamHandler.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		binderProperties.setAutoCreateStreams(true);
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(kinesisStreamHandlerMock, binderProperties);

		when(kinesisStreamHandlerMock.createOrUpdate(name, shards, autoCreateStream))
				.thenThrow(new RuntimeException("oops"));

		provisioner.provisionProducerDestination(name,
				new ExtendedProducerProperties<>(new KinesisProducerProperties()));

		verify(kinesisStreamHandlerMock, times(1)).createOrUpdate(name, shards, autoCreateStream);
	}

	@Test
	public void testProvisionConsumerSuccessful() {
		String name = "test-stream";
		String group = "test-group";
		Boolean autoCreateStream = true;

		KinesisStreamHandler kinesisStreamHandlerMock = mock(KinesisStreamHandler.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		binderProperties.setAutoCreateStreams(true);
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(kinesisStreamHandlerMock, binderProperties);

		when(kinesisStreamHandlerMock.createOrUpdate(name, 1, autoCreateStream))
				.thenReturn(new KinesisStream(name, 1));

		ConsumerDestination destination = provisioner.provisionConsumerDestination(name, group,
				new ExtendedConsumerProperties<>(new KinesisConsumerProperties()));

		verify(kinesisStreamHandlerMock, times(1)).createOrUpdate(name, 1, autoCreateStream);
		assertThat(destination.getName(), is(name));
	}

	@Test(expected = RuntimeException.class)
	public void testProvisionConsumerUnsuccessful() {
		String name = "test-stream";
		String group = "test-group";
		Boolean autoCreateStream = true;

		KinesisStreamHandler kinesisStreamHandlerMock = mock(KinesisStreamHandler.class);
		KinesisBinderConfigurationProperties binderProperties = new KinesisBinderConfigurationProperties();
		binderProperties.setAutoCreateStreams(true);
		KinesisStreamProvisioner provisioner = new KinesisStreamProvisioner(kinesisStreamHandlerMock, binderProperties);

		when(kinesisStreamHandlerMock.createOrUpdate(name, 1, autoCreateStream))
				.thenThrow(new RuntimeException("oops"));

		provisioner.provisionConsumerDestination(name, group,
				new ExtendedConsumerProperties<>(new KinesisConsumerProperties()));

		verify(kinesisStreamHandlerMock, times(1)).createOrUpdate(name, 1, autoCreateStream);
	}
}

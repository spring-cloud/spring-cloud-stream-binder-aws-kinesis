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
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

/**
 * @author Jacob Severson
 */
@RunWith(MockitoJUnitRunner.class)
public class KinesisStreamHandlerTests {

	@Mock private AmazonKinesis amazonKinesisMock;
	@InjectMocks private KinesisStreamHandler kinesisStreamHandler;

	@Test
	public void testCreateWithExistingStream() {
		String name = "test-stream";
		Integer shards = 2;

		DescribeStreamResult describeStreamResult =
				describeStreamResultWithShards(Collections.singletonList(new Shard()));

		when(amazonKinesisMock.describeStream(name)).thenReturn(describeStreamResult);

		KinesisStream stream = kinesisStreamHandler.createOrUpdate(name, shards, true);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		assertThat(stream.getName(), is(name));
		assertThat(stream.getShards(), is(1));
	}

	@Test
	public void testCreateNewStreamSuccessfulAutoCreationEnabled() {
		String name = "test-stream";
		Integer shards = 2;

		when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));
		when(amazonKinesisMock.createStream(name, shards)).thenReturn(new CreateStreamResult());

		KinesisStream stream = kinesisStreamHandler.createOrUpdate(name, shards, true);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		verify(amazonKinesisMock, times(1)).createStream(name, shards);
		assertThat(stream.getName(), is(name));
		assertThat(stream.getShards(), is(2));
	}

	@Test
	public void testCreateStreamNoErrorAutoCreationNotEnabled() {
		String name = "test-stream";
		Integer shards = 2;

		when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));

		KinesisStream stream = kinesisStreamHandler.createOrUpdate(name, shards, false);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		verifyNoMoreInteractions(amazonKinesisMock);
		assertThat(stream.getName(), is(name));
		assertThat(stream.getShards(), is(0));
	}

	@Test(expected = RuntimeException.class)
	public void testCreateNewStreamUnsuccessfulAutoCreationEnabled() {
		String name = "test-stream";
		Integer shards = 2;

		when(amazonKinesisMock.describeStream(name)).thenThrow(new ResourceNotFoundException("I got nothing"));
		when(amazonKinesisMock.createStream(name, shards)).thenThrow(new RuntimeException("oops"));

		kinesisStreamHandler.createOrUpdate(name, shards, true);

		verify(amazonKinesisMock, times(1)).describeStream(name);
		verify(amazonKinesisMock, times(1)).createStream(name, shards);
	}

	private static DescribeStreamResult describeStreamResultWithShards(List<Shard> shards) {
		return new DescribeStreamResult()
						.withStreamDescription(
								new StreamDescription()
										.withShards(shards)
						);
	}

}

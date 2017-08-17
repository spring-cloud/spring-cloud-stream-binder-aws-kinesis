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

import java.util.List;

import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.model.ListStreamsRequest;
import com.amazonaws.services.kinesis.model.ListStreamsResult;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;

import org.springframework.cloud.stream.binder.AbstractTestBinder;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.integration.codec.kryo.PojoCodec;

/**
 * @author Artem Bilan
 *
 */
public class KinesisTestBinder
		extends AbstractTestBinder<KinesisMessageChannelBinder, ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>> {

	private final AmazonKinesisAsync amazonKinesis;

	public KinesisTestBinder(AmazonKinesisAsync amazonKinesis,
			KinesisBinderConfigurationProperties kinesisBinderConfigurationProperties) {
		this.amazonKinesis = amazonKinesis;

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
		ListStreamsRequest listStreamsRequest = new ListStreamsRequest();
		ListStreamsResult listStreamsResult = this.amazonKinesis.listStreams(listStreamsRequest);

		List<String> streamNames = listStreamsResult.getStreamNames();

		while (listStreamsResult.getHasMoreStreams()) {
			if (streamNames.size() > 0) {
				listStreamsRequest.setExclusiveStartStreamName(streamNames.get(streamNames.size() - 1));
			}
			listStreamsResult = this.amazonKinesis.listStreams(listStreamsRequest);
			streamNames.addAll(listStreamsResult.getStreamNames());
		}

		for (String stream : streamNames) {
			this.amazonKinesis.deleteStream(stream);
			while (true) {
				try {
					this.amazonKinesis.describeStream(stream);
					try {
						Thread.sleep(100);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(e);
					}
				}
				catch (ResourceNotFoundException e) {
					break;
				}
			}
		}
	}

}

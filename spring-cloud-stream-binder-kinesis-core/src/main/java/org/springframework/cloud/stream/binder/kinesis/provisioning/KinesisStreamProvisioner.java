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

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.LimitExceededException;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.StreamDescription;
import com.amazonaws.services.kinesis.model.StreamStatus;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

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
 * @author Jacob Severson
 *
 */
public class KinesisStreamProvisioner
		implements
		ProvisioningProvider<ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>> {

	private final Log logger = LogFactory.getLog(getClass());

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

		if (this.logger.isInfoEnabled()) {
			this.logger.info("Using Kinesis stream for outbound: " + name);
		}

		return new KinesisProducerDestination(name, createOrUpdate(name, properties.getPartitionCount()));
	}

	@Override
	public ConsumerDestination provisionConsumerDestination(String name, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) throws ProvisioningException {

		if (this.logger.isInfoEnabled()) {
			this.logger.info("Using Kinesis stream for inbound: " + name);
		}

		int shardCount = properties.getInstanceCount() * properties.getConcurrency();

		return new KinesisConsumerDestination(name, createOrUpdate(name, shardCount));
	}

	private List<Shard> createOrUpdate(String stream, int shards) {
		List<Shard> shardList = new ArrayList<>();

		int describeStreamRetries = 0;

		String exclusiveStartShardId = null;

		DescribeStreamRequest describeStreamRequest =
				new DescribeStreamRequest()
						.withStreamName(stream);

		while (true) {
			DescribeStreamResult describeStreamResult = null;

			try {
				describeStreamRequest.withExclusiveStartShardId(exclusiveStartShardId);
				describeStreamResult = this.amazonKinesis.describeStream(describeStreamRequest);
				StreamDescription streamDescription = describeStreamResult.getStreamDescription();
				if (StreamStatus.ACTIVE.toString().equals(streamDescription.getStreamStatus())) {
					shardList.addAll(streamDescription.getShards());

					if (streamDescription.getHasMoreShards()) {
						exclusiveStartShardId = shardList.get(shardList.size() - 1).getShardId();
					}
					else {
						break;
					}
				}
			}
			catch (ResourceNotFoundException e) {
				if (logger.isInfoEnabled()) {
					logger.info("Stream '" + stream + " ' not found. Create one...");
				}

				this.amazonKinesis.createStream(stream, shards);
				continue;
			}
			catch (LimitExceededException e) {
				logger.info("Got LimitExceededException when describing stream [" + stream + "]. " +
						"Backing off for [" + this.configurationProperties.getDescribeStreamBackoff() + "] millis.");
			}

			if (describeStreamResult == null ||
					!StreamStatus.ACTIVE.toString()
							.equals(describeStreamResult.getStreamDescription().getStreamStatus())) {
				if (describeStreamRetries++ > this.configurationProperties.getDescribeStreamRetries()) {
					ResourceNotFoundException resourceNotFoundException =
							new ResourceNotFoundException("The stream [" + stream +
									"] isn't ACTIVE or doesn't exist.");
					resourceNotFoundException.setServiceName("Kinesis");
					throw new ProvisioningException("Kinesis provisioning error", resourceNotFoundException);
				}
				try {
					Thread.sleep(this.configurationProperties.getDescribeStreamBackoff());
				}
				catch (InterruptedException e) {
					Thread.interrupted();
					throw new ProvisioningException("The [describeStream] thread for the stream ["
							+ stream + "] has been interrupted.", e);
				}
			}
		}

		return shardList;
	}

}

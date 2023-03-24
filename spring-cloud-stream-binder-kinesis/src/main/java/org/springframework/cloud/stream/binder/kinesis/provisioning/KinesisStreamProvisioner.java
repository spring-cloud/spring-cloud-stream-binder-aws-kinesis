/*
 * Copyright 2017-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kinesis.provisioning;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import software.amazon.awssdk.core.retry.backoff.FixedDelayBackoffStrategy;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.ListShardsResponse;
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException;
import software.amazon.awssdk.services.kinesis.model.ScalingType;
import software.amazon.awssdk.services.kinesis.model.Shard;

import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.HeaderMode;
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
 * @author Sergiu Pantiru
 * @author Matthias Wesolowski
 */
public class KinesisStreamProvisioner implements
		ProvisioningProvider<ExtendedConsumerProperties<KinesisConsumerProperties>,
				ExtendedProducerProperties<KinesisProducerProperties>> {

	private static final Log logger = LogFactory.getLog(KinesisStreamProvisioner.class);

	private final KinesisAsyncClient amazonKinesis;

	private final KinesisBinderConfigurationProperties configurationProperties;

	public KinesisStreamProvisioner(KinesisAsyncClient amazonKinesis,
			KinesisBinderConfigurationProperties kinesisBinderConfigurationProperties) {

		Assert.notNull(amazonKinesis, "'amazonKinesis' must not be null");
		Assert.notNull(kinesisBinderConfigurationProperties,
				"'kinesisBinderConfigurationProperties' must not be null");
		this.amazonKinesis = amazonKinesis;
		this.configurationProperties = kinesisBinderConfigurationProperties;
	}

	@Override
	public ProducerDestination provisionProducerDestination(String name,
			ExtendedProducerProperties<KinesisProducerProperties> properties)
			throws ProvisioningException {

		if (logger.isInfoEnabled()) {
			logger.info("Using Kinesis stream for outbound: " + name);
		}

		KinesisProducerProperties kinesisProducerProperties = properties.getExtension();
		kinesisProducerProperties.setEmbedHeaders(
				properties.getHeaderMode() == null || HeaderMode.embeddedHeaders.equals(properties.getHeaderMode()));
		properties.setHeaderMode(HeaderMode.none);

		if (properties.getHeaderMode() == null) {
			properties.setHeaderMode(HeaderMode.embeddedHeaders);
		}

		return new KinesisProducerDestination(name, createOrUpdate(name, properties.getPartitionCount()));
	}

	@Override
	public ConsumerDestination provisionConsumerDestination(String name, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties)
			throws ProvisioningException {

		KinesisConsumerProperties kinesisConsumerProperties = properties.getExtension();
		kinesisConsumerProperties.setEmbedHeaders(
				properties.getHeaderMode() == null || HeaderMode.embeddedHeaders.equals(properties.getHeaderMode()));
		properties.setHeaderMode(HeaderMode.none);

		if (logger.isInfoEnabled()) {
			logger.info("Using Kinesis stream for inbound: " + name);
		}

		int shardCount = properties.getInstanceCount() * properties.getConcurrency();

		return new KinesisConsumerDestination(name, createOrUpdate(name, shardCount));
	}

	private List<Shard> createOrUpdate(String stream, int shards) {
		List<Shard> shardList;
		try {
			shardList = getShardList(stream).join();
		}
		catch (CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof ResourceNotFoundException) {
				if (!this.configurationProperties.isAutoCreateStream()) {
					throw new ProvisioningException(
							"The stream [" + stream + "] was not found and auto creation is disabled.", cause);
				}
				if (logger.isInfoEnabled()) {
					logger.info("Stream '" + stream + "' not found. Create one...");
				}

				shardList = createStream(stream, shards);
			}
			else {
				throw new ProvisioningException(
						"Cannot retrieve shards information for stream [" + stream + "].", cause);
			}
		}

		int effectiveShardCount = Math.max(this.configurationProperties.getMinShardCount(), shards);

		if ((shardList.size() < effectiveShardCount) && this.configurationProperties.isAutoAddShards()) {
			return updateShardCount(stream, shardList.size(), effectiveShardCount);
		}

		return shardList;
	}

	private CompletableFuture<List<Shard>> getShardList(String stream) {
		return this.amazonKinesis.describeStreamSummary(request -> request.streamName(stream))
				.thenCompose(reply -> this.amazonKinesis.listShards(request -> request.streamName(stream)))
				.thenApply(ListShardsResponse::shards);
	}

	private List<Shard> createStream(String streamName, int shards) {
		try {
			return this.amazonKinesis.createStream(request -> request
							.streamName(streamName)
							.shardCount(Math.max(this.configurationProperties.getMinShardCount(), shards)))
					.thenCompose(reply -> waitForStreamToBecomeActive(streamName))
					.thenCompose(reply -> getShardList(streamName))
					.join();
		}
		catch (Exception ex) {
			throw new ProvisioningException("Cannot create stream [" + streamName + "].", ex);
		}
	}

	private CompletableFuture<WaiterResponse<DescribeStreamResponse>> waitForStreamToBecomeActive(String streamName) {
		return this.amazonKinesis.waiter()
				.waitUntilStreamExists(request -> request.streamName(streamName),
						waiter -> waiter
								.maxAttempts(this.configurationProperties.getDescribeStreamRetries())
								.backoffStrategy(FixedDelayBackoffStrategy.create(
										Duration.ofMillis(this.configurationProperties.getDescribeStreamBackoff()))));
	}

	private List<Shard> updateShardCount(String streamName, int shardCount, int targetCount) {
		if (logger.isInfoEnabled()) {
			logger.info("Stream [" + streamName + "] has [" + shardCount
					+ "] shards compared to a target configuration of [" + targetCount
					+ "], creating shards...");
		}

		return this.amazonKinesis.updateShardCount(request -> request
						.streamName(streamName)
						.targetShardCount(targetCount)
						.scalingType(ScalingType.UNIFORM_SCALING))
				.thenCompose(reply -> waitForStreamToBecomeActive(streamName))
				.thenCompose(reply -> getShardList(streamName))
				.join();
	}

}

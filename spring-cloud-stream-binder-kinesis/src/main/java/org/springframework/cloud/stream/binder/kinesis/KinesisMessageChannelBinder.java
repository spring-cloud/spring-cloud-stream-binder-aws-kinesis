/*
 * Copyright 2017-2018 the original author or authors.
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

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.ShardIteratorType;

import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.ExtendedPropertiesBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisConsumerDestination;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.cloud.stream.binding.MessageConverterConfigurer;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.expression.Expression;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageHeaderErrorMessageStrategy;
import org.springframework.integration.aws.inbound.kinesis.KinesisShardOffset;
import org.springframework.integration.aws.inbound.kinesis.ListenerMode;
import org.springframework.integration.aws.outbound.KinesisMessageHandler;
import org.springframework.integration.channel.ChannelInterceptorAware;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.expression.FunctionExpression;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 *
 * @author Peter Oates
 * @author Artem Bilan
 *
 */
public class KinesisMessageChannelBinder extends
		AbstractMessageChannelBinder<ExtendedConsumerProperties<KinesisConsumerProperties>, ExtendedProducerProperties<KinesisProducerProperties>, KinesisStreamProvisioner>
		implements ExtendedPropertiesBinder<MessageChannel, KinesisConsumerProperties, KinesisProducerProperties> {

	private static final ErrorMessageStrategy ERROR_MESSAGE_STRATEGY = new KinesisMessageHeaderErrorMessageStrategy();

	private final KinesisBinderConfigurationProperties configurationProperties;

	private KinesisExtendedBindingProperties extendedBindingProperties = new KinesisExtendedBindingProperties();

	private final AmazonKinesisAsync amazonKinesis;

	private ConcurrentMetadataStore checkpointStore;

	private LockRegistry lockRegistry;

	public KinesisMessageChannelBinder(AmazonKinesisAsync amazonKinesis,
			KinesisBinderConfigurationProperties configurationProperties,
			KinesisStreamProvisioner provisioningProvider) {

		super(headersToMap(configurationProperties), provisioningProvider);
		Assert.notNull(amazonKinesis, "'amazonKinesis' must not be null");
		this.configurationProperties = configurationProperties;
		this.amazonKinesis = amazonKinesis;
	}

	public void setExtendedBindingProperties(KinesisExtendedBindingProperties extendedBindingProperties) {
		this.extendedBindingProperties = extendedBindingProperties;
	}

	public void setCheckpointStore(ConcurrentMetadataStore checkpointStore) {
		this.checkpointStore = checkpointStore;
	}

	public void setLockRegistry(LockRegistry lockRegistry) {
		this.lockRegistry = lockRegistry;
	}

	@Override
	public KinesisConsumerProperties getExtendedConsumerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedConsumerProperties(channelName);
	}

	@Override
	public KinesisProducerProperties getExtendedProducerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedProducerProperties(channelName);
	}

	@Override
	protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
			ExtendedProducerProperties<KinesisProducerProperties> producerProperties, MessageChannel errorChannel) {

		KinesisMessageHandler kinesisMessageHandler = new KinesisMessageHandler(this.amazonKinesis);
		kinesisMessageHandler.setSync(producerProperties.getExtension().isSync());
		kinesisMessageHandler.setSendTimeout(producerProperties.getExtension().getSendTimeout());
		kinesisMessageHandler.setStream(destination.getName());
		Expression partitionKeyExpression = producerProperties.getPartitionKeyExpression();
		if (partitionKeyExpression != null) {
			kinesisMessageHandler
					.setPartitionKeyExpression(partitionKeyExpression);
		}
		else {
			kinesisMessageHandler.setPartitionKeyExpression(
					new FunctionExpression<Message<?>>(m -> m.getPayload().hashCode()));
		}
		kinesisMessageHandler.setFailureChannel(errorChannel);
		kinesisMessageHandler.setBeanFactory(getBeanFactory());

		return kinesisMessageHandler;
	}

	@Override
	protected void postProcessOutputChannel(MessageChannel outputChannel,
			ExtendedProducerProperties<KinesisProducerProperties> producerProperties) {

		if (outputChannel instanceof ChannelInterceptorAware) {
			ChannelInterceptorAware channelInterceptorAware = (ChannelInterceptorAware) outputChannel;
			List<ChannelInterceptor> channelInterceptors = channelInterceptorAware.getChannelInterceptors();
			for (ChannelInterceptor channelInterceptor : channelInterceptors) {
				if (channelInterceptor instanceof MessageConverterConfigurer.PartitioningInterceptor) {
					channelInterceptorAware.removeInterceptor(channelInterceptor);
					break;
				}
			}
		}
	}

	@Override
	protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) {

		KinesisConsumerProperties kinesisConsumerProperties = properties.getExtension();

		Set<KinesisShardOffset> shardOffsets = null;

		String shardIteratorType = kinesisConsumerProperties.getShardIteratorType();

		KinesisShardOffset kinesisShardOffset = KinesisShardOffset.latest();

		if (StringUtils.hasText(shardIteratorType)) {
			String[] typeValue = shardIteratorType.split(":", 2);
			ShardIteratorType iteratorType = ShardIteratorType.valueOf(typeValue[0]);
			kinesisShardOffset = new KinesisShardOffset(iteratorType);
			if (typeValue.length > 1) {
				if (ShardIteratorType.AT_TIMESTAMP.equals(iteratorType)) {
					kinesisShardOffset.setTimestamp(new Date(Long.parseLong(typeValue[1])));
				}
				else {
					kinesisShardOffset.setSequenceNumber(typeValue[1]);
				}
			}
		}

		if (properties.getInstanceCount() > 1) {
			shardOffsets = new HashSet<>();
			KinesisConsumerDestination kinesisConsumerDestination = (KinesisConsumerDestination) destination;
			List<Shard> shards = kinesisConsumerDestination.getShards();
			for (int i = 0; i < shards.size(); i++) {
				// divide shards across instances
				if ((i % properties.getInstanceCount()) == properties.getInstanceIndex()) {
					KinesisShardOffset shardOffset = new KinesisShardOffset(kinesisShardOffset);
					shardOffset.setStream(destination.getName());
					shardOffset.setShard(shards.get(i).getShardId());
					shardOffsets.add(shardOffset);
				}
			}
		}

		KinesisMessageDrivenChannelAdapter adapter;

		if (CollectionUtils.isEmpty(shardOffsets)) {
			adapter = new KinesisMessageDrivenChannelAdapter(this.amazonKinesis, destination.getName());
		}
		else {
			adapter = new KinesisMessageDrivenChannelAdapter(this.amazonKinesis,
					shardOffsets.toArray(new KinesisShardOffset[0]));
		}

		boolean anonymous = !StringUtils.hasText(group);
		String consumerGroup = anonymous ? "anonymous." + UUID.randomUUID().toString() : group;
		adapter.setConsumerGroup(consumerGroup);

		adapter.setStreamInitialSequence(
				anonymous || StringUtils.hasText(shardIteratorType)
						? kinesisShardOffset
						: KinesisShardOffset.trimHorizon());

		adapter.setListenerMode(kinesisConsumerProperties.getListenerMode());

		if (properties.isUseNativeDecoding()) {
			adapter.setConverter(null);
		}
		else {
			// Defer byte[] conversion to the InboundContentTypeConvertingInterceptor
			adapter.setConverter(bytes -> bytes);
		}

		adapter.setCheckpointMode(kinesisConsumerProperties.getCheckpointMode());
		adapter.setRecordsLimit(kinesisConsumerProperties.getRecordsLimit());
		adapter.setIdleBetweenPolls(kinesisConsumerProperties.getIdleBetweenPolls());
		adapter.setConsumerBackoff(kinesisConsumerProperties.getConsumerBackoff());

		if (this.checkpointStore != null) {
			adapter.setCheckpointStore(this.checkpointStore);
		}

		adapter.setLockRegistry(this.lockRegistry);

		adapter.setConcurrency(properties.getConcurrency());
		adapter.setStartTimeout(kinesisConsumerProperties.getStartTimeout());
		adapter.setDescribeStreamBackoff(this.configurationProperties.getDescribeStreamBackoff());
		adapter.setDescribeStreamRetries(this.configurationProperties.getDescribeStreamRetries());

		ErrorInfrastructure errorInfrastructure = registerErrorInfrastructure(destination, consumerGroup, properties);
		adapter.setErrorMessageStrategy(ERROR_MESSAGE_STRATEGY);
		adapter.setErrorChannel(errorInfrastructure.getErrorChannel());

		return adapter;
	}

	@Override
	protected ErrorMessageStrategy getErrorMessageStrategy() {
		return ERROR_MESSAGE_STRATEGY;
	}

	private static String[] headersToMap(KinesisBinderConfigurationProperties configurationProperties) {
		Assert.notNull(configurationProperties, "'configurationProperties' must not be null");
		if (ObjectUtils.isEmpty(configurationProperties.getHeaders())) {
			return BinderHeaders.STANDARD_HEADERS;
		}
		else {
			String[] combinedHeadersToMap = Arrays.copyOfRange(BinderHeaders.STANDARD_HEADERS, 0,
					BinderHeaders.STANDARD_HEADERS.length + configurationProperties.getHeaders().length);
			System.arraycopy(configurationProperties.getHeaders(), 0, combinedHeadersToMap,
					BinderHeaders.STANDARD_HEADERS.length, configurationProperties.getHeaders().length);
			return combinedHeadersToMap;
		}
	}

}

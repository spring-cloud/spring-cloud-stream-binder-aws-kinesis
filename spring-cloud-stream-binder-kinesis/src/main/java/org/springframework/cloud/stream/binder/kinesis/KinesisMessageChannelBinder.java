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

package org.springframework.cloud.stream.binder.kinesis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import io.micrometer.observation.ObservationRegistry;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.InvalidArgumentException;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;

import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.BinderSpecificPropertiesProvider;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.ExtendedPropertiesBinder;
import org.springframework.cloud.stream.binder.PartitionKeyExtractorStrategy;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisConsumerProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisProducerProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisConsumerDestination;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.expression.EvaluationContext;
import org.springframework.integration.aws.inbound.kinesis.KclMessageDrivenChannelAdapter;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageDrivenChannelAdapter;
import org.springframework.integration.aws.inbound.kinesis.KinesisMessageHeaderErrorMessageStrategy;
import org.springframework.integration.aws.inbound.kinesis.KinesisShardOffset;
import org.springframework.integration.aws.outbound.AbstractAwsMessageHandler;
import org.springframework.integration.aws.outbound.KinesisMessageHandler;
import org.springframework.integration.aws.outbound.KplMessageHandler;
import org.springframework.integration.channel.NullChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.expression.FunctionExpression;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.integration.support.management.IntegrationManagement;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.InterceptableChannel;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 *
 * The Spring Cloud Stream Binder implementation for AWS Kinesis.
 *
 * @author Peter Oates
 * @author Artem Bilan
 * @author Arnaud Lecollaire
 * @author Dirk Bonhomme
 * @author Asiel Caballero
 * @author Dmytro Danilenkov
 * @author Minkyu Moon
 *
 */
public class KinesisMessageChannelBinder extends
		AbstractMessageChannelBinder<ExtendedConsumerProperties<KinesisConsumerProperties>,
				ExtendedProducerProperties<KinesisProducerProperties>, KinesisStreamProvisioner>
		implements
		ExtendedPropertiesBinder<MessageChannel, KinesisConsumerProperties, KinesisProducerProperties> {

	private static final ErrorMessageStrategy ERROR_MESSAGE_STRATEGY = new KinesisMessageHeaderErrorMessageStrategy();

	private final List<String> streamsInUse = new ArrayList<>();

	private final KinesisBinderConfigurationProperties configurationProperties;

	private final KinesisAsyncClient amazonKinesis;

	private final AwsCredentialsProvider awsCredentialsProvider;

	private final CloudWatchAsyncClient cloudWatchClient;

	private final DynamoDbAsyncClient dynamoDBClient;

	private final LegacyEmbeddedHeadersSupportBytesMessageMapper embeddedHeadersMapper;

	private KinesisExtendedBindingProperties extendedBindingProperties = new KinesisExtendedBindingProperties();

	@Nullable
	private ConcurrentMetadataStore checkpointStore;

	@Nullable
	private LockRegistry lockRegistry;

	@Nullable
	private KinesisProducerConfiguration kinesisProducerConfiguration;

	private EvaluationContext evaluationContext;

	private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

	public KinesisMessageChannelBinder(KinesisBinderConfigurationProperties configurationProperties,
			KinesisStreamProvisioner provisioningProvider, KinesisAsyncClient amazonKinesis,
			AwsCredentialsProvider awsCredentialsProvider,
			@Nullable DynamoDbAsyncClient dynamoDBClient,
			@Nullable CloudWatchAsyncClient cloudWatchClient) {

		super(new String[0], provisioningProvider);
		Assert.notNull(amazonKinesis, "'amazonKinesis' must not be null");
		Assert.notNull(awsCredentialsProvider, "'awsCredentialsProvider' must not be null");
		this.configurationProperties = configurationProperties;
		this.amazonKinesis = amazonKinesis;
		this.cloudWatchClient = cloudWatchClient;
		this.dynamoDBClient = dynamoDBClient;
		this.awsCredentialsProvider = awsCredentialsProvider;

		this.embeddedHeadersMapper =
				new LegacyEmbeddedHeadersSupportBytesMessageMapper(
						configurationProperties.isLegacyEmbeddedHeadersFormat(),
						headersToMap(configurationProperties));
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

	public void setKinesisProducerConfiguration(KinesisProducerConfiguration kinesisProducerConfiguration) {
		this.kinesisProducerConfiguration = kinesisProducerConfiguration;
	}

	public void setObservationRegistry(@Nullable ObservationRegistry observationRegistry) {
		this.observationRegistry = observationRegistry;
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
	public String getDefaultsPrefix() {
		return this.extendedBindingProperties.getDefaultsPrefix();
	}

	@Override
	public Class<? extends BinderSpecificPropertiesProvider> getExtendedPropertiesEntryClass() {
		return this.extendedBindingProperties.getExtendedPropertiesEntryClass();
	}

	public KinesisAsyncClient getAmazonKinesis() {
		return this.amazonKinesis;
	}

	public List<String> getStreamsInUse() {
		return this.streamsInUse;
	}

	@Override
	protected void onInit() throws Exception {
		super.onInit();
		this.evaluationContext = ExpressionUtils.createStandardEvaluationContext(getBeanFactory());
	}

	@Override
	public String getBinderIdentity() {
		return "kinesis-" + super.getBinderIdentity();
	}

	@Override
	protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
			ExtendedProducerProperties<KinesisProducerProperties> producerProperties, MessageChannel channel,
			@Nullable MessageChannel errorChannel) {

		FunctionExpression<Message<?>> partitionKeyExpression =
				new FunctionExpression<>((m) ->
						m.getHeaders().containsKey(BinderHeaders.PARTITION_HEADER)
								? m.getHeaders().get(BinderHeaders.PARTITION_HEADER)
								: m.getPayload().hashCode());
		AbstractAwsMessageHandler<?> messageHandler;
		if (this.configurationProperties.isKplKclEnabled()) {
			messageHandler = createKplMessageHandler(destination, partitionKeyExpression,
					producerProperties.getExtension().isEmbedHeaders() && !producerProperties.isUseNativeEncoding());
		}
		else {
			messageHandler = createKinesisMessageHandler(destination, partitionKeyExpression,
					producerProperties.getExtension().isEmbedHeaders() && !producerProperties.isUseNativeEncoding());
		}
		messageHandler.setAsync(!producerProperties.getExtension().isSync());
		messageHandler.setSendTimeout(producerProperties.getExtension().getSendTimeout());
		messageHandler.setBeanFactory(getBeanFactory());
		String recordMetadataChannel = producerProperties.getExtension().getRecordMetadataChannel();
		if (StringUtils.hasText(recordMetadataChannel)) {
			messageHandler.setOutputChannelName(recordMetadataChannel);
		}
		else {
			messageHandler.setOutputChannel(new NullChannel());
		}

		if (errorChannel != null) {
			((InterceptableChannel) channel)
					.addInterceptor(new ChannelInterceptor() {

						@Override
						public Message<?> preSend(Message<?> message, MessageChannel channel) {
							return MessageBuilder.fromMessage(message).setErrorChannel(errorChannel).build();
						}

					});
		}

		this.streamsInUse.add(destination.getName());

		return messageHandler;
	}

	@Override
	protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
			ExtendedProducerProperties<KinesisProducerProperties> producerProperties, MessageChannel errorChannel) {
		return null;
	}

	private AbstractAwsMessageHandler<?> createKinesisMessageHandler(ProducerDestination destination,
			FunctionExpression<Message<?>> partitionKeyExpression, boolean embedHeaders) {

		KinesisMessageHandler messageHandler = new KinesisMessageHandler(this.amazonKinesis);
		messageHandler.setStream(destination.getName());
		messageHandler.setPartitionKeyExpression(partitionKeyExpression);
		if (embedHeaders) {
			messageHandler.setEmbeddedHeadersMapper(this.embeddedHeadersMapper);
		}
		return messageHandler;
	}

	private AbstractAwsMessageHandler<?> createKplMessageHandler(ProducerDestination destination,
			FunctionExpression<Message<?>> partitionKeyExpression, boolean embedHeaders) {

		KplMessageHandler messageHandler = new KplMessageHandler(new KinesisProducer(this.kinesisProducerConfiguration));
		messageHandler.setStream(destination.getName());
		messageHandler.setPartitionKeyExpression(partitionKeyExpression);
		if (embedHeaders) {
			messageHandler.setEmbeddedHeadersMapper(this.embeddedHeadersMapper);
		}
		return messageHandler;
	}

	@Override
	protected void postProcessOutputChannel(MessageChannel outputChannel,
			ExtendedProducerProperties<KinesisProducerProperties> producerProperties) {

		((IntegrationManagement) outputChannel).registerObservationRegistry(this.observationRegistry);

		if (outputChannel instanceof InterceptableChannel && producerProperties.isPartitioned()) {
			((InterceptableChannel) outputChannel)
					.addInterceptor(0,
							new ChannelInterceptor() {

								private final PartitionKeyExtractorStrategy partitionKeyExtractorStrategy;

								{
									if (StringUtils.hasText(producerProperties.getPartitionKeyExtractorName())) {
										this.partitionKeyExtractorStrategy =
												getBeanFactory()
														.getBean(producerProperties.getPartitionKeyExtractorName(),
																PartitionKeyExtractorStrategy.class);
									}
									else {
										this.partitionKeyExtractorStrategy =
												(message) ->
														producerProperties.getPartitionKeyExpression()
																.getValue(KinesisMessageChannelBinder.this.evaluationContext,
																		message);
									}
								}

								@Override
								public Message<?> preSend(Message<?> message, MessageChannel channel) {
									Object partitionKey = this.partitionKeyExtractorStrategy.extractKey(message);
									return MessageBuilder.fromMessage(message)
											.setHeader(BinderHeaders.PARTITION_HEADER, partitionKey)
											.build();
								}

							});
		}
	}

	@Override
	protected MessageProducer createConsumerEndpoint(ConsumerDestination destination,
			String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) {

		this.streamsInUse.add(destination.getName());

		MessageProducerSupport adapter;
		if (this.configurationProperties.isKplKclEnabled()) {
			adapter = createKclConsumerEndpoint(destination, group, properties);
		}
		else {
			adapter = createKinesisConsumerEndpoint(destination, group, properties);
		}

		adapter.registerObservationRegistry(this.observationRegistry);
		adapter.setComponentName(String.format("Consumer for [%s]", destination.getName()));

		return adapter;
	}

	private MessageProducerSupport createKclConsumerEndpoint(ConsumerDestination destination, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) {

		KinesisConsumerProperties kinesisConsumerProperties = properties.getExtension();

		if (kinesisConsumerProperties.getShardId() != null) {
			logger.warn("Kinesis Client Library doesn't does not support explicit shard configuration. " +
					"Ignoring 'shardId' property");
		}

		String[] streams =
				Arrays.stream(StringUtils.commaDelimitedListToStringArray(destination.getName()))
						.map(String::trim)
						.toArray(String[]::new);

		KclMessageDrivenChannelAdapter adapter =
				new KclMessageDrivenChannelAdapter(this.amazonKinesis, this.cloudWatchClient, this.dynamoDBClient,
						streams);

		boolean anonymous = !StringUtils.hasText(group);
		String consumerGroup = anonymous ? "anonymous." + UUID.randomUUID() : group;

		String workerId =
				kinesisConsumerProperties.getWorkerId() != null
						? kinesisConsumerProperties.getWorkerId()
						: UUID.randomUUID().toString();

		String shardIteratorType = kinesisConsumerProperties.getShardIteratorType();

		InitialPositionInStreamExtended kinesisShardOffset =
				InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST);

		if (StringUtils.hasText(shardIteratorType)) {
			String[] typeValue = shardIteratorType.split(":", 2);
			ShardIteratorType iteratorType = ShardIteratorType.valueOf(typeValue[0]);
			if (typeValue.length > 1) {
				if (ShardIteratorType.AT_TIMESTAMP.equals(iteratorType)) {
					kinesisShardOffset =
							InitialPositionInStreamExtended.newInitialPositionAtTimestamp(
									new Date(Long.parseLong(typeValue[1])));
				}
				else {
					throw new IllegalArgumentException("The KCL does not support 'AT_SEQUENCE_NUMBER' " +
							"or 'AFTER_SEQUENCE_NUMBER' initial position in stream.");
				}
			}
			else {
				kinesisShardOffset =
						InitialPositionInStreamExtended.newInitialPosition(
								InitialPositionInStream.valueOf(iteratorType.name()));
			}
		}

		kinesisShardOffset =
				anonymous || StringUtils.hasText(shardIteratorType)
						? kinesisShardOffset
						: InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON);

		adapter.setConsumerGroup(consumerGroup);
		adapter.setWorkerId(workerId);
		adapter.setStreamInitialSequence(kinesisShardOffset);
		adapter.setCheckpointMode(kinesisConsumerProperties.getCheckpointMode());
		adapter.setCheckpointsInterval(kinesisConsumerProperties.getCheckpointInterval());
		adapter.setConsumerBackoff(kinesisConsumerProperties.getConsumerBackoff());
		adapter.setListenerMode(kinesisConsumerProperties.getListenerMode());
		adapter.setFanOut(kinesisConsumerProperties.isFanOut());
		adapter.setMetricsLevel(kinesisConsumerProperties.getMetricsLevel());
		adapter.setEmptyRecordList(kinesisConsumerProperties.isEmptyRecordList());
		adapter.setLeaseTableName(kinesisConsumerProperties.getLeaseTableName());
		adapter.setPollingMaxRecords(kinesisConsumerProperties.getPollingMaxRecords());
		adapter.setPollingIdleTime(kinesisConsumerProperties.getPollingIdleTime());
		adapter.setGracefulShutdownTimeout(kinesisConsumerProperties.getGracefulShutdownTimeout());
		if (properties.getExtension().isEmbedHeaders() && !properties.isUseNativeDecoding()) {
			adapter.setEmbeddedHeadersMapper(this.embeddedHeadersMapper);
		}

		if (properties.isUseNativeDecoding()) {
			adapter.setConverter(null);
		}
		else {
			// Defer byte[] conversion to the InboundContentTypeConvertingInterceptor
			adapter.setConverter((bytes) -> bytes);
		}

		ErrorInfrastructure errorInfrastructure = registerErrorInfrastructure(destination, consumerGroup, properties);
		adapter.setErrorMessageStrategy(ERROR_MESSAGE_STRATEGY);
		adapter.setErrorChannel(errorInfrastructure.getErrorChannel());
		adapter.setBindSourceRecord(true);
		return adapter;
	}

	private MessageProducerSupport createKinesisConsumerEndpoint(ConsumerDestination destination, String group,
			ExtendedConsumerProperties<KinesisConsumerProperties> properties) {

		KinesisConsumerProperties kinesisConsumerProperties = properties.getExtension();

		if (properties.getInstanceCount() > 1 && properties.getExtension().getShardId() != null) {
			throw InvalidArgumentException.builder()
					.message("'instanceCount' more than 1 and 'shardId' cannot be provided together.")
					.build();
		}

		Set<KinesisShardOffset> shardOffsets = null;

		String shardIteratorType = kinesisConsumerProperties.getShardIteratorType();

		KinesisShardOffset kinesisShardOffset = KinesisShardOffset.latest();

		if (StringUtils.hasText(shardIteratorType)) {
			String[] typeValue = shardIteratorType.split(":", 2);
			ShardIteratorType iteratorType = ShardIteratorType.valueOf(typeValue[0]);
			kinesisShardOffset = new KinesisShardOffset(iteratorType);
			if (typeValue.length > 1) {
				if (ShardIteratorType.AT_TIMESTAMP.equals(iteratorType)) {
					kinesisShardOffset
							.setTimestamp(Instant.ofEpochMilli(Long.parseLong(typeValue[1])));
				}
				else {
					kinesisShardOffset.setSequenceNumber(typeValue[1]);
				}
			}
		}

		if (properties.getInstanceCount() > 1) {
			Assert.state(!properties.isMultiplex(), "Cannot use multi-stream binding together with 'instance-index'");
			shardOffsets = new HashSet<>();
			KinesisConsumerDestination kinesisConsumerDestination = (KinesisConsumerDestination) destination;
			List<Shard> shards = kinesisConsumerDestination.getShards();
			for (int i = 0; i < shards.size(); i++) {
				// divide shards across instances
				if ((i % properties.getInstanceCount()) == properties.getInstanceIndex()) {
					KinesisShardOffset shardOffset = new KinesisShardOffset(kinesisShardOffset);
					shardOffset.setStream(destination.getName());
					shardOffset.setShard(shards.get(i).shardId());
					shardOffsets.add(shardOffset);
				}
			}
		}

		KinesisMessageDrivenChannelAdapter adapter;

		String shardId = kinesisConsumerProperties.getShardId();

		if (CollectionUtils.isEmpty(shardOffsets) && shardId == null) {
			String[] streams =
					Arrays.stream(StringUtils.commaDelimitedListToStringArray(destination.getName()))
							.map(String::trim)
							.toArray(String[]::new);
			adapter = new KinesisMessageDrivenChannelAdapter(this.amazonKinesis, streams);
		}
		else if (shardId != null) {
			Assert.state(!properties.isMultiplex(), "Cannot use multi-stream binding together with 'shard-id'");
			KinesisShardOffset shardOffset = new KinesisShardOffset(kinesisShardOffset);
			shardOffset.setStream(destination.getName());
			shardOffset.setShard(shardId);
			adapter = new KinesisMessageDrivenChannelAdapter(this.amazonKinesis, shardOffset);
		}
		else {
			adapter = new KinesisMessageDrivenChannelAdapter(this.amazonKinesis,
					shardOffsets.toArray(new KinesisShardOffset[0]));
		}

		boolean anonymous = !StringUtils.hasText(group);
		String consumerGroup = anonymous ? "anonymous." + UUID.randomUUID() : group;
		adapter.setConsumerGroup(consumerGroup);

		adapter.setStreamInitialSequence(
				anonymous || StringUtils.hasText(shardIteratorType) ? kinesisShardOffset
						: KinesisShardOffset.trimHorizon());

		adapter.setListenerMode(kinesisConsumerProperties.getListenerMode());
		if (properties.getExtension().isEmbedHeaders() && !properties.isUseNativeDecoding()) {
			adapter.setEmbeddedHeadersMapper(this.embeddedHeadersMapper);
		}

		if (properties.isUseNativeDecoding()) {
			adapter.setConverter(null);
		}
		else {
			// Defer byte[] conversion to the InboundContentTypeConvertingInterceptor
			adapter.setConverter((bytes) -> bytes);
		}

		adapter.setCheckpointMode(kinesisConsumerProperties.getCheckpointMode());
		adapter.setRecordsLimit(kinesisConsumerProperties.getRecordsLimit());
		adapter.setIdleBetweenPolls(kinesisConsumerProperties.getIdleBetweenPolls());
		adapter.setConsumerBackoff(kinesisConsumerProperties.getConsumerBackoff());
		adapter.setCheckpointsInterval(kinesisConsumerProperties.getCheckpointInterval());

		if (this.checkpointStore != null) {
			adapter.setCheckpointStore(this.checkpointStore);
		}

		adapter.setLockRegistry(this.lockRegistry);

		adapter.setConcurrency(properties.getConcurrency());
		adapter.setStartTimeout(kinesisConsumerProperties.getStartTimeout());
		adapter.setDescribeStreamBackoff(
				this.configurationProperties.getDescribeStreamBackoff());
		adapter.setDescribeStreamRetries(
				this.configurationProperties.getDescribeStreamRetries());

		ErrorInfrastructure errorInfrastructure = registerErrorInfrastructure(destination,
				consumerGroup, properties);
		adapter.setErrorMessageStrategy(ERROR_MESSAGE_STRATEGY);
		adapter.setErrorChannel(errorInfrastructure.getErrorChannel());
		adapter.setBindSourceRecord(true);
		return adapter;
	}

	@Override
	protected ErrorMessageStrategy getErrorMessageStrategy() {
		return ERROR_MESSAGE_STRATEGY;
	}

	private static String[] headersToMap(KinesisBinderConfigurationProperties configurationProperties) {
		Assert.notNull(configurationProperties, "'configurationProperties' must not be null");
		List<String> headers = new ArrayList<>();
		Collections.addAll(headers, BinderHeaders.STANDARD_HEADERS);
		if (configurationProperties.isEnableObservation()) {
			headers.add("traceparent");
			headers.add("X-B3*");
			headers.add("b3");
		}
		if (!ObjectUtils.isEmpty(configurationProperties.getHeaders())) {
			Collections.addAll(headers, configurationProperties.getHeaders());
		}
		return headers.toArray(new String[0]);
	}

}

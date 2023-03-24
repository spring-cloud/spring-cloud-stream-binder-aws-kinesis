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

package org.springframework.cloud.stream.binder.kinesis.config;

import java.util.List;
import java.util.Set;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import io.micrometer.observation.ObservationRegistry;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.kinesis.KinesisBinderHealthIndicator;
import org.springframework.cloud.stream.binder.kinesis.KinesisMessageChannelBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.cloud.stream.binding.Bindable;
import org.springframework.cloud.stream.config.ConsumerEndpointCustomizer;
import org.springframework.cloud.stream.config.ProducerMessageHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aws.lock.DynamoDbLockRegistry;
import org.springframework.integration.aws.lock.DynamoDbLockRepository;
import org.springframework.integration.aws.metadata.DynamoDbMetadataStore;
import org.springframework.integration.aws.outbound.AbstractAwsMessageHandler;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.support.locks.LockRegistry;

/**
 * The auto-configuration for AWS components and Spring Cloud Stream Kinesis Binder.
 *
 * @author Peter Oates
 * @author Artem Bilan
 * @author Arnaud Lecollaire
 * @author Asiel Caballero
 */
@AutoConfiguration
@ConditionalOnMissingBean(Binder.class)
@EnableConfigurationProperties({KinesisBinderConfigurationProperties.class, KinesisExtendedBindingProperties.class})
public class KinesisBinderConfiguration {

	private final KinesisBinderConfigurationProperties configurationProperties;

	private final AwsCredentialsProvider awsCredentialsProvider;

	private final Region region;

	private final boolean hasInputs;

	public KinesisBinderConfiguration(KinesisBinderConfigurationProperties configurationProperties,
			AwsCredentialsProvider awsCredentialsProvider,
			AwsRegionProvider regionProvider,
			List<Bindable> bindables) {

		this.configurationProperties = configurationProperties;
		this.awsCredentialsProvider = awsCredentialsProvider;
		this.region = regionProvider.getRegion();
		this.hasInputs =
				bindables.stream()
						.map(Bindable::getInputs)
						.flatMap(Set::stream)
						.findFirst()
						.isPresent();
	}

	@Bean
	@ConditionalOnMissingBean
	public KinesisAsyncClient amazonKinesis() {
		return KinesisAsyncClient.builder()
				.credentialsProvider(this.awsCredentialsProvider)
				.region(this.region)
				.build();
	}

	@Bean
	public KinesisStreamProvisioner provisioningProvider(KinesisAsyncClient amazonKinesis) {
		return new KinesisStreamProvisioner(amazonKinesis, this.configurationProperties);
	}

	@Bean
	@ConditionalOnMissingBean
	public DynamoDbAsyncClient dynamoDB() {
		if (this.hasInputs) {
			return DynamoDbAsyncClient.builder()
					.credentialsProvider(this.awsCredentialsProvider)
					.region(this.region)
					.build();
		}
		else {
			return null;
		}
	}

	@Bean
	@ConditionalOnMissingBean(LockRegistry.class)
	@ConditionalOnBean(DynamoDbAsyncClient.class)
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled", havingValue = "false",
			matchIfMissing = true)
	public DynamoDbLockRepository dynamoDbLockRepository(@Autowired(required = false) DynamoDbAsyncClient dynamoDB) {
		if (dynamoDB != null) {
			KinesisBinderConfigurationProperties.Locks locks = this.configurationProperties.getLocks();
			DynamoDbLockRepository dynamoDbLockRepository = new DynamoDbLockRepository(dynamoDB, locks.getTable());
			dynamoDbLockRepository.setLeaseDuration(locks.getLeaseDuration());
			dynamoDbLockRepository.setBillingMode(locks.getBillingMode());
			dynamoDbLockRepository.setReadCapacity(locks.getReadCapacity());
			dynamoDbLockRepository.setWriteCapacity(locks.getWriteCapacity());
			return dynamoDbLockRepository;
		}
		else {
			return null;
		}
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(DynamoDbAsyncClient.class)
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled", havingValue = "false",
			matchIfMissing = true)
	public LockRegistry dynamoDBLockRegistry(
			@Autowired(required = false) DynamoDbLockRepository dynamoDbLockRepository) {
		if (dynamoDbLockRepository != null) {
			KinesisBinderConfigurationProperties.Locks locks = this.configurationProperties.getLocks();
			DynamoDbLockRegistry dynamoDbLockRegistry = new DynamoDbLockRegistry(dynamoDbLockRepository);
			dynamoDbLockRegistry.setIdleBetweenTries(locks.getRefreshPeriod());
			return dynamoDbLockRegistry;
		}
		else {
			return null;
		}
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(DynamoDbAsyncClient.class)
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled", havingValue = "false",
			matchIfMissing = true)
	public ConcurrentMetadataStore kinesisCheckpointStore(@Autowired(required = false) DynamoDbAsyncClient dynamoDB) {
		if (dynamoDB != null) {
			KinesisBinderConfigurationProperties.Checkpoint checkpoint = this.configurationProperties.getCheckpoint();
			DynamoDbMetadataStore kinesisCheckpointStore = new DynamoDbMetadataStore(dynamoDB, checkpoint.getTable());
			kinesisCheckpointStore.setBillingMode(checkpoint.getBillingMode());
			kinesisCheckpointStore.setReadCapacity(checkpoint.getReadCapacity());
			kinesisCheckpointStore.setWriteCapacity(checkpoint.getWriteCapacity());
			kinesisCheckpointStore.setCreateTableDelay(checkpoint.getCreateDelay());
			kinesisCheckpointStore.setCreateTableRetries(checkpoint.getCreateRetries());
			if (checkpoint.getTimeToLive() != null) {
				kinesisCheckpointStore.setTimeToLive(checkpoint.getTimeToLive());
			}
			return kinesisCheckpointStore;
		}
		else {
			return null;
		}
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled")
	public CloudWatchAsyncClient cloudWatch() {
		if (this.hasInputs) {
			return CloudWatchAsyncClient.builder()
					.credentialsProvider(this.awsCredentialsProvider)
					.region(this.region)
					.build();
		}
		else {
			return null;
		}
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled")
	public KinesisProducerConfiguration kinesisProducerConfiguration() {
		KinesisProducerConfiguration kinesisProducerConfiguration = new KinesisProducerConfiguration();
		kinesisProducerConfiguration.setCredentialsProvider(
				new AWSCredentialsProviderAdapter(this.awsCredentialsProvider));
		kinesisProducerConfiguration.setRegion(this.region.id());
		return kinesisProducerConfiguration;
	}

	@Bean
	public KinesisMessageChannelBinder kinesisMessageChannelBinder(
			KinesisStreamProvisioner provisioningProvider,
			KinesisAsyncClient amazonKinesis,
			KinesisExtendedBindingProperties kinesisExtendedBindingProperties,
			@Autowired(required = false) ConcurrentMetadataStore kinesisCheckpointStore,
			@Autowired(required = false) LockRegistry lockRegistry,
			@Autowired(required = false) DynamoDbAsyncClient dynamoDBClient,
			@Autowired(required = false) CloudWatchAsyncClient cloudWatchClient,
			@Autowired(required = false) KinesisProducerConfiguration kinesisProducerConfiguration,
			@Autowired(required = false) ProducerMessageHandlerCustomizer<? extends AbstractAwsMessageHandler<Void>> producerMessageHandlerCustomizer,
			@Autowired(required = false) ConsumerEndpointCustomizer<? extends MessageProducerSupport> consumerEndpointCustomizer,
			@Autowired ObservationRegistry observationRegistry) {

		KinesisMessageChannelBinder kinesisMessageChannelBinder =
				new KinesisMessageChannelBinder(this.configurationProperties, provisioningProvider, amazonKinesis,
						this.awsCredentialsProvider, dynamoDBClient, cloudWatchClient);
		kinesisMessageChannelBinder.setCheckpointStore(kinesisCheckpointStore);
		kinesisMessageChannelBinder.setLockRegistry(lockRegistry);
		kinesisMessageChannelBinder.setExtendedBindingProperties(kinesisExtendedBindingProperties);
		kinesisMessageChannelBinder.setKinesisProducerConfiguration(kinesisProducerConfiguration);
		kinesisMessageChannelBinder.setProducerMessageHandlerCustomizer(producerMessageHandlerCustomizer);
		kinesisMessageChannelBinder.setConsumerEndpointCustomizer(consumerEndpointCustomizer);
		if (this.configurationProperties.isEnableObservation()) {
			kinesisMessageChannelBinder.setObservationRegistry(observationRegistry);
		}
		return kinesisMessageChannelBinder;
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(HealthIndicator.class)
	@ConditionalOnEnabledHealthIndicator("binders")
	protected static class KinesisBinderHealthIndicatorConfiguration {

		@Bean
		@ConditionalOnMissingBean(name = "kinesisBinderHealthIndicator")
		public KinesisBinderHealthIndicator kinesisBinderHealthIndicator(
				KinesisMessageChannelBinder kinesisMessageChannelBinder) {

			return new KinesisBinderHealthIndicator(kinesisMessageChannelBinder);
		}

	}

	private static final class AWSCredentialsProviderAdapter implements AWSCredentialsProvider {

		private final AWSCredentials awsCredentials;

		AWSCredentialsProviderAdapter(AwsCredentialsProvider awsCredentialsProvider) {
			AwsCredentials credentials = awsCredentialsProvider.resolveCredentials();
			this.awsCredentials = new BasicAWSCredentials(credentials.secretAccessKey(), credentials.accessKeyId());
		}

		@Override
		public AWSCredentials getCredentials() {
			return this.awsCredentials;
		}

		@Override
		public void refresh() {

		}

	}

}

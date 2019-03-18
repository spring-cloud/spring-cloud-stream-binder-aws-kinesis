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

package org.springframework.cloud.stream.binder.kinesis.config;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClientBuilder;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.aws.autoconfigure.context.ContextCredentialsAutoConfiguration;
import org.springframework.cloud.aws.autoconfigure.context.ContextRegionProviderAutoConfiguration;
import org.springframework.cloud.aws.core.region.RegionProvider;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.kinesis.KinesisMessageChannelBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.integration.aws.lock.DynamoDbLockRegistry;
import org.springframework.integration.aws.metadata.DynamoDbMetadataStore;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.support.locks.LockRegistry;

/**
 * The auto-configuration for AWS components and Spring Cloud Stream Kinesis Binder.
 *
 * @author Peter Oates
 * @author Artem Bilan
 * @author Arnaud Lecollaire
 */
@Configuration
@ConditionalOnMissingBean(Binder.class)
@EnableConfigurationProperties({ KinesisBinderConfigurationProperties.class,
		KinesisExtendedBindingProperties.class })
@Import({ ContextCredentialsAutoConfiguration.class, ContextRegionProviderAutoConfiguration.class })
public class KinesisBinderConfiguration {

	@Autowired
	private KinesisBinderConfigurationProperties configurationProperties;

	@Bean
	@ConditionalOnMissingBean
	public AmazonKinesisAsync amazonKinesis(AWSCredentialsProvider awsCredentialsProvider,
			RegionProvider regionProvider) {

		return AmazonKinesisAsyncClientBuilder.standard()
				.withCredentials(awsCredentialsProvider)
				.withRegion(regionProvider.getRegion().getName()).build();
	}

	@Bean
	public KinesisStreamProvisioner provisioningProvider(
			AmazonKinesisAsync amazonKinesis) {
		return new KinesisStreamProvisioner(amazonKinesis, this.configurationProperties);
	}

	@Bean
	public KinesisMessageChannelBinder kinesisMessageChannelBinder(
			AmazonKinesisAsync amazonKinesis,
			@Autowired(required = false) AmazonCloudWatch cloudWatchClient,
			AmazonDynamoDB dynamoDBClient,
			KinesisStreamProvisioner provisioningProvider,
			ConcurrentMetadataStore kinesisCheckpointStore, LockRegistry lockRegistry,
			KinesisExtendedBindingProperties kinesisExtendedBindingProperties,
			@Autowired(required = false) KinesisProducerConfiguration kinesisProducerConfiguration,
			AWSCredentialsProvider awsCredentialsProvider,
			@Autowired(required = false) @Qualifier("taskScheduler") TaskExecutor kclTaskExecutor) {

		KinesisMessageChannelBinder kinesisMessageChannelBinder = new KinesisMessageChannelBinder(
				amazonKinesis, cloudWatchClient, dynamoDBClient,
				this.configurationProperties, provisioningProvider, awsCredentialsProvider);
		kinesisMessageChannelBinder.setCheckpointStore(kinesisCheckpointStore);
		kinesisMessageChannelBinder.setLockRegistry(lockRegistry);
		kinesisMessageChannelBinder
				.setExtendedBindingProperties(kinesisExtendedBindingProperties);
		kinesisMessageChannelBinder.setKinesisProducerConfiguration(kinesisProducerConfiguration);
		kinesisMessageChannelBinder.setKclTaskExecutor(kclTaskExecutor);

		return kinesisMessageChannelBinder;
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled", havingValue = "true")
	public AmazonCloudWatchAsync cloudWatch(AWSCredentialsProvider awsCredentialsProvider,
			RegionProvider regionProvider) {

		return AmazonCloudWatchAsyncClientBuilder.standard()
				.withCredentials(awsCredentialsProvider)
				.withRegion(regionProvider.getRegion().getName()).build();
	}

	@Bean
	@ConditionalOnMissingBean
	public AmazonDynamoDBAsync dynamoDB(AWSCredentialsProvider awsCredentialsProvider,
			RegionProvider regionProvider) {

		return AmazonDynamoDBAsyncClientBuilder.standard()
				.withCredentials(awsCredentialsProvider)
				.withRegion(regionProvider.getRegion().getName()).build();
	}

	@Bean
	@ConditionalOnMissingBean
	public LockRegistry dynamoDBLockRegistry(AmazonDynamoDBAsync dynamoDB) {
		KinesisBinderConfigurationProperties.Locks locks = this.configurationProperties
				.getLocks();

		DynamoDbLockRegistry dynamoDbLockRegistry = new DynamoDbLockRegistry(dynamoDB,
				locks.getTable());
		dynamoDbLockRegistry.setRefreshPeriod(locks.getRefreshPeriod());
		dynamoDbLockRegistry.setHeartbeatPeriod(locks.getHeartbeatPeriod());
		dynamoDbLockRegistry.setLeaseDuration(locks.getLeaseDuration());
		dynamoDbLockRegistry.setPartitionKey(locks.getPartitionKey());
		dynamoDbLockRegistry.setSortKeyName(locks.getSortKeyName());
		dynamoDbLockRegistry.setSortKey(locks.getSortKey());
		dynamoDbLockRegistry.setReadCapacity(locks.getReadCapacity());
		dynamoDbLockRegistry.setWriteCapacity(locks.getWriteCapacity());

		return dynamoDbLockRegistry;
	}

	@Bean
	@ConditionalOnMissingBean
	public ConcurrentMetadataStore kinesisCheckpointStore(AmazonDynamoDBAsync dynamoDB) {
		KinesisBinderConfigurationProperties.Checkpoint checkpoint = this.configurationProperties
				.getCheckpoint();

		DynamoDbMetadataStore kinesisCheckpointStore = new DynamoDbMetadataStore(dynamoDB,
				checkpoint.getTable());
		kinesisCheckpointStore.setReadCapacity(checkpoint.getReadCapacity());
		kinesisCheckpointStore.setWriteCapacity(checkpoint.getWriteCapacity());
		kinesisCheckpointStore.setCreateTableDelay(checkpoint.getCreateDelay());
		kinesisCheckpointStore.setCreateTableRetries(checkpoint.getCreateRetries());
		if (checkpoint.getTimeToLive() != null) {
			kinesisCheckpointStore.setTimeToLive(checkpoint.getTimeToLive());
		}

		return kinesisCheckpointStore;
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled", havingValue = "true")
	public KinesisProducerConfiguration kinesisProducerConfiguration(RegionProvider regionProvider) {
		KinesisProducerConfiguration kinesisProducerConfiguration = new KinesisProducerConfiguration();
		kinesisProducerConfiguration.setRegion(regionProvider.getRegion().getName());
		return kinesisProducerConfiguration;
	}

//	@Bean(name = "kclTaskExecutor")
//	@ConditionalOnMissingBean(name = "kclTaskExecutor")
//	@ConditionalOnProperty(name = "spring.cloud.stream.kinesis.binder.kpl-kcl-enabled", havingValue = "true")
//	public TaskExecutor kclTaskExecutor() {
//		return new SimpleAsyncTaskExecutor();
//	}

}

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

package org.springframework.cloud.stream.binder.kinesis.config;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClientBuilder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.aws.core.region.RegionProvider;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.kinesis.KinesisMessageChannelBinder;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kinesis.properties.KinesisExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kinesis.provisioning.KinesisStreamProvisioner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aws.metadata.DynamoDbMetaDataStore;
import org.springframework.integration.codec.Codec;
import org.springframework.integration.metadata.MetadataStore;

/**
 *
 * @author Peter Oates
 * @author Artem Bilan
 *
 */
@Configuration
@ConditionalOnMissingBean(Binder.class)
@EnableConfigurationProperties({ KinesisBinderConfigurationProperties.class, KinesisExtendedBindingProperties.class })
public class KinesisBinderConfiguration {

	@Autowired
	private KinesisBinderConfigurationProperties configurationProperties;

	@Bean
	public AmazonKinesisAsync amazonKinesis(AWSCredentialsProvider awsCredentialsProvider,
			RegionProvider regionProvider) {

		return AmazonKinesisAsyncClientBuilder.standard()
				.withCredentials(awsCredentialsProvider)
				.withRegion(
						regionProvider.getRegion()
								.getName())
				.build();
	}

	@Bean
	public KinesisStreamProvisioner provisioningProvider(AmazonKinesisAsync amazonKinesis) {
		return new KinesisStreamProvisioner(amazonKinesis, this.configurationProperties);
	}

	@Bean
	KinesisMessageChannelBinder kinesisMessageChannelBinder(AmazonKinesisAsync amazonKinesis,
			KinesisStreamProvisioner provisioningProvider, Codec codec, MetadataStore kinesisCheckpointStore) {

		KinesisMessageChannelBinder kinesisMessageChannelBinder = new KinesisMessageChannelBinder(amazonKinesis,
				this.configurationProperties, provisioningProvider);
		kinesisMessageChannelBinder.setCodec(codec);
		kinesisMessageChannelBinder.setCheckpointStore(kinesisCheckpointStore);

		return kinesisMessageChannelBinder;
	}

	@Bean
	@ConditionalOnMissingBean
	MetadataStore kinesisCheckpointStore(AWSCredentialsProvider awsCredentialsProvider, RegionProvider regionProvider) {
		AmazonDynamoDBAsync dynamoDB = AmazonDynamoDBAsyncClientBuilder.standard()
				.withCredentials(awsCredentialsProvider)
				.withRegion(regionProvider.getRegion().getName())
				.build();

		KinesisBinderConfigurationProperties.Checkpoint checkpoint = this.configurationProperties.getCheckpoint();

		DynamoDbMetaDataStore kinesisCheckpointStore = new DynamoDbMetaDataStore(dynamoDB, checkpoint.getTable());
		kinesisCheckpointStore.setReadCapacity(checkpoint.getReadCapacity());
		kinesisCheckpointStore.setWriteCapacity(checkpoint.getWriteCapacity());
		kinesisCheckpointStore.setCreateTableDelay(checkpoint.getCreateDelay());
		kinesisCheckpointStore.setCreateTableRetries(checkpoint.getCreateRetries());

		return kinesisCheckpointStore;
	}

}

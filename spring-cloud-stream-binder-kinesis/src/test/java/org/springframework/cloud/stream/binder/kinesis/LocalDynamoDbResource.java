/*
 * Copyright 2018 the original author or authors.
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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder;

import org.springframework.cloud.stream.test.junit.AbstractExternalResourceTestSupport;

/**
 * The {@link AbstractExternalResourceTestSupport} implementation for local Amazon
 * DynamoDB service. See https://github.com/mhart/dynalite.
 *
 * @author Artem Bilan
 */
public final class LocalDynamoDbResource extends AbstractExternalResourceTestSupport<AmazonDynamoDBAsync> {

	public static final int DEFAULT_PORT = 4568;

	private final int port;

	public LocalDynamoDbResource() {
		this(DEFAULT_PORT);
	}

	public LocalDynamoDbResource(int port) {
		super("DYNAMODB");
		this.port = port;
	}

	@Override
	protected void obtainResource() {
		String url = "http://localhost:" + this.port;

		this.resource = AmazonDynamoDBAsyncClientBuilder.standard()
				.withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("", "")))
				.withClientConfiguration(
						new ClientConfiguration()
								.withMaxErrorRetry(0)
								.withConnectionTimeout(1000))
				.withEndpointConfiguration(
						new AwsClientBuilder.EndpointConfiguration(url, Regions.DEFAULT_REGION.getName()))
				.build();

		this.resource.listTables();
	}

	@Override
	protected void cleanupResource() {
		this.resource.listTables()
				.getTableNames()
				.forEach(this.resource::deleteTable);

		this.resource.shutdown();
	}

}

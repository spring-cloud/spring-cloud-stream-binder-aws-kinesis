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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClientBuilder;
import org.springframework.cloud.stream.test.junit.AbstractExternalResourceTestSupport;

/**
 * @author Artem Bilan
 *
 */
public class LocalKinesisResource extends AbstractExternalResourceTestSupport<AmazonKinesisAsync> {

	public static final int DEFAULT_PORT = 4567;

	private final int port;

	public LocalKinesisResource() {
		this(DEFAULT_PORT);
	}

	public LocalKinesisResource(int port) {
		super("KINESIS");
		this.port = port;
	}

	@Override
	protected void obtainResource() throws Exception {
		// See https://github.com/mhart/kinesalite#cbor-protocol-issues-with-the-java-sdk
		System.setProperty(SDKGlobalConfiguration.AWS_CBOR_DISABLE_SYSTEM_PROPERTY, "true");

		this.resource = AmazonKinesisAsyncClientBuilder.standard()
				.withClientConfiguration(
						new ClientConfiguration()
								.withMaxErrorRetry(0)
								.withConnectionTimeout(1000))
				.withEndpointConfiguration(
						new AwsClientBuilder.EndpointConfiguration("http://localhost:" + this.port,
								Regions.DEFAULT_REGION.getName()))
				.build();

		// Check connection
		this.resource.listStreams();
	}

	@Override
	protected void cleanupResource() throws Exception {
		System.clearProperty(SDKGlobalConfiguration.AWS_CBOR_DISABLE_SYSTEM_PROPERTY);
		this.resource.shutdown();
	}

}

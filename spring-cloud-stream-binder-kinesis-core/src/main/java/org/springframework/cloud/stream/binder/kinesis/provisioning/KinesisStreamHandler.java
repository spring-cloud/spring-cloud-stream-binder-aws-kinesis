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

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Encapsulates Kinesis stream operations.
 *
 * @author Jacob Severson
 */
public class KinesisStreamHandler {

	private final Log logger = LogFactory.getLog(getClass());

	private final AmazonKinesis amazonKinesis;

	public KinesisStreamHandler(AmazonKinesis amazonKinesis) {
		this.amazonKinesis = amazonKinesis;
	}

	public KinesisStream createOrUpdate(String name, Integer shards, Boolean autoCreateStream) {
		try {
			DescribeStreamResult streamResult = amazonKinesis.describeStream(name);
			logger.info("Stream found, using existing stream");

			return new KinesisStream(name, streamResult.getStreamDescription().getShards().size());

		} catch (ResourceNotFoundException e) {
			logger.info("Stream not found");
		}

		if (autoCreateStream) {
			logger.info("Auto create set to true, attempting to create stream");
			amazonKinesis.createStream(name, shards);

			return new KinesisStream(name, shards);
		}

		return new KinesisStream(name, 0);
	}

}

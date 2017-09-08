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

package org.springframework.cloud.stream.binder.kinesis.properties;

/**
 * 
 * @author Peter Oates
 *
 */
public class CheckpointProperties {

	private String checkpointTable = "checkpoint";

	private Long dynamoDbReadCapacity = 1L;

	private Long dynamoDbWriteCapacity = 1L;

	public String getCheckpointTable() {
		return checkpointTable;
	}

	public void setCheckpointTable(String checkpointTable) {
		this.checkpointTable = checkpointTable;
	}

	public Long getDynamoDbReadCapacity() {
		return dynamoDbReadCapacity;
	}

	public void setDynamoDbReadCapacity(Long dynamoDbReadCapacity) {
		this.dynamoDbReadCapacity = dynamoDbReadCapacity;
	}

	public Long getDynamoDbWriteCapacity() {
		return dynamoDbWriteCapacity;
	}

	public void setDynamoDbWriteCapacity(Long dynamoDbWriteCapacity) {
		this.dynamoDbWriteCapacity = dynamoDbWriteCapacity;
	}
	
}

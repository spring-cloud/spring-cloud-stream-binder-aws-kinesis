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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 *
 * @author Peter Oates
 * @author Artem Bilan
 * @author Jacob Severson
 *
 */
@ConfigurationProperties(prefix = "spring.cloud.stream.kinesis.binder")
public class KinesisBinderConfigurationProperties {

	private String[] headers = new String[] {};

	private int describeStreamBackoff = 1000;

	private int describeStreamRetries = 50;

	private boolean autoAddShards = false;

	private int minShardCount = 1;

	private Checkpoint checkpoint = new Checkpoint();

	public String[] getHeaders() {
		return this.headers;
	}

	public void setHeaders(String... headers) {
		this.headers = headers;
	}

	public int getDescribeStreamBackoff() {
		return this.describeStreamBackoff;
	}

	public void setDescribeStreamBackoff(int describeStreamBackoff) {
		this.describeStreamBackoff = describeStreamBackoff;
	}

	public int getDescribeStreamRetries() {
		return this.describeStreamRetries;
	}

	public void setDescribeStreamRetries(int describeStreamRetries) {
		this.describeStreamRetries = describeStreamRetries;
	}

	public boolean isAutoAddShards() {
		return this.autoAddShards;
	}

	public void setAutoAddShards(boolean autoAddShards) {
		this.autoAddShards = autoAddShards;
	}

	public int getMinShardCount() {
		return this.minShardCount;
	}

	public void setMinShardCount(int minShardCount) {
		this.minShardCount = minShardCount;
	}

	public Checkpoint getCheckpoint() {
		return this.checkpoint;
	}

	public void setCheckpoint(Checkpoint checkpoint) {
		this.checkpoint = checkpoint;
	}

	public static class Checkpoint {

		private String table = "checkpoint";

		private long readCapacity = 1L;

		private long writeCapacity = 1L;

		private int createDelay = 1;

		private int createRetries = 25;

		public String getTable() {
			return this.table;
		}

		public void setTable(String table) {
			this.table = table;
		}

		public long getReadCapacity() {
			return this.readCapacity;
		}

		public void setReadCapacity(long readCapacity) {
			this.readCapacity = readCapacity;
		}

		public long getWriteCapacity() {
			return this.writeCapacity;
		}

		public void setWriteCapacity(long writeCapacity) {
			this.writeCapacity = writeCapacity;
		}

		public int getCreateDelay() {
			return this.createDelay;
		}

		public void setCreateDelay(int createDelay) {
			this.createDelay = createDelay;
		}

		public int getCreateRetries() {
			return this.createRetries;
		}

		public void setCreateRetries(int createRetries) {
			this.createRetries = createRetries;
		}

	}

}

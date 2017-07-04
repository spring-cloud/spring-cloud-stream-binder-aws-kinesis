package org.springframework.cloud.stream.binder.kinesis.provisioning;

/**
 * Represents a Kinesis stream.
 *
 * @author Jacob Severson
 */
public class KinesisStream {

	private final String name;

	private final Integer shards;

	public KinesisStream(String name, Integer shards) {
		this.name = name;
		this.shards = shards;
	}

	public String getName() {
		return name;
	}

	public Integer getShards() {
		return shards;
	}
}

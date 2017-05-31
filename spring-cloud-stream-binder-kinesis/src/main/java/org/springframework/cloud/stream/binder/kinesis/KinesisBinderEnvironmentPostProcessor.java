package org.springframework.cloud.stream.binder.kinesis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * An {@link EnvironmentPostProcessor} that sets some common configuration properties (log config etc.,) for Kinesis
 *  binder.
 *
 * @author Peter Oates
 */
public class KinesisBinderEnvironmentPostProcessor implements EnvironmentPostProcessor {

	public final static String SPRING_KINESIS = "spring.kinesis";

	public final static String SPRING_KINESIS_PRODUCER = SPRING_KINESIS + ".producer";

	public final static String SPRING_KINESIS_CONSUMER = SPRING_KINESIS + ".consumer";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

	}
}

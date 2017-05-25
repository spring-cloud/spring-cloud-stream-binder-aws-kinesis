package org.springframework.cloud.stream.binder.kinesis;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

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

	private static final String KINESIS_BINDER_DEFAULT_PROPERTIES = "kinesisBinderDefaultProperties";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getPropertySources().contains(KINESIS_BINDER_DEFAULT_PROPERTIES)) {
			Map<String, Object> kinesisBinderDefaultProperties = new HashMap<>();
			kinesisBinderDefaultProperties.put("logging.pattern.console", "%d{ISO8601} %5p %t %c{2}:%L - %m%n");
			kinesisBinderDefaultProperties.put("logging.level.kinesis.server.KinesisConfig", "ERROR");
			kinesisBinderDefaultProperties.put("logging.level.kinesis.admin.AdminClient.AdminConfig", "ERROR");
			environment.getPropertySources().addLast(new MapPropertySource(KINESIS_BINDER_DEFAULT_PROPERTIES, kinesisBinderDefaultProperties));
		}
	}
}

package sample.conditionSample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * @description:
 * @author: Seldom
 * @time: 2020/11/8 10:52
 */
@Configuration
public class ConditionConfig {

	/**
	 * 只有`@ConditionalOnLinux`的注解属性`environment`是"linux"时才会创建bean
	 * @return
	 */
	@Bean
	@ConditionalOnLinux(environment = "linux")
	public Environment linuxEnvironment() {
		return new LinuxEnvironment();
	}

	/**
	 * 只有`@ConditionalOnLinux`的注解属性`environment`是"linux"时才会创建bean
	 * @return
	 */
	@Bean
	@ConditionalOnLinux(environment = "win")
	public Environment winEnvironment() {
		return new WinEnvironment();
	}
}

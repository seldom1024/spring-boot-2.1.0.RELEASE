/*
 * Copyright 2012-2018 the original author or authors.
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

package org.springframework.boot.context.properties;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;


/**
 * 可以看到EnableConfigurationPropertiesImportSelector类中的selectImports方法中返回的是IMPORTS数组，
 * 而这个IMPORTS是一个常量数组，值是ConfigurationPropertiesBeanRegistrar和ConfigurationPropertiesBindingPostProcessorRegistrar。
 * 即EnableConfigurationPropertiesImportSelector的作用是向Spring容器中注册了
 * ConfigurationPropertiesBeanRegistrar和ConfigurationPropertiesBindingPostProcessorRegistrar这两个bean。
 *
 * 我们在EnableConfigurationPropertiesImportSelector类中没看到处理外部属性绑定的相关逻辑，
 * 其只是注册了ConfigurationPropertiesBeanRegistrar和ConfigurationPropertiesBindingPostProcessorRegistrar
 */

/**
 * Import selector that sets up binding of external properties to configuration classes
 * (see {@link ConfigurationProperties}). It either registers a
 * {@link ConfigurationProperties} bean or not, depending on whether the enclosing
 * {@link EnableConfigurationProperties} explicitly declares one. If none is declared then
 * a bean post processor will still kick in for any beans annotated as external
 * configuration. If one is declared then it a bean definition is registered with id equal
 * to the class name (thus an application context usually only contains one
 * {@link ConfigurationProperties} bean of each unique type).
 *
 * @author Dave Syer
 * @author Christian Dupuis
 * @author Stephane Nicoll
 */
class EnableConfigurationPropertiesImportSelector implements ImportSelector {

	/**
	 * IMPORTS数组即是要向spring容器中注册的bean
	 */
	private static final String[] IMPORTS = {
			ConfigurationPropertiesBeanRegistrar.class.getName(),
			ConfigurationPropertiesBindingPostProcessorRegistrar.class.getName() };

	@Override
	public String[] selectImports(AnnotationMetadata metadata) {
		// 返回ConfigurationPropertiesBeanRegistrar和ConfigurationPropertiesBindingPostProcessorRegistrar的全限定名
		// 即上面两个类将会被注册到Spring容器中
		return IMPORTS;
	}

	/**
	 * {@link ImportBeanDefinitionRegistrar} for configuration properties support.
	 * ConfigurationPropertiesBeanRegistrar是EnableConfigurationPropertiesImportSelector的内部类，
	 * 其实现了ImportBeanDefinitionRegistrar接口，覆写了registerBeanDefinitions方法。
	 * 可见，ConfigurationPropertiesBeanRegistrar又是用来注册一些bean definition的，即也是向Spring容器中注册一些bean。
	 */
	public static class ConfigurationPropertiesBeanRegistrar
			implements ImportBeanDefinitionRegistrar {

		/**
		 * 在ConfigurationPropertiesBeanRegistrar实现的registerBeanDefinitions中，可以看到主要做了两件事：
		 *
		 * 调用getTypes方法获取@EnableConfigurationProperties注解的属性值XxxProperties；
		 * 调用register方法将获取的属性值XxxProperties注册到Spring容器中，用于以后和外部属性绑定时使用。
		 * @param metadata
		 * @param registry
		 */
		@Override
		public void registerBeanDefinitions(AnnotationMetadata metadata,
				BeanDefinitionRegistry registry) {
			// （1）得到@EnableConfigurationProperties注解的所有属性值,
			// 比如@EnableConfigurationProperties(ServerProperties.class),那么得到的值是ServerProperties.class
			// （2）然后再将得到的@EnableConfigurationProperties注解的所有属性值注册到容器中
			getTypes(metadata).forEach((type) -> register(registry,
					(ConfigurableListableBeanFactory) registry, type));
		}

		/**
		 * getTypes方法里面的逻辑很简单即将@EnableConfigurationProperties注解里面的属性值XxxProperties
		 * （比如ServerProperties.class）取出并装进List集合并返回。
		 *
		 * 由getTypes方法拿到@EnableConfigurationProperties注解里面的属性值XxxProperties
		 * （比如ServerProperties.class）后，此时再遍历将XxxProperties逐个注册进Spring容器中
		 * @param metadata
		 * @return
		 */
		private List<Class<?>> getTypes(AnnotationMetadata metadata) {
			// 得到@EnableConfigurationProperties注解的所有属性值,
			// 比如@EnableConfigurationProperties(ServerProperties.class),那么得到的值是ServerProperties.class
			MultiValueMap<String, Object> attributes = metadata
					.getAllAnnotationAttributes(
							EnableConfigurationProperties.class.getName(), false);
			// 将属性值取出装进List集合并返回
			return collectClasses((attributes != null) ? attributes.get("value")
					: Collections.emptyList());
		}

		private List<Class<?>> collectClasses(List<?> values) {
			return values.stream().flatMap((value) -> Arrays.stream((Object[]) value))
					.map((o) -> (Class<?>) o).filter((type) -> void.class != type)
					.collect(Collectors.toList());
		}

		private void register(BeanDefinitionRegistry registry,
				ConfigurableListableBeanFactory beanFactory, Class<?> type) {
			// 得到type的名字，一般用类的全限定名作为bean name
			String name = getName(type);
			// 根据bean name判断beanFactory容器中是否包含该bean
			if (!containsBeanDefinition(beanFactory, name)) {
				// 若不包含，那么注册bean definition
				registerBeanDefinition(registry, name, type);
			}
		}

		private String getName(Class<?> type) {
			ConfigurationProperties annotation = AnnotationUtils.findAnnotation(type,
					ConfigurationProperties.class);
			String prefix = (annotation != null) ? annotation.prefix() : "";
			return (StringUtils.hasText(prefix) ? prefix + "-" + type.getName()
					: type.getName());
		}

		private boolean containsBeanDefinition(
				ConfigurableListableBeanFactory beanFactory, String name) {
			if (beanFactory.containsBeanDefinition(name)) {
				return true;
			}
			BeanFactory parent = beanFactory.getParentBeanFactory();
			if (parent instanceof ConfigurableListableBeanFactory) {
				return containsBeanDefinition((ConfigurableListableBeanFactory) parent,
						name);
			}
			return false;
		}

		private void registerBeanDefinition(BeanDefinitionRegistry registry, String name,
				Class<?> type) {
			assertHasAnnotation(type);
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClass(type);
			registry.registerBeanDefinition(name, definition);
		}

		private void assertHasAnnotation(Class<?> type) {
			Assert.notNull(
					AnnotationUtils.findAnnotation(type, ConfigurationProperties.class),
					() -> "No " + ConfigurationProperties.class.getSimpleName()
							+ " annotation found on  '" + type.getName() + "'.");
		}

	}

}

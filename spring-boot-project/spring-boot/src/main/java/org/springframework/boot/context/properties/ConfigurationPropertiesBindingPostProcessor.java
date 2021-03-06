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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.PropertySources;
import org.springframework.validation.annotation.Validated;

/**
 * 这个后置处理器就尤其重要了，主要承担了将外部配置属性绑定到@ConfigurationProperties注解标注的XxxProperties类的属性中
 * （比如application.properties配置文件中设置了server.port=8081,那么8081将会绑定到ServerProperties类的port属性中）的实现逻辑。
 *
 * {@link BeanPostProcessor} to bind {@link PropertySources} to beans annotated with
 * {@link ConfigurationProperties}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Christian Dupuis
 * @author Stephane Nicoll
 * @author Madhura Bhave
 *
 * 1.InitializingBean接口的afterPropertiesSet方法会在bean属性赋值后调用，
 * 用来执行一些自定义的初始化逻辑比如检查某些强制的属性是否有被赋值，校验某些配置或给一些未被赋值的属性赋值
 *
 * 2.BeanPostProcessor接口是bean的后置处理器，其有postProcessBeforeInitialization和postProcessAfterInitialization两个勾子方法，
 * 分别会在bean初始化前后被调用来执行一些后置处理逻辑，比如检查标记接口或是否用代理包装了bean。
 */
public class ConfigurationPropertiesBindingPostProcessor implements BeanPostProcessor,
		PriorityOrdered, ApplicationContextAware, InitializingBean {

	/**
	 * The bean name that this post-processor is registered with.
	 */
	public static final String BEAN_NAME = ConfigurationPropertiesBindingPostProcessor.class
			.getName();

	/**
	 * The bean name of the configuration properties validator.
	 *
	 * 配置属性校验器名字
	 */
	public static final String VALIDATOR_BEAN_NAME = "configurationPropertiesValidator";

	/**
	 * 工厂bean相关元数据
	 */
	private ConfigurationBeanFactoryMetadata beanFactoryMetadata;

	/**
	 * 上下文
	 */
	private ApplicationContext applicationContext;

	/**
	 * 配置属性绑定器
	 */
	private ConfigurationPropertiesBinder configurationPropertiesBinder;

	/**
	 * 这里主要是给beanFactoryMetadata和configurationPropertiesBinder的属性赋值，用于后面的后置处理器方法处理属性绑定的时候用
	 * @param applicationContext
	 * @throws BeansException
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * 主要逻辑就是在执行外部属性绑定逻辑前先准备好相关元数据和配置属性绑定器，
	 * 即从Spring容器中获取到之前注册的ConfigurationBeanFactoryMetadata对象赋给
	 * ConfigurationPropertiesBindingPostProcessor后置处理器的beanFactoryMetadata属性,
	 * 还有就是新建一个ConfigurationPropertiesBinder配置属性绑定器对象并赋值给configurationPropertiesBinder属性。
	 * @throws Exception
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		// We can't use constructor injection of the application context because
		// it causes eager factory bean initialization
		// 【1】利用afterPropertiesSet这个勾子方法从容器中获取之前注册的ConfigurationBeanFactoryMetadata对象赋给beanFactoryMetadata属性
		// （问1）beanFactoryMetadata这个bean是什么时候注册到容器中的？
		// （答1）在ConfigurationPropertiesBindingPostProcessorRegistrar类的registerBeanDefinitions方法中将beanFactoryMetadata这个bean注册到容器中
		// （问2）从容器中获取beanFactoryMetadata对象后，什么时候会被用到？
		// （答2）beanFactoryMetadata对象的beansFactoryMetadata集合保存的工厂bean相关的元数据，在ConfigurationPropertiesBindingPostProcessor类
		//        要判断某个bean是否有FactoryAnnotation或FactoryMethod时会根据这个beanFactoryMetadata对象的beansFactoryMetadata集合的元数据来查找
		this.beanFactoryMetadata = this.applicationContext.getBean(
				ConfigurationBeanFactoryMetadata.BEAN_NAME,
				ConfigurationBeanFactoryMetadata.class);
		// 【2】new一个ConfigurationPropertiesBinder，用于后面的外部属性绑定时使用
		this.configurationPropertiesBinder = new ConfigurationPropertiesBinder(
				this.applicationContext, VALIDATOR_BEAN_NAME);// VALIDATOR_BEAN_NAME="configurationPropertiesValidator"
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

	/**
	 * 因为是外部配置属性后置处理器，因此这里对@ConfigurationProperties注解标注的XxxProperties类进行后置处理完成属性绑定
	 * @param bean
	 * @param beanName
	 * @return
	 * @throws BeansException
	 *
	 * ConfigurationPropertiesBindingPostProcessor类覆写的postProcessBeforeInitialization方法的做的事情就是将外部属性配置绑定到@ConfigurationProperties注解标注的XxxProperties类上，现关键步骤总结如下：
	 *
	 * 1.从bean上获取@ConfigurationProperties注解；
	 * 2.若标注有@ConfigurationProperties注解的bean，那么则进行进一步的处理：
	 * 将外部配置属性值绑定到bean的属性值中后再返回bean；若没有标注有@ConfigurationProperties注解的bean，那么将直接原样返回bean。
	 *
	 *  ## 注意：后置处理器默认会对每个容器中的bean进行后置处理，因为这里只针对标注有@ConfigurationProperties注解的bean进行外部属性绑定，因此没有标注@ConfigurationProperties注解的bean将不会被处理。
	 */
	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName)
			throws BeansException {
		// 注意，BeanPostProcessor后置处理器默认会对所有的bean进行处理，因此需要根据bean的一些条件进行过滤得到最终要处理的目的bean，
		// 这里的过滤条件就是判断某个bean是否有@ConfigurationProperties注解
		// 【1】从bean上获取@ConfigurationProperties注解,若bean有标注，那么返回该注解；若没有，则返回Null。比如ServerProperty上标注了@ConfigurationProperties注解
		ConfigurationProperties annotation = getAnnotation(bean, beanName,
				ConfigurationProperties.class);
		// 【2】若标注有@ConfigurationProperties注解的bean，那么则进行进一步处理：将配置文件的配置注入到bean的属性值中
		if (annotation != null) {
			/********主线，重点关注】********/
			bind(bean, beanName, annotation);
		}
		// 【3】返回外部配置属性值绑定后的bean（一般是XxxProperties对象）
		return bean;
	}

	private void bind(Object bean, String beanName, ConfigurationProperties annotation) {
		// 【1】得到bean的类型，比如ServerPropertie这个bean得到的类型是：org.springframework.boot.autoconfigure.web.ServerProperties
		ResolvableType type = getBeanType(bean, beanName);
		// 【2】获取bean上标注的@Validated注解
		Validated validated = getAnnotation(bean, beanName, Validated.class);
		// 若标注有@Validated注解的话则跟@ConfigurationProperties注解一起组成一个Annotation数组
		Annotation[] annotations = (validated != null)
				? new Annotation[] { annotation, validated }
				: new Annotation[] { annotation };
		// 【3】返回一个绑定了XxxProperties类的Bindable对象target，这个target对象即被外部属性值注入的目标对象
		// （比如封装了标注有@ConfigurationProperties注解的ServerProperties对象的Bindable对象）
		Bindable<?> target = Bindable.of(type).withExistingValue(bean)
				.withAnnotations(annotations); // 设置annotations属性数组
		try {
			// 【4】执行外部配置属性绑定逻辑
			/********【主线，重点关注】********/
			this.configurationPropertiesBinder.bind(target);
		}
		catch (Exception ex) {
			throw new ConfigurationPropertiesBindException(beanName, bean, annotation,
					ex);
		}
	}

	private ResolvableType getBeanType(Object bean, String beanName) {
		// 首先获取有没有工厂方法
		Method factoryMethod = this.beanFactoryMetadata.findFactoryMethod(beanName);
		// 若有工厂方法
		if (factoryMethod != null) {
			return ResolvableType.forMethodReturnType(factoryMethod);
		}
		// 没有工厂方法，则说明是普通的配置类
		return ResolvableType.forClass(bean.getClass());
	}

	private <A extends Annotation> A getAnnotation(Object bean, String beanName,
			Class<A> type) {
		A annotation = this.beanFactoryMetadata.findFactoryAnnotation(beanName, type);
		if (annotation == null) {
			annotation = AnnotationUtils.findAnnotation(bean.getClass(), type);
		}
		return annotation;
	}

}

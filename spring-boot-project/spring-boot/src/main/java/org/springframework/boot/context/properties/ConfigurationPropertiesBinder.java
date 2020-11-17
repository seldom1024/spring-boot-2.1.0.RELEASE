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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.IgnoreErrorsBindHandler;
import org.springframework.boot.context.properties.bind.handler.IgnoreTopLevelConverterNotFoundBindHandler;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.PropertySources;
import org.springframework.util.Assert;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

/**
 * Internal class by the {@link ConfigurationPropertiesBindingPostProcessor} to handle the
 * actual {@link ConfigurationProperties} binding.
 *
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ConfigurationPropertiesBinder {

	private final ApplicationContext applicationContext;

	private final PropertySources propertySources;

	private final Validator configurationPropertiesValidator;

	private final boolean jsr303Present;

	private volatile Validator jsr303Validator;

	private volatile Binder binder;

	/**
	 * 可以看到在构造ConfigurationPropertiesBinder对象时主要给其相关属性赋值（一般构造器逻辑都是这样）：
	 *
	 * 1.给applicationContext属性赋值注入上下文对象；
	 * 2.给propertySources属性赋值，属性源即外部配置值比如application.properties配置的属性值，
	 * 	注意这里的属性源是由ConfigFileApplicationListener这个监听器负责读取的，ConfigFileApplicationListener将会在后面源码分析章节中详述。
	 * 3.给configurationPropertiesValidator属性赋值，值来自Spring容器中名为configurationPropertiesValidator的bean。
	 * 4.给jsr303Present属性赋值，当javax.validation.Validator,javax.validation.ValidatorFactory和
	 *	 javax.validation.bootstrap.GenericBootstrap"这三个类同时存在于classpath中jsr303Present属性值才为true。
	 *
	 * ## 关于JSR303：JSR-303是JAVA EE 6中的一项子规范，叫做Bean Validation，Hibernate Validator是Bean Validation的参考实现 。
	 * Hibernate Validator提供了JSR 303规范中所有内置constraint 的实现，除此之外还有一些附加的constraint。
	 * @param applicationContext
	 * @param validatorBeanName
	 */
	ConfigurationPropertiesBinder(ApplicationContext applicationContext,
			String validatorBeanName) {
		this.applicationContext = applicationContext;
		// 将applicationContext封装到PropertySourcesDeducer对象中并返回
		this.propertySources = new PropertySourcesDeducer(applicationContext)
				.getPropertySources();// 获取属性源，主要用于在ConfigurableListableBeanFactory的后置处理方法postProcessBeanFactory中处理
		// 如果没有配置validator的话，这里一般返回的是null
		this.configurationPropertiesValidator = getConfigurationPropertiesValidator(
				applicationContext, validatorBeanName);
		// 检查实现JSR-303规范的bean校验器相关类在classpath中是否存在
		this.jsr303Present = ConfigurationPropertiesJsr303Validator
				.isJsr303Present(applicationContext);
	}

	/**
	 * 先获取target对象（对应XxxProperties类）上的@ConfigurationProperties注解和校验器（若有）;
	 * 然后再根据获取的的@ConfigurationProperties注解和校验器来获得BindHandler对象，BindHandler的作用是用于在属性绑定时来处理一些附件逻辑;在<font color=blue>8.1节分析</font>.
	 * 最后再获取一个Binder对象，调用其bind方法来执行外部属性绑定的逻辑
	 * @param target
	 */
	public void bind(Bindable<?> target) {
		//【1】得到@ConfigurationProperties注解
		ConfigurationProperties annotation = target
				.getAnnotation(ConfigurationProperties.class);
		Assert.state(annotation != null,
				() -> "Missing @ConfigurationProperties on " + target);
		// 【2】得到Validator对象集合，用于属性校验
		List<Validator> validators = getValidators(target);
		// 【3】得到BindHandler对象（默认是IgnoreTopLevelConverterNotFoundBindHandler对象），
		// 用于对ConfigurationProperties注解的ignoreUnknownFields等属性的处理
		BindHandler bindHandler = getBindHandler(annotation, validators);
		// 【4】得到一个Binder对象，并利用其bind方法执行外部属性绑定逻辑
		/********************【主线，重点关注】********************/
		getBinder().bind(annotation.prefix(), target, bindHandler);
	}

	private Validator getConfigurationPropertiesValidator(
			ApplicationContext applicationContext, String validatorBeanName) {
		if (applicationContext.containsBean(validatorBeanName)) {
			return applicationContext.getBean(validatorBeanName, Validator.class);
		}
		return null;
	}

	private List<Validator> getValidators(Bindable<?> target) {
		List<Validator> validators = new ArrayList<>(3);
		if (this.configurationPropertiesValidator != null) {
			validators.add(this.configurationPropertiesValidator);
		}
		if (this.jsr303Present && target.getAnnotation(Validated.class) != null) {
			validators.add(getJsr303Validator());
		}
		if (target.getValue() != null && target.getValue().get() instanceof Validator) {
			validators.add((Validator) target.getValue().get());
		}
		return validators;
	}

	private Validator getJsr303Validator() {
		if (this.jsr303Validator == null) {
			this.jsr303Validator = new ConfigurationPropertiesJsr303Validator(
					this.applicationContext);
		}
		return this.jsr303Validator;
	}

	/**
	 * 注意BindHandler的设计技巧，应该是责任链模式，非常巧妙，值得借鉴
	 * @param annotation
	 * @param validators
	 * @return
	 *
	 *
	 * getBindHandler方法的逻辑很简单，主要是根据传入的@ConfigurationProperties注解和validators校验器来创建不同的BindHandler具体实现类：
	 *
	 * 首先new一个IgnoreTopLevelConverterNotFoundBindHandler作为默认的BindHandler;
	 * 若@ConfigurationProperties注解的属性ignoreInvalidFields值为true，那么再new一个IgnoreErrorsBindHandler对象，把刚才新建的IgnoreTopLevelConverterNotFoundBindHandler对象作为构造参数传入赋值给AbstractBindHandler父类的parent属性；
	 * 若@ConfigurationProperties注解的属性ignoreUnknownFields值为false，那么再new一个UnboundElementsSourceFilter对象，把之前构造的BindHandler对象作为构造参数传入赋值给AbstractBindHandler父类的parent属性；
	 * ......以此类推，前一个handler对象作为后一个hangdler对象的构造参数，就这样利用AbstractBindHandler父类的parent属性将每一个handler链起来，最后再得到最终构造的handler。
	 *
	 *
	 * 这个就是责任链模式。我们学习源码，同时也是学习别人怎么熟练运用设计模式。
	 * 责任链模式的应用案例有很多，比如Dubbo的各种Filter们（比如AccessLogFilter是用来记录服务的访问日志的，ExceptionFilter是用来处理异常的...），
	 * 我们一开始学习java web时的Servlet的Filter,MyBatis的Plugin们以及Netty的Pipeline都采用了责任链模式。
	 */
	private BindHandler getBindHandler(ConfigurationProperties annotation,
			List<Validator> validators) {
		// 新建一个IgnoreTopLevelConverterNotFoundBindHandler对象，这是个默认的BindHandler对象
		BindHandler handler = new IgnoreTopLevelConverterNotFoundBindHandler();
		// 若注解@ConfigurationProperties的ignoreInvalidFields属性设置为true，
		// 则说明可以忽略无效的配置属性例如类型错误，此时新建一个IgnoreErrorsBindHandler对象
		if (annotation.ignoreInvalidFields()) {
			handler = new IgnoreErrorsBindHandler(handler);
		}
		// 若注解@ConfigurationProperties的ignoreUnknownFields属性设置为true，
		// 则说明配置文件配置了一些未知的属性配置，此时新建一个ignoreUnknownFields对象
		if (!annotation.ignoreUnknownFields()) {
			UnboundElementsSourceFilter filter = new UnboundElementsSourceFilter();
			handler = new NoUnboundElementsBindHandler(handler, filter);
		}
		// 如果@Valid注解不为空，则创建一个ValidationBindHandler对象
		if (!validators.isEmpty()) {
			handler = new ValidationBindHandler(handler,
					validators.toArray(new Validator[0]));
		}
		// 遍历获取的ConfigurationPropertiesBindHandlerAdvisor集合，
		// ConfigurationPropertiesBindHandlerAdvisor目前只在测试类中有用到
		for (ConfigurationPropertiesBindHandlerAdvisor advisor : getBindHandlerAdvisors()) {
			// 对handler进一步处理
			handler = advisor.apply(handler);
		}
		// 返回handler
		return handler;
	}

	private List<ConfigurationPropertiesBindHandlerAdvisor> getBindHandlerAdvisors() {
		return this.applicationContext
				.getBeanProvider(ConfigurationPropertiesBindHandlerAdvisor.class)
				.orderedStream().collect(Collectors.toList());
	}

	private Binder getBinder() {
		// Binder是一个能绑定ConfigurationPropertySource的容器对象
		if (this.binder == null) {
			// 新建一个Binder对象，这个binder对象封装了ConfigurationPropertySources，
			// PropertySourcesPlaceholdersResolver，ConversionService和PropertyEditorInitializer对象
			this.binder = new Binder(getConfigurationPropertySources(), // 将PropertySources对象封装成SpringConfigurationPropertySources对象并返回
					getPropertySourcesPlaceholdersResolver(), getConversionService(), // 将PropertySources对象封装成PropertySourcesPlaceholdersResolver对象并返回，从容器中获取到ConversionService对象
					getPropertyEditorInitializer()); // 得到Consumer<PropertyEditorRegistry>对象，这些初始化器用来配置property editors，property editors通常可以用来转换值
		}
		// 返回binder
		return this.binder;
	}

	private Iterable<ConfigurationPropertySource> getConfigurationPropertySources() {
		return ConfigurationPropertySources.from(this.propertySources);
	}

	private PropertySourcesPlaceholdersResolver getPropertySourcesPlaceholdersResolver() {
		return new PropertySourcesPlaceholdersResolver(this.propertySources);
	}

	private ConversionService getConversionService() {
		return new ConversionServiceDeducer(this.applicationContext)
				.getConversionService();
	}

	private Consumer<PropertyEditorRegistry> getPropertyEditorInitializer() {
		if (this.applicationContext instanceof ConfigurableApplicationContext) {
			return ((ConfigurableApplicationContext) this.applicationContext)
					.getBeanFactory()::copyRegisteredEditorsTo;
		}
		return null;
	}

}

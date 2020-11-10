package sample.conditionSample;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @description: 实现spring 的Condition接口，并且重写matches()方法
 * 				如果@ConditionalOnLinux的注解属性environment是linux就返回true
 * @author: Seldom
 * @time: 2020/11/8 10:19
 */
public class LinuxCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		// 获得注解@ConditionalOnLinux的所有属性
		List<AnnotationAttributes> allAnnotationAttributes = annotationAttributesFromMultiValueMap(
				metadata.getAllAnnotationAttributes(ConditionalOnLinux.class.getName()));
		for (AnnotationAttributes annotationAttributes : allAnnotationAttributes) {
			// 获得注解@ConditionalOnLinux的environment属性
			String environment = annotationAttributes.getString("environment");
			// 若environment等于linux，则返回true
			if ("linux".equals(environment)) {
				return true;
			}
		}
		return false;
	}

	private List<AnnotationAttributes> annotationAttributesFromMultiValueMap(MultiValueMap<String, Object> allAnnotationAttributes) {
		AnnotationAttributes annotationAttributes = new AnnotationAttributes(allAnnotationAttributes.toSingleValueMap());
		AnnotationAttributes environment = annotationAttributes.getAnnotation("environment");
		AnnotationAttributes[] annotationArray = annotationAttributes.getAnnotationArray("environment");
		return Arrays.asList(annotationArray);
	}


}

package com.emie.designpm.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/** Prevents background repositories from colliding with same-named primary repositories. */
public class BackgroundRepositoryBeanNameGenerator implements BeanNameGenerator {
    @Override
    public String generateBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
        String className = definition.getBeanClassName();
        String simpleName = className == null ? "repository" : className.substring(className.lastIndexOf('.') + 1);
        if (simpleName.endsWith("Repository")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Repository".length());
        }
        return "background" + Character.toUpperCase(simpleName.charAt(0)) + simpleName.substring(1) + "Repository";
    }
}

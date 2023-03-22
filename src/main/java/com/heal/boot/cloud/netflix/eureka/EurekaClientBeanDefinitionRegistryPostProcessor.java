package com.heal.boot.cloud.netflix.eureka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.cloud.context.scope.GenericScope;

/**
 * eurekaClient/applicationInfoManager 实例在 @ConditionalOnRefreshScope 情况下每次 refresh，会销毁重新创建。实例会取消注册，再重新注册到eureka。导致当前应用refresh时，其他应用从eureka获取该应用实例的时候存在找不到的情况
 * 此处扩展后，refresh不再会重新创建eurekaClient/applicationInfoManager实例
 * @author wenqi.huang
 * @auther gyf
 * 2018/4/27 .
 */
public class EurekaClientBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(EurekaClientBeanDefinitionRegistryPostProcessor.class);

    private static final String EUREKA_CLIENT_BEAN_ID = "eurekaClient";
    private static final String APPLICATION_INFO_MANAGER_BEAN_ID = "eurekaApplicationInfoManager";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        //修改eurekaClient的bean定义
        BeanDefinition eurekaClientBeanDefinition = null;
        BeanDefinition scopedEurekaClientBeanDefinition = null;
        try {
            eurekaClientBeanDefinition = registry.getBeanDefinition(EUREKA_CLIENT_BEAN_ID);
            //如果eurekaClient是@RefreshScope的，则一定会有scopedTarget开头的bean
            scopedEurekaClientBeanDefinition = registry.getBeanDefinition(GenericScope.SCOPED_TARGET_PREFIX + EUREKA_CLIENT_BEAN_ID);
        } catch (NoSuchBeanDefinitionException e) {
            //Ignore
            return;
        }

        if (eurekaClientBeanDefinition == null || scopedEurekaClientBeanDefinition == null) {
            return;
        }

        BeanDefinition eurekaClientOriginatingBeanDefinition = eurekaClientBeanDefinition.getOriginatingBeanDefinition();
        if (eurekaClientOriginatingBeanDefinition == null) {
            throw new IllegalStateException("[NOTIFYME]如果你看到这个异常，说明我们不支持spring-cloud或spring-boot的新版本，请联系中间件团队解决此问题");
        }

        registry.removeBeanDefinition(GenericScope.SCOPED_TARGET_PREFIX + EUREKA_CLIENT_BEAN_ID);

        //spring内部生成代理beanDefinition时会把原始的beanDefinition的AutowireCandidate设为false，导致无法注入到其他bean中，这里把这个配置还原回去
        eurekaClientOriginatingBeanDefinition.setAutowireCandidate(true);
        eurekaClientOriginatingBeanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);//把scope由refresh改为singleton

        registry.registerBeanDefinition(EUREKA_CLIENT_BEAN_ID, eurekaClientOriginatingBeanDefinition);


        //修改applicationInfoManager的bean定义
        BeanDefinition infoManagerBeanDefinition = null;
        BeanDefinition scopedInfoManagerBeanDefinition = null;
        try {
            infoManagerBeanDefinition = registry.getBeanDefinition(APPLICATION_INFO_MANAGER_BEAN_ID);
            //如果ApplicationInfoManager是@RefreshScope的，则一定会有scopedTarget开头的bean
            scopedInfoManagerBeanDefinition = registry.getBeanDefinition(GenericScope.SCOPED_TARGET_PREFIX + APPLICATION_INFO_MANAGER_BEAN_ID);
        } catch (NoSuchBeanDefinitionException e) {
            //Ignore
            return;
        }

        if (infoManagerBeanDefinition == null || scopedInfoManagerBeanDefinition == null) {
            return;
        }

        BeanDefinition infoManagerOriginatingBeanDefinition = infoManagerBeanDefinition.getOriginatingBeanDefinition();
        if (infoManagerOriginatingBeanDefinition == null) {
            throw new IllegalStateException("[NOTIFYME]如果你看到这个异常，说明我们不支持spring-cloud或spring-boot的新版本，请联系中间件团队解决此问题");
        }

        registry.removeBeanDefinition(GenericScope.SCOPED_TARGET_PREFIX + APPLICATION_INFO_MANAGER_BEAN_ID);

        //spring内部生成代理beanDefinition时会把原始的beanDefinition的AutowireCandidate设为false，导致无法注入到其他bean中，这里把这个配置还原回去
        infoManagerOriginatingBeanDefinition.setAutowireCandidate(true);
        infoManagerOriginatingBeanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);//把scope由refresh改为singleton

        registry.registerBeanDefinition(APPLICATION_INFO_MANAGER_BEAN_ID, infoManagerOriginatingBeanDefinition);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        //do nothing
    }

}

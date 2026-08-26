package com.guanxian.platform.ai.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProviderProperties.class, RagProperties.class})
public class RagConfiguration {
}

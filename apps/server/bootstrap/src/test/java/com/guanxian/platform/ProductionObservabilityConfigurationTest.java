package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionObservabilityConfigurationTest {

    @Test
    void productionProfileUsesStructuredLogsAndBaseConfigExposesPrometheusHistograms() throws java.io.IOException {
        var production = load("application-production.yml");
        var base = loadMainConfiguration();

        assertEquals("logstash", production.getProperty("logging.structured.format.console"));
        assertEquals("true", production.getProperty("logging.include-application-name"));
        assertTrue(new FileSystemResource("src/main/resources/application.yml").getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8).contains("include: health,info,prometheus"));
        assertEquals("unrestricted", base.getProperty("management.endpoint.prometheus.access"));
        assertEquals("true", base.getProperty("management.prometheus.metrics.export.enabled"));
        assertEquals("true", base.getProperty(
                "management.metrics.distribution.percentiles-histogram.http.server.requests"));
    }

    private static java.util.Properties load(String resource) {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        java.util.Properties properties = factory.getObject();
        if (properties == null) {
            throw new IllegalStateException("could not load " + resource);
        }
        return properties;
    }

    private static java.util.Properties loadMainConfiguration() {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource("src/main/resources/application.yml"));
        java.util.Properties properties = factory.getObject();
        if (properties == null) {
            throw new IllegalStateException("could not load main application.yml");
        }
        return properties;
    }
}

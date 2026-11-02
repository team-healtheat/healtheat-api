package com.healtheat.healtheat_api.ai;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    // Spring AI 1.0 supplies a temperature by default, which GPT-5 mini rejects.
    @Bean
    static BeanPostProcessor omitUnsupportedSamplingParameters() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof OpenAiChatProperties properties) {
                    properties.getOptions().setTemperature(null);
                    properties.getOptions().setTopP(null);
                }
                return bean;
            }
        };
    }
}

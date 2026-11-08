package com.ochavada.backend.rates;

import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.rates.pricing.StayPricingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RatesConfig {

    @Bean
    public StayPricingEngine stayPricingEngine(PropertyProperties properties) {
        return new StayPricingEngine(properties.weekendNights());
    }
}

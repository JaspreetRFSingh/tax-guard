package com.taxguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taxguard.domain.TaxCalculationEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, TaxCalculationEvent> consumerFactory() {
        JsonDeserializer<TaxCalculationEvent> deserializer =
            new JsonDeserializer<>(TaxCalculationEvent.class);
        deserializer.setRemoveTypeHeaders(false);
        deserializer.addTrustedPackages("com.taxguard.domain");

        return new DefaultKafkaConsumerFactory<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG,           "taxguard-anomaly-detector",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "latest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true
        ), new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaxCalculationEvent>
    kafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, TaxCalculationEvent>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3); // 3 consumer threads = 3 partitions processed in parallel
        return factory;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule()); // handle Instant serialization
        return mapper;
    }
}

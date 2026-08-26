package com.jaswal.gpxfileprocessor.common.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String MAIN_EXCHANGE = "main.topic.exchange";
    public static final String Q1_QUEUE = "q1.ingest.queue";
    public static final String INGEST_ROUTING_KEY = "job.ingest";
    public static final String SERVICE_ROUTING_KEY = "job.service.ready";
    public static final String Q2_QUEUE = "q2.math.queue";
    public static final String SERVICE_BINDING_PATTERN = "job.service.*";

    @Bean
    public TopicExchange mainExchange() {
        return new TopicExchange(MAIN_EXCHANGE);
    }

    @Bean
    public Queue q1Queue() {
        return new Queue(Q1_QUEUE);
    }

    @Bean
    public Binding q1Binding() {
        return BindingBuilder.bind(q1Queue()).to(mainExchange()).with(INGEST_ROUTING_KEY);
    }

    @Bean
    public Queue q2Queue() {
        return new Queue(Q2_QUEUE);
    }

    @Bean
    public Binding q2Binding() {
        return BindingBuilder.bind(q2Queue()).to(mainExchange()).with(SERVICE_BINDING_PATTERN);
    }
}
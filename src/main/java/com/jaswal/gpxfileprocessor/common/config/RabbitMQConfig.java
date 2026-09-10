package com.jaswal.gpxfileprocessor.common.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String MAIN_EXCHANGE = "main.topic.exchange";
    public static final String Q1_QUEUE = "q1.ingest.queue";
    public static final String INGEST_ROUTING_KEY = "job.ingest";
    public static final String SERVICE_ROUTING_KEY = "job.service.ready";
    public static final String Q2_QUEUE = "q2.math.queue";
    public static final String Q3_QUEUE = "q3.enrichment.queue";
    public static final String SERVICE_BINDING_PATTERN = "job.service.*";
    public static final String GENERATOR_EXCHANGE = "generator.exchange";
    public static final String Q4_QUEUE = "q4.generator.queue";
    public static final String GENERATOR_ROUTING_KEY = "job.generator";
    public static final String DELAYED_RETRY_EXCHANGE = "retry.delayed.exchange";
    public static final String Q1_RETRY_ROUTING_KEY = "retry.q1";
    public static final String Q2_RETRY_ROUTING_KEY = "retry.q2";
    public static final String Q3_RETRY_ROUTING_KEY = "retry.q3";
    public static final String Q4_RETRY_ROUTING_KEY = "retry.q4";
    public static final String DLQ1_QUEUE = "dlq1.queue";
    public static final String DLQ2_QUEUE = "dlq2.queue";
    public static final String DLQ3_QUEUE = "dlq3.queue";
    public static final String DLQ4_QUEUE = "dlq4.queue";

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

    @Bean
    public Queue q3Queue() {
        return new Queue(Q3_QUEUE);
    }

    @Bean
    public Binding q3Binding() {
        return BindingBuilder.bind(q3Queue()).to(mainExchange()).with(SERVICE_BINDING_PATTERN);
    }

    @Bean
    public DirectExchange generatorExchange() {
        return new DirectExchange(GENERATOR_EXCHANGE);
    }

    @Bean
    public Queue q4Queue() {
        return new Queue(Q4_QUEUE);
    }

    @Bean
    public Binding q4Binding() {
        return BindingBuilder.bind(q4Queue()).to(generatorExchange()).with(GENERATOR_ROUTING_KEY);
    }

    @Bean
    public Queue dlq1Queue() {
        return new Queue(DLQ1_QUEUE);
    }

    @Bean
    public Binding q1RetryBinding() {
        return BindingBuilder.bind(q1Queue()).to(delayedRetryExchange()).with(Q1_RETRY_ROUTING_KEY).noargs();
    }

    @Bean
    public Queue dlq2Queue() {
        return new Queue(DLQ2_QUEUE);
    }

    @Bean
    public Binding q2RetryBinding() {
        return BindingBuilder.bind(q2Queue()).to(delayedRetryExchange()).with(Q2_RETRY_ROUTING_KEY).noargs();
    }

    @Bean
    public Queue dlq3Queue() {
        return new Queue(DLQ3_QUEUE);
    }

    @Bean
    public Binding q3RetryBinding() {
        return BindingBuilder.bind(q3Queue()).to(delayedRetryExchange()).with(Q3_RETRY_ROUTING_KEY).noargs();
    }

    @Bean
    public Queue dlq4Queue() {
        return new Queue(DLQ4_QUEUE);
    }

    @Bean
    public Binding q4RetryBinding() {
        return BindingBuilder.bind(q4Queue()).to(delayedRetryExchange()).with(Q4_RETRY_ROUTING_KEY).noargs();
    }

    @Bean
    public CustomExchange delayedRetryExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(DELAYED_RETRY_EXCHANGE, "x-delayed-message", true, false, args);
    }
}
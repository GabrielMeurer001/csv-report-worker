package dev.csvreport;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReportQueue {
    static final String EXCHANGE = "reports";
    static final String JOBS = "reports.jobs";
    static final String DEAD_LETTER_EXCHANGE = "reports.dead";
    static final String DEAD_LETTERS = "reports.dlq";
    private static final Logger log = LoggerFactory.getLogger(ReportQueue.class);

    private final RabbitTemplate rabbit;

    ReportQueue(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Bean
    Declarables reportTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE);
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE);
        Queue jobs = QueueBuilder.durable(JOBS)
            .deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(DEAD_LETTERS).build();
        Queue deadLetters = QueueBuilder.durable(DEAD_LETTERS).build();
        return new Declarables(exchange, deadLetterExchange, jobs, deadLetters,
            BindingBuilder.bind(jobs).to(exchange).with(JOBS),
            BindingBuilder.bind(deadLetters).to(deadLetterExchange).with(DEAD_LETTERS));
    }

    boolean publish(UUID id) {
        CorrelationData publication = new CorrelationData(id.toString());
        try {
            rabbit.convertAndSend(EXCHANGE, JOBS, id.toString(), publication);
            return publication.getFuture().get(3, TimeUnit.SECONDS).ack() && publication.getReturned() == null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Confirmação de publicação interrompida para {}", id);
            return false;
        } catch (AmqpException | ExecutionException | TimeoutException exception) {
            log.warn("Falha ao publicar relatório {}", id, exception);
            return false;
        }
    }
}

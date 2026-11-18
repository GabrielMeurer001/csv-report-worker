package dev.csvreport;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
class ReportWorker {
    private static final Logger log = LoggerFactory.getLogger(ReportWorker.class);
    private static final int ATTEMPTS = 3;
    private final ReportStore store;
    private final SalesCsv csv;

    ReportWorker(ReportStore store, SalesCsv csv) {
        this.store = store;
        this.csv = csv;
    }

    @RabbitListener(queues = ReportQueue.JOBS)
    void consume(String payload, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        UUID id;
        try {
            id = UUID.fromString(payload);
        } catch (IllegalArgumentException exception) {
            log.warn("Mensagem sem UUID válido enviada à DLQ");
            channel.basicReject(tag, false);
            return;
        }

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                process(id);
            } catch (RuntimeException exception) {
                log.warn("Falha no relatório {} (tentativa {}/{})", id, attempt, ATTEMPTS, exception);
                if (attempt == ATTEMPTS) {
                    recordExhaustion(id);
                    channel.basicReject(tag, false);
                    return;
                }
                try {
                    Thread.sleep(Duration.ofSeconds(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    channel.basicReject(tag, true);
                    return;
                }
                continue;
            }
            channel.basicAck(tag, false);
            return;
        }
    }

    private void process(UUID id) {
        Optional<byte[]> data = store.start(id);
        if (data.isEmpty()) {
            return;
        }
        try {
            store.complete(id, csv.summarize(data.get()));
        } catch (SalesCsv.InvalidCsv exception) {
            store.fail(id, "processing", new ReportStore.ReportError("INVALID_CSV", exception.line(), exception.getMessage()));
        }
    }

    private void recordExhaustion(UUID id) {
        try {
            store.failProcessing(id, new ReportStore.ReportError("PROCESSING_FAILED", null,
                "O relatório falhou após três tentativas."));
        } catch (RuntimeException exception) {
            log.error("Não foi possível salvar a falha final do relatório {}", id, exception);
        }
    }
}

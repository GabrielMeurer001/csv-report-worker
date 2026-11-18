package dev.csvreport;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/reports")
class ReportController {
    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private static final ReportStore.ReportError QUEUE_UNAVAILABLE = new ReportStore.ReportError(
        "QUEUE_UNAVAILABLE", null, "A fila está indisponível. Tente enviar o arquivo novamente mais tarde.");

    private final ReportStore store;
    private final ReportQueue queue;

    ReportController(ReportStore store, ReportQueue queue) {
        this.store = store;
        this.queue = queue;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Submission> submit(@RequestParam("file") MultipartFile file) throws IOException {
        UUID id = UUID.randomUUID();
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().pathSegment(id.toString()).build().toUri();
        store.create(id, file.getBytes());
        if (queue.publish(id)) {
            return ResponseEntity.accepted().location(location).body(new Submission(id, null));
        }

        try {
            if (!store.fail(id, "pending", QUEUE_UNAVAILABLE)) {
                return ResponseEntity.accepted().location(location).body(new Submission(id, null));
            }
        } catch (DataAccessException exception) {
            log.error("Não foi possível registrar a falha de publicação para {}", id, exception);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).location(location)
            .body(new Submission(id, QUEUE_UNAVAILABLE));
    }

    @GetMapping("/{id}")
    ResponseEntity<ReportStore.Report> get(@PathVariable UUID id) {
        return ResponseEntity.of(store.find(id));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ReportStore.ReportError> databaseUnavailable(DataAccessException exception) {
        log.error("Falha de acesso ao PostgreSQL", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ReportStore.ReportError(
            "DATABASE_UNAVAILABLE", null, "O banco de dados está indisponível. Tente novamente mais tarde."));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Submission(UUID id, ReportStore.ReportError error) {}
}

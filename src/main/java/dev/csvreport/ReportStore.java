package dev.csvreport;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ReportStore {
    private final JdbcClient db;
    private final JsonMapper json;

    ReportStore(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    void create(UUID id, byte[] csv) {
        db.sql("INSERT INTO report_jobs (id, status, csv_data) VALUES (:id, 'pending', :csv)")
            .param("id", id).param("csv", csv).update();
    }

    Optional<Report> find(UUID id) {
        return db.sql("SELECT status, result, error FROM report_jobs WHERE id = :id")
            .param("id", id)
            .query((row, index) -> new Report(id, row.getString("status"),
                readJson(row.getString("result")), readJson(row.getString("error"))))
            .optional();
    }

    Optional<byte[]> start(UUID id) {
        return db.sql("""
            UPDATE report_jobs SET status = 'processing', updated_at = now()
            WHERE id = :id AND status IN ('pending', 'processing')
            RETURNING csv_data
            """).param("id", id).query((row, index) -> row.getBytes("csv_data")).optional();
    }

    void complete(UUID id, SalesCsv.Summary result) {
        db.sql("""
            UPDATE report_jobs SET status = 'completed', result = CAST(:result AS jsonb), updated_at = now()
            WHERE id = :id AND status = 'processing'
            """).param("id", id).param("result", json.writeValueAsString(result)).update();
    }

    boolean fail(UUID id, String expectedStatus, ReportError error) {
        return db.sql("""
            UPDATE report_jobs SET status = 'failed', error = CAST(:error AS jsonb), updated_at = now()
            WHERE id = :id AND status = :expectedStatus
            """).param("id", id).param("expectedStatus", expectedStatus)
            .param("error", json.writeValueAsString(error)).update() == 1;
    }

    void failProcessing(UUID id, ReportError error) {
        db.sql("""
            UPDATE report_jobs SET status = 'failed', error = CAST(:error AS jsonb), updated_at = now()
            WHERE id = :id AND status IN ('pending', 'processing')
            """).param("id", id).param("error", json.writeValueAsString(error)).update();
    }

    private JsonNode readJson(String value) {
        return value == null ? null : json.readTree(value);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(UUID id, String status, JsonNode result, JsonNode error) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReportError(String code, Long line, String message) {}
}

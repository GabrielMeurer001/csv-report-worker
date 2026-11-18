package dev.csvreport;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SalesCsvTest {
    private final SalesCsv csv = new SalesCsv();

    @Test
    void addsAmountsExactlyAndHandlesQuotedCategories() {
        var summary = csv.summarize(bytes("\uFEFFsale_id,category,amount\r\n"
            + "1,\"livros, papel\",0.10\r\n2,\"livros, papel\",0.2\r\n3,jogos,2\r\n"));

        assertThat(summary.saleCount()).isEqualTo(3);
        assertThat(summary.total()).isEqualTo("2.30");
        assertThat(summary.totalsByCategory()).isEqualTo(Map.of("livros, papel", "0.30", "jogos", "2.00"));
    }

    @Test
    void malformedQuotesReportThePhysicalLine() {
        var failure = assertThrows(SalesCsv.InvalidCsv.class, () -> csv.summarize(bytes(
            "sale_id,category,amount\n1,livros,10\n2,\"jogos,20\n")));

        assertThat(failure.line()).isEqualTo(3);
        assertThat(failure.getMessage()).contains("aspas");
    }

    @Test
    void invalidEncodingReportsThePhysicalLine() {
        byte[] invalid = bytes("sale_id,category,amount\r\n1,livros,10\r\n2,jogos,20\n");
        invalid[invalid.length - 4] = (byte) 0xff;

        assertThat(assertThrows(SalesCsv.InvalidCsv.class, () -> csv.summarize(invalid)).line()).isEqualTo(3);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

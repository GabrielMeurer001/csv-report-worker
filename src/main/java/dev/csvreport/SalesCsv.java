package dev.csvreport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
class SalesCsv {
    private static final List<String> HEADER = List.of("sale_id", "category", "amount");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]+(?:\\.[0-9]{1,2})?");

    Summary summarize(byte[] csv) {
        String text = decode(csv);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        long line = 1;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        Map<String, BigDecimal> categories = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String header = reader.readLine();
            if (header == null || !parseLine(header, line).equals(HEADER)) {
                throw new InvalidCsv(line, "O cabeçalho deve ser sale_id,category,amount.");
            }

            String row;
            while ((row = reader.readLine()) != null) {
                line++;
                List<String> fields = parseLine(row, line);
                if (fields.size() != 3) {
                    throw new InvalidCsv(line, "Informe as três colunas: sale_id, category e amount.");
                }
                String category = fields.get(1).strip();
                String amount = fields.get(2).strip();
                if (fields.getFirst().isBlank() || category.isEmpty()) {
                    throw new InvalidCsv(line, "sale_id e category são obrigatórios.");
                }
                if (!AMOUNT.matcher(amount).matches()) {
                    throw new InvalidCsv(line, "amount deve ser um valor não negativo, com ponto e até duas casas decimais.");
                }
                BigDecimal value = new BigDecimal(amount).setScale(2);
                total = total.add(value);
                categories.merge(category, value, BigDecimal::add);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao ler o CSV em memória", exception);
        }
        if (line == 1) {
            throw new InvalidCsv(2, "Inclua ao menos uma venda após o cabeçalho.");
        }
        Map<String, String> categoryTotals = new TreeMap<>();
        categories.forEach((category, value) -> categoryTotals.put(category, value.toPlainString()));
        return new Summary(line - 1, total.toPlainString(), categoryTotals);
    }

    private List<String> parseLine(String row, long line) {
        if (row.indexOf('\0') >= 0) {
            throw new InvalidCsv(line, "A linha contém um caractere nulo.");
        }
        try (CSVParser parser = CSVParser.parse(row, CSVFormat.RFC4180)) {
            List<CSVRecord> records = parser.getRecords();
            return records.isEmpty() ? List.of() : records.getFirst().toList();
        } catch (IOException | UncheckedIOException exception) {
            throw new InvalidCsv(line, "CSV inválido. Verifique as aspas e mantenha cada venda em uma linha.");
        }
    }

    private String decode(byte[] csv) {
        ByteBuffer input = ByteBuffer.wrap(csv);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(input).toString();
        } catch (CharacterCodingException exception) {
            long line = 1;
            for (int i = 0; i < input.position(); i++) {
                if (csv[i] == '\r' || (csv[i] == '\n' && (i == 0 || csv[i - 1] != '\r'))) {
                    line++;
                }
            }
            throw new InvalidCsv(line, "Salve o arquivo com codificação UTF-8.");
        }
    }

    record Summary(long saleCount, String total, Map<String, String> totalsByCategory) {}

    static final class InvalidCsv extends RuntimeException {
        private final long line;

        InvalidCsv(long line, String message) {
            super(message);
            this.line = line;
        }

        long line() {
            return line;
        }
    }
}

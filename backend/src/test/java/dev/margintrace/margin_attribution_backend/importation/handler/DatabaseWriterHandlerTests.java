package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseWriterHandlerTests {

    @Test
    void createsTableWithoutTrailingComma() {
        DatabaseWriterHandler handler = new DatabaseWriterHandler(null, null);
        Map<String, DataType> columnTypes = new LinkedHashMap<>();
        columnTypes.put("Order No", DataType.TEXT);
        columnTypes.put("Total Price", DataType.NUMERIC);

        String sql = handler.buildCreateTableSql(
                "sales_data",
                handler.buildColumnStatement(columnTypes)
        );

        assertThat(sql)
                .isEqualTo("CREATE TABLE sales_data("
                        + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                        + "order_no_0 TEXT," + System.lineSeparator()
                        + "total_price_0 NUMERIC(20,5))")
                .doesNotContain("," + System.lineSeparator() + ")");
    }

    @Test
    void createsTableAndInsertsExcelRows() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sales Data");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Order No");
            header.createCell(1).setCellValue("Total Price");

            var firstRow = sheet.createRow(1);
            firstRow.createCell(0).setCellValue("SO-001");
            firstRow.createCell(1).setCellValue(12.50);

            var secondRow = sheet.createRow(2);
            secondRow.createCell(0).setCellValue("SO-002");
            secondRow.createCell(1).setCellValue(8.25);

            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:database-writer-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE SCHEMA raw");
        RawFileReader rawFileReader = objectKey -> new ByteArrayInputStream(workbookBytes);
        DatabaseWriterHandler handler = new DatabaseWriterHandler(jdbcTemplate, rawFileReader);

        ImportContext context = new ImportContext();
        context.setObjectKey("raw/test.xlsx");
        context.setTableStructure(new TableStructure("Sales Data", 0, 0, 1, 2, 1.0));
        Map<String, DataType> columnTypes = new LinkedHashMap<>();
        columnTypes.put("Order No", DataType.TEXT);
        columnTypes.put("Total Price", DataType.NUMERIC);
        context.setColumnTypes(columnTypes);

        handler.doImport(context);

        assertThat(context.getRawTableName()).startsWith("raw.sales_data_");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + context.getRawTableName(), Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT order_no_0, total_price_0 FROM " + context.getRawTableName() + " WHERE id = 1"
        ))
                .containsEntry("ORDER_NO_0", "SO-001")
                .containsEntry("TOTAL_PRICE_0", new BigDecimal("12.50000"));
    }
}

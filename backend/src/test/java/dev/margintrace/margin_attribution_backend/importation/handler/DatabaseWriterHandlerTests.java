package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.SchemaMappingPresetCatalog;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseWriterHandlerTests {

    @Test
    void writesMappedExcelRowsIntoAnExistingWarehouseTable() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("sales");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("销售订单号");
            header.createCell(1).setCellValue("SKU");
            header.createCell(2).setCellValue("销售数量");
            header.createCell(3).setCellValue("销售金额");
            header.createCell(4).setCellValue("备注");

            var firstRow = sheet.createRow(1);
            firstRow.createCell(0).setCellValue("SO-001");
            firstRow.createCell(1).setCellValue("PRODUCT-001");
            firstRow.createCell(2).setCellValue(2.5);
            firstRow.createCell(3).setCellValue(12.50);
            firstRow.createCell(4).setCellValue("ignored source column");

            var secondRow = sheet.createRow(2);
            secondRow.createCell(0).setCellValue("SO-002");
            secondRow.createCell(1).setCellValue("PRODUCT-002");
            secondRow.createCell(2).setCellValue("3.25");
            secondRow.createCell(3).setCellValue("8.25");

            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        JdbcTemplate jdbcTemplate = createJdbcTemplate("warehouse-writer-test");
        jdbcTemplate.execute("""
                CREATE TABLE sales (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    sale_order_no VARCHAR(100) NOT NULL,
                    product_no VARCHAR(100) NOT NULL,
                    product_num NUMERIC(18, 6) NOT NULL,
                    product_total_price NUMERIC(18, 6) NOT NULL
                )
                """);

        RawFileReader rawFileReader = objectKey -> new ByteArrayInputStream(workbookBytes);
        DatabaseWriterHandler writer = new DatabaseWriterHandler(jdbcTemplate, rawFileReader);
        ImportContext context = salesContext();
        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);

        writer.doImport(context);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT sale_order_no, product_no, product_num, product_total_price
                FROM sales WHERE id = 1
                """))
                .containsEntry("SALE_ORDER_NO", "SO-001")
                .containsEntry("PRODUCT_NO", "PRODUCT-001")
                .containsEntry("PRODUCT_NUM", new BigDecimal("2.500000"))
                .containsEntry("PRODUCT_TOTAL_PRICE", new BigDecimal("12.500000"));
        assertThat(jdbcTemplate.queryForMap("SELECT product_num, product_total_price FROM sales WHERE id = 2"))
                .containsEntry("PRODUCT_NUM", new BigDecimal("3.250000"))
                .containsEntry("PRODUCT_TOTAL_PRICE", new BigDecimal("8.250000"));
    }

    @Test
    void rejectsAPartiallyPopulatedRowWhenARequiredMappedValueIsBlank() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("sales");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("销售订单号");
            header.createCell(1).setCellValue("SKU");
            header.createCell(2).setCellValue("销售数量");
            header.createCell(3).setCellValue("销售金额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("SO-001");
            row.createCell(1).setCellValue("PRODUCT-001");
            row.createCell(3).setCellValue(12.50);
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        JdbcTemplate jdbcTemplate = createJdbcTemplate("warehouse-writer-required-test");
        RawFileReader rawFileReader = objectKey -> new ByteArrayInputStream(workbookBytes);
        DatabaseWriterHandler writer = new DatabaseWriterHandler(jdbcTemplate, rawFileReader);
        ImportContext context = salesContext();
        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);

        assertThatThrownBy(() -> writer.doImport(context))
                .hasMessageContaining("Excel data insertion failed")
                .hasRootCauseMessage("Required values are blank at worksheet row 2: productQuantity");
    }

    private static JdbcTemplate createJdbcTemplate(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(dataSource);
    }

    private static ImportContext salesContext() {
        ImportContext context = new ImportContext();
        context.setObjectKey("raw/test.xlsx");
        context.setTableStructure(new TableStructure("sales", 0, 0, 4, 2, 1.0));
        Map<String, DataType> columnTypes = new LinkedHashMap<>();
        columnTypes.put("销售订单号", DataType.TEXT);
        columnTypes.put("SKU", DataType.TEXT);
        columnTypes.put("销售数量", DataType.NUMERIC);
        columnTypes.put("销售金额", DataType.NUMERIC);
        columnTypes.put("备注", DataType.TEXT);
        context.setColumnTypes(columnTypes);
        return context;
    }
}

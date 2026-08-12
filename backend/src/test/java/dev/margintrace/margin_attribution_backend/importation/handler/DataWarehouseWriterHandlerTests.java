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

class DataWarehouseWriterHandlerTests {

    @Test
    void writesOnlySchemaMappedRawColumnsIntoWarehouseTable() throws Exception {
        byte[] workbookBytes = salesWorkbook();
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.execute("CREATE SCHEMA raw");
        jdbcTemplate.execute("""
                CREATE TABLE sales (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    sale_order_no VARCHAR(100) NOT NULL,
                    product_no VARCHAR(100) NOT NULL,
                    product_num NUMERIC(18,6) NOT NULL,
                    product_total_price NUMERIC(18,6) NOT NULL
                )
                """);

        ImportContext context = salesContext();
        RawFileReader reader = objectKey -> new ByteArrayInputStream(workbookBytes);
        new DatabaseWriterHandler(jdbcTemplate, reader).doImport(context);
        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);

        new DataWarehouseWriterHandler(jdbcTemplate).doImport(context);

        assertThat(context.getWarehouseImportedRows()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + context.getRawTableName(), Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT notes_0 FROM " + context.getRawTableName() + " WHERE sales_order_no_0 = 'SO-001'",
                String.class
        )).isEqualTo("raw only");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT sale_order_no, product_no, product_num, product_total_price
                FROM sales WHERE sale_order_no = 'SO-001'
                """))
                .containsEntry("SALE_ORDER_NO", "SO-001")
                .containsEntry("PRODUCT_NO", "P-001")
                .containsEntry("PRODUCT_NUM", new BigDecimal("2.000000"))
                .containsEntry("PRODUCT_TOTAL_PRICE", new BigDecimal("25.000000"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'PUBLIC' AND table_name = 'SALES'",
                String.class
        )).doesNotContain("NOTES_0");
    }

    private static ImportContext salesContext() {
        ImportContext context = new ImportContext();
        context.setObjectKey("raw/test-sales.xlsx");
        context.setTableStructure(new TableStructure("sales", 0, 0, 4, 2, 1.0));
        Map<String, DataType> columnTypes = new LinkedHashMap<>();
        columnTypes.put("Sales Order No", DataType.TEXT);
        columnTypes.put("SKU", DataType.TEXT);
        columnTypes.put("Sales Quantity", DataType.NUMERIC);
        columnTypes.put("Sales Amount", DataType.NUMERIC);
        columnTypes.put("Notes", DataType.TEXT);
        context.setColumnTypes(columnTypes);
        return context;
    }

    private static byte[] salesWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("sales");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Sales Order No");
            header.createCell(1).setCellValue("SKU");
            header.createCell(2).setCellValue("Sales Quantity");
            header.createCell(3).setCellValue("Sales Amount");
            header.createCell(4).setCellValue("Notes");

            var firstRow = sheet.createRow(1);
            firstRow.createCell(0).setCellValue("SO-001");
            firstRow.createCell(1).setCellValue("P-001");
            firstRow.createCell(2).setCellValue(2);
            firstRow.createCell(3).setCellValue(25);
            firstRow.createCell(4).setCellValue("raw only");

            var secondRow = sheet.createRow(2);
            secondRow.createCell(0).setCellValue("SO-002");
            secondRow.createCell(1).setCellValue("P-002");
            secondRow.createCell(2).setCellValue(1);
            secondRow.createCell(3).setCellValue(8.25);
            secondRow.createCell(4).setCellValue("not mapped");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static JdbcTemplate createJdbcTemplate() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:data-warehouse-writer-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(dataSource);
    }
}

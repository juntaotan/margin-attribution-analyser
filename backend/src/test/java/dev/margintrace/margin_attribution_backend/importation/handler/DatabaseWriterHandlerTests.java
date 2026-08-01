package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseWriterHandlerTests {

    @Test
    void createsTableWithoutTrailingComma() {
        DatabaseWriterHandler handler = new DatabaseWriterHandler(null);

        Map<String, DataType> columnTypes = new LinkedHashMap<>();
        columnTypes.put("Order No", DataType.TEXT);
        columnTypes.put("Total Price", DataType.NUMERIC);
        String columnDefinition = handler.buildColumnStatement(columnTypes);
        String sql = handler.buildCreateTableSql("sales_data", columnDefinition);

        assertThat(sql)
                .isEqualTo("CREATE TABLE sales_data("
                        + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                        + "order_no_0 TEXT," + System.lineSeparator()
                        + "total_price_0 NUMERIC(20,5))")
                .doesNotContain("," + System.lineSeparator() + ")");
    }
}

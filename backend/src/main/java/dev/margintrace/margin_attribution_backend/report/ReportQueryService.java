package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReportQueryService {
    private static final int MAX_ROWS = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern SAFE_CHARACTERS = Pattern.compile(
            "[A-Za-z0-9_\\s'(),.+\\-*/%<>=!]*");

    private static final Map<String, Set<String>> TABLE_COLUMNS = Map.of(
            "production_order", Set.of(
                    "id", "production_order_no", "date", "product_no", "product_num",
                    "product_department", "bom_no"),
            "inventory_usage", Set.of(
                    "id", "date", "movement_no", "product_no", "product_num",
                    "product_total_cost", "order_no", "material_no", "material_num",
                    "material_total_cost"),
            "bill_of_material", Set.of(
                    "id", "bom_no", "product_no", "material_no", "material_usage"),
            "sales_order", Set.of(
                    "id", "sale_order_no", "date", "movement_no", "product_no",
                    "product_num", "product_total_price"),
            "cost_details", Set.of(
                    "id", "sale_order_no", "date", "movement_no", "product_no",
                    "product_num", "total_cost"),
            "account_receivables", Set.of(
                    "id", "date", "account_receivable_no", "product_no", "product_num",
                    "product_total_price", "sale_order_no"),
            "purchases", Set.of(
                    "id", "purchase_order_no", "date", "product_no", "product_num",
                    "product_total_price"),
            "account_payables", Set.of(
                    "id", "date", "account_payable_no", "product_no", "product_num",
                    "product_total_price", "purchase_order_no")
    );

    private static final Set<String> SQL_WORDS = Set.of(
            "sum", "avg", "min", "max", "count", "coalesce", "nullif", "round", "abs",
            "distinct", "case", "when", "then", "else", "end", "and", "or", "not",
            "between", "in", "is", "null", "true", "false", "like", "ilike", "date");

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public QueryResult execute(ReportBlueprint blueprint) {
        if (blueprint == null) {
            throw new IllegalArgumentException("A generated Blueprint is required");
        }

        String table = normalizeTable(blueprint.sourceTable());
        String formula = validateFragment(blueprint.formula(), table, "formula");
        List<String> filters = blueprint.filterConditions() == null
                ? List.of()
                : blueprint.filterConditions().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> validateFragment(value, table, "filter"))
                        .toList();

        StringBuilder query = new StringBuilder("SELECT ")
                .append(formula)
                .append(" AS result FROM ")
                .append(table);
        if (!filters.isEmpty()) {
            query.append(" WHERE (").append(String.join(") AND (", filters)).append(")");
        }
        query.append(" LIMIT ").append(MAX_ROWS);

        try {
            List<Map<String, Object>> databaseRows = jdbcTemplate.query(
                    query.toString(),
                    statement -> {
                        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        statement.setMaxRows(MAX_ROWS);
                    },
                    new ColumnMapRowMapper());
            List<Map<String, Object>> rows = databaseRows.stream()
                    .map(row -> (Map<String, Object>) new LinkedHashMap<>(row))
                    .toList();
            List<String> columns = rows.isEmpty()
                    ? List.of("result")
                    : new ArrayList<>(rows.getFirst().keySet());
            return new QueryResult(query.toString(), columns, rows, rows.size());
        } catch (DataAccessException exception) {
            String detail = exception.getMostSpecificCause().getMessage();
            throw new IllegalArgumentException(
                    "The generated query could not be executed" +
                            (detail == null || detail.isBlank() ? "" : ": " + detail),
                    exception);
        }
    }

    private String normalizeTable(String sourceTable) {
        String table = sourceTable == null ? "" : sourceTable.trim().toLowerCase(Locale.ROOT);
        if (!TABLE_COLUMNS.containsKey(table)) {
            throw new IllegalArgumentException("The generated query uses an unknown source table");
        }
        return table;
    }

    private String validateFragment(String value, String table, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The generated query has an empty " + fieldName);
        }
        String fragment = value.trim();
        if (fragment.length() > 2_000 || !SAFE_CHARACTERS.matcher(fragment).matches()
                || fragment.contains(";") || fragment.contains("--") || fragment.contains("/*")) {
            throw new IllegalArgumentException("The generated query contains an unsafe " + fieldName);
        }

        String withoutStrings = STRING_LITERAL.matcher(fragment).replaceAll(" ");
        if (withoutStrings.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("The generated query contains an invalid string literal");
        }
        Matcher identifiers = IDENTIFIER.matcher(withoutStrings);
        Set<String> columns = TABLE_COLUMNS.get(table);
        while (identifiers.find()) {
            String identifier = identifiers.group().toLowerCase(Locale.ROOT);
            if (!columns.contains(identifier) && !SQL_WORDS.contains(identifier)) {
                throw new IllegalArgumentException(
                        "The generated query uses an unsupported field or function: " + identifier);
            }
        }
        return fragment;
    }

    public record QueryResult(
            String sql,
            List<String> columns,
            List<Map<String, Object>> rows,
            int rowCount) {
    }
}

package dev.margintrace.margin_attribution_backend.browse;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataBrowserControllerTests {
    private DataBrowserController browser;
    private MinioClient minio;

    @BeforeEach
    void setUp() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL", "sa", ""));
        jdbc.execute("CREATE SCHEMA raw");
        jdbc.execute("CREATE TABLE raw.example(id BIGINT PRIMARY KEY, amount NUMERIC(20,6), note VARCHAR)");
        jdbc.execute("INSERT INTO raw.example VALUES(9007199254740993, 12345678901234.123456, NULL), (2, 0.100000, 'second')");
        jdbc.execute("CREATE TABLE public.material_master(material_no VARCHAR PRIMARY KEY)");
        jdbc.execute("CREATE TABLE public.ai_provider_settings(secret VARCHAR)");
        minio = mock(MinioClient.class);
        browser = new DataBrowserController(jdbc, minio, "source-files");
    }

    @Test
    void listsOnlyIntendedTables() {
        assertEquals(List.of("example"), browser.tables("raw"));
        assertEquals(List.of("material_master"), browser.tables("warehouse"));
    }

    @Test
    void paginatesAndPreservesNumbersAndNulls() {
        var first = browser.rows("raw", "example", 0, 1);
        assertEquals(2L, first.get("total"));
        assertEquals(List.of(List.of("2", "0.100000", "second")), first.get("rows"));
        var second = browser.rows("raw", "example", 1, 1);
        var records = (List<?>) second.get("rows");
        assertEquals(java.util.Arrays.asList("9007199254740993", "12345678901234.123456", null), records.getFirst());
        assertEquals(List.of(), browser.rows("raw", "example", 2, 1).get("rows"));
    }

    @Test
    void rejectsUnknownLayersTablesAndInvalidPages() {
        assertThrows(ResponseStatusException.class, () -> browser.tables("private"));
        assertThrows(ResponseStatusException.class, () -> browser.rows("warehouse", "ai_provider_settings", 0, 50));
        assertThrows(ResponseStatusException.class, () -> browser.rows("raw", "example;DROP TABLE raw.example", 0, 50));
        assertThrows(ResponseStatusException.class, () -> browser.rows("raw", "example", -1, 50));
        assertThrows(ResponseStatusException.class, () -> browser.rows("raw", "example", 0, 201));
    }

    @Test
    void missingBucketIsEmptyAndStorageFailuresAreExplicit() throws Exception {
        assertEquals(List.of(), browser.files(0, 50).get("items"));
        when(minio.bucketExists(any())).thenThrow(new IllegalStateException("offline"));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> browser.files(0, 50)).getStatusCode().value());
    }
}

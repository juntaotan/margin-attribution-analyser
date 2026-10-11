package dev.margintrace.margin_attribution_backend.browse;

import io.minio.*;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;

/** Read-only browsing of imported raw tables, warehouse records and source objects. */
@RestController
@RequestMapping("/api/data-browser")
public class DataBrowserController {
    private static final Set<String> WAREHOUSE = Set.of("material_master", "inventory_usage", "bill_of_material", "sales_details", "purchases");
    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final String bucket;

    public DataBrowserController(JdbcTemplate jdbc, MinioClient minio,
                                 @Value("${datalake.raw-bucket}") String bucket) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.bucket = bucket;
    }

    /** Lists available import tables or the supported business tables without exposing settings tables. */
    @GetMapping("/tables")
    public List<String> tables(@RequestParam String layer) {
        String schema = schema(layer);
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_type = 'BASE TABLE' ORDER BY table_name", String.class, schema).stream()
                .filter(name -> layer.equals("raw") || WAREHOUSE.contains(name)).toList();
    }

    /** Returns a bounded, deterministic page with all values encoded as strings to preserve decimal precision. */
    @GetMapping("/rows")
    public Map<String, Object> rows(@RequestParam String layer, @RequestParam String table,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        validatePage(page, size);
        if (!tables(layer).contains(table)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not available");
        String schema = schema(layer);
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position", String.class, schema, table);
        String qualified = quote(schema) + "." + quote(table);
        String order = columns.stream().map(DataBrowserController::quote).collect(java.util.stream.Collectors.joining(","));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + qualified, Long.class);
        List<List<String>> records = jdbc.query("SELECT * FROM " + qualified + " ORDER BY " + order
                + " LIMIT ? OFFSET ?", (rs, index) -> {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= columns.size(); i++) values.add(rs.getString(i));
            return values;
        }, size, (long) page * size);
        return Map.of("columns", columns, "rows", records, "total", total, "page", page, "size", size);
    }

    /** Lists a bounded page of actual objects; a missing bucket is an empty data lake. */
    @GetMapping("/files")
    public Map<String, Object> files(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        validatePage(page, size);
        try {
            List<Map<String, Object>> files = new ArrayList<>();
            boolean more = false;
            if (minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                long index = 0;
                for (var result : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                    Item item = result.get();
                    if (index++ < (long) page * size) continue;
                    if (files.size() == size) { more = true; break; }
                    files.add(Map.of("key", item.objectName(), "size", Long.toString(item.size()),
                            "modified", item.lastModified().toString()));
                }
            }
            return Map.of("items", files, "hasMore", more, "bucket", bucket);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to list data lake files", exception);
        }
    }

    /** Streams an existing source object as an attachment without loading the whole file into memory. */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@RequestParam String key) {
        try {
            var metadata = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            String filename = key.substring(key.lastIndexOf('/') + 1);
            StreamingResponseBody body = output -> {
                try (var input = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                    input.transferTo(output);
                } catch (Exception exception) { throw new java.io.IOException("Unable to download source file", exception); }
            };
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(metadata.size())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(body);
        } catch (io.minio.errors.ErrorResponseException exception) {
            if (Set.of("NoSuchKey", "NoSuchBucket").contains(exception.errorResponse().code()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source file not found", exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to download source file", exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to download source file", exception);
        }
    }

    private static String schema(String layer) {
        return switch (layer) {
            case "raw" -> "raw";
            case "warehouse" -> "public";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown data layer");
        };
    }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size between 1 and 200");
    }
}

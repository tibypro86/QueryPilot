package com.querypilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class ApiController {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DATA_DIR = Paths.get("querypilot-data").toAbsolutePath();
    private static final Path ACTIVE_DB = DATA_DIR.resolve("active.sqlite");
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "openai/gpt-oss-120b";

    @Value("${GROQ_API_KEY:}")
    private String configuredGroqKey;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ApiController() {
        try {
            Files.createDirectories(DATA_DIR);
            if (!Files.exists(ACTIVE_DB) || Files.size(ACTIVE_DB) == 0) createDemoDatabase();
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize QueryPilot data directory", e);
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "QueryPilot API", "sourceName", sourceName());
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() {
        try (Connection c = connect()) {
            List<Map<String, Object>> tables = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    List<Map<String, Object>> columns = new ArrayList<>();
                    try (Statement st = c.createStatement();
                         ResultSet crs = st.executeQuery("PRAGMA table_info(" + quoteIdentifier(table) + ")")) {
                        while (crs.next()) {
                            Map<String, Object> col = new LinkedHashMap<>();
                            col.put("name", crs.getString("name"));
                            col.put("type", crs.getString("type"));
                            col.put("primaryKey", crs.getInt("pk") == 1);
                            columns.add(col);
                        }
                    }
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", table);
                    t.put("columns", columns);
                    tables.add(t);
                }
            }
            return Map.of("success", true, "sourceName", sourceName(), "tables", tables);
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read database schema: " + e.getMessage());
        }
    }

    @PostMapping(value = "/database/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadDatabase(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty file.");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds 25 MB.");
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("upload.csv");
        String ext = extension(original);
        if (!Set.of("csv", "json", "db", "sqlite", "sqlite3", "sql").contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supported formats: CSV, JSON, SQLite DB, and restricted SQL dumps.");
        }
        try {
            Path temp = Files.createTempFile(DATA_DIR, "incoming-", "." + ext);
            try {
                Files.copy(file.getInputStream(), temp, StandardCopyOption.REPLACE_EXISTING);
                switch (ext) {
                    case "csv" -> importCsv(temp, original);
                    case "json" -> importJson(temp, original);
                    case "db", "sqlite", "sqlite3" -> importSqlite(temp);
                    case "sql" -> importSqlDump(temp);
                    default -> throw new IllegalStateException("Unsupported format");
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sourceName", original,
                    "databaseName", original,
                    "message", "Uploaded " + original + " and prepared it for analysis."
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not import file: " + safeMessage(e));
        }
    }

    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyze(@RequestBody Map<String, Object> request,
                                       @RequestHeader(value = "X-Groq-API-Key", required = false) String headerKey) {
        String question = Objects.toString(request.get("question"), "").trim();
        if (question.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is required.");
        if (question.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is too long.");
        String conversationId = Objects.toString(request.getOrDefault("conversationId", UUID.randomUUID().toString()));
        long start = System.currentTimeMillis();

        try {
            String schemaText = schemaDescription();
            String key = headerKey != null && headerKey.startsWith("gsk_") ? headerKey.trim() : configuredGroqKey.trim();
            String sql;
            String answer;
            String title = "Query results";
            boolean aiUsed = false;

            if (!key.isBlank()) {
                AiPlan plan = askGroq(key, question, schemaText);
                sql = plan.sql;
                answer = plan.answer;
                aiUsed = true;
            } else {
                AiPlan plan = fallbackPlan(question, schemaText);
                sql = plan.sql;
                answer = plan.answer;
            }

            List<Map<String, Object>> rows = executeReadOnly(sql);
            if (answer == null || answer.isBlank() || answer.equalsIgnoreCase("Query results")) {
                answer = rows.isEmpty() ? "The query ran successfully but returned no rows." : "Query completed successfully and returned " + rows.size() + " row(s).";
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("title", title);
            result.put("answer", answer);
            result.put("sql", sql);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("evidence", "Executed read-only SQL against " + sourceName() + "; " + rows.size() + " row(s) returned.");
            result.put("source", sourceName());
            result.put("executionTime", (System.currentTimeMillis() - start) + " ms");
            result.put("conversationId", conversationId);
            result.put("needsClarification", false);
            result.put("aiUsed", aiUsed);
            if (!aiUsed) result.put("notice", "Groq key not configured. Used limited local query rules; add a valid Groq key for natural-language SQL generation.");
            return result;
        } catch (ResponseStatusException e) {
            System.err.println("[QueryPilot] Analyze request rejected: " + e.getReason());
            throw e;
        } catch (Exception e) {
            System.err.println("[QueryPilot] Analyze failed: " + e.getClass().getName() + ": " + safeMessage(e));
            e.printStackTrace(System.err);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Analysis failed: " + safeMessage(e), e);
        }
    }

    private AiPlan askGroq(String key, String question, String schema) throws Exception {
        String system = """
            You are QueryPilot, a careful SQLite data analyst. Only use tables and columns in the provided schema.
            Return ONLY a valid JSON object with exactly these keys: "sql" and "answer".
            "sql" must be one SQLite read-only SELECT or WITH query. Never return INSERT, UPDATE, DELETE, DROP, ALTER, PRAGMA, ATTACH, multiple statements, or comments.
            Use LIMIT 100 unless the user explicitly asks for a count/aggregate. Do not invent columns.
            "answer" should be a concise description of what the query will show, not a fabricated result.
            If the question cannot be answered from the schema, return SQL selecting a small sample from a real table and explain the limitation in answer.
            """;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", GROQ_MODEL);
        payload.put("temperature", 0.1);
        payload.put("max_tokens", 900);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", "DATABASE SCHEMA:\n" + schema + "\n\nQUESTION:\n" + question)
        ));
        HttpRequest req = HttpRequest.newBuilder(URI.create(GROQ_URL))
                .timeout(Duration.ofSeconds(35))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = JSON.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String msg = root.path("error").path("message").asText("Groq request failed (HTTP " + response.statusCode() + ")");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Groq API: " + msg);
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) throw new IllegalArgumentException("Groq returned an empty response.");
        content = content.trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        JsonNode parsed = JSON.readTree(content);
        String sql = parsed.path("sql").asText("").trim().replaceAll(";\\s*$", "");
        String answer = parsed.path("answer").asText("Query generated from your question.");
        validateReadOnlySql(sql);
        return new AiPlan(sql, answer);
    }

    private AiPlan fallbackPlan(String question, String schema) throws SQLException {
        List<String> tables = getTableNames();
        if (tables.isEmpty()) throw new IllegalStateException("No tables are available. Upload a CSV, JSON, or SQLite database first.");
        String q = question.toLowerCase(Locale.ROOT);
        String table = tables.get(0);
        String sql;
        String answer;
        if (q.matches(".*\\b(how many|count|number of|total records|total rows)\\b.*")) {
            sql = "SELECT COUNT(*) AS total_records FROM " + quoteIdentifier(table);
            answer = "Counts records in the " + table + " table. Add a Groq API key for more flexible questions.";
        } else if (q.contains("table") || q.contains("schema") || q.contains("columns")) {
            sql = "SELECT name AS table_name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 100";
            answer = "Lists the tables available in the uploaded database. Add a Groq API key for natural-language analysis.";
        } else {
            sql = "SELECT * FROM " + quoteIdentifier(table) + " LIMIT 25";
            answer = "Groq is not configured, so this is a sample of " + table + " rather than an AI-generated answer. Add your Groq API key to analyze the question.";
        }
        return new AiPlan(sql, answer);
    }

    private List<Map<String, Object>> executeReadOnly(String sql) throws SQLException {
        validateReadOnlySql(sql);
        String safeSql = sql;
        if (!Pattern.compile("\\blimit\\s+\\d+", Pattern.CASE_INSENSITIVE).matcher(sql).find()) safeSql += " LIMIT 200";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = connect()) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA query_only=ON");
                st.setQueryTimeout(15);
                try (ResultSet rs = st.executeQuery(safeSql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int count = Math.min(md.getColumnCount(), 50);
                    while (rs.next() && rows.size() < 200) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= count; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private void validateReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No SQL query was generated.");
        String trimmed = sql.trim();
        if (trimmed.contains(";") || trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a single read-only SQL query is allowed.");
        }
        if (!trimmed.matches("(?is)^(SELECT|WITH)\\b.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only read-only SELECT queries are allowed.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("insert ", "update ", "delete ", "drop ", "alter ", "attach ", "detach ", "pragma ", "create ", "replace ", "vacuum ")) {
            if (lower.contains(forbidden)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsafe SQL operation rejected.");
        }
    }

    private void importCsv(Path input, String original) throws Exception {
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get().parse(reader)) {
            List<String> headers = parser.getHeaderNames().stream().map(String::trim).toList();
            if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("CSV needs a header row with column names.");
            List<CSVRecord> records = parser.getRecords();
            String table = sanitizeIdentifier(removeExtension(original));
            Path fresh = newDatabasePath();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fresh)) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    String defs = headers.stream().map(h -> quoteIdentifier(sanitizeIdentifier(h)) + " TEXT").collect(Collectors.joining(", "));
                    st.execute("CREATE TABLE " + quoteIdentifier(table) + " (" + defs + ")");
                }
                String columns = headers.stream().map(h -> quoteIdentifier(sanitizeIdentifier(h))).collect(Collectors.joining(", "));
                String placeholders = String.join(",", Collections.nCopies(headers.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + quoteIdentifier(table) + " (" + columns + ") VALUES (" + placeholders + ")")) {
                    for (CSVRecord record : records) {
                        for (int i = 0; i < headers.size(); i++) ps.setString(i + 1, i < record.size() ? record.get(i) : null);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            }
            replaceActive(fresh);
        }
    }

    private void importJson(Path input, String original) throws Exception {
        JsonNode root = JSON.readTree(input.toFile());
        JsonNode array = root.isArray() ? root : root.has("data") && root.get("data").isArray() ? root.get("data") : null;
        if (array == null || array.isEmpty() || !array.get(0).isObject()) {
            throw new IllegalArgumentException("JSON must be a non-empty array of objects, or an object with a 'data' array.");
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        array.forEach(item -> item.fieldNames().forEachRemaining(k -> keys.add(sanitizeIdentifier(k))));
        if (keys.isEmpty()) throw new IllegalArgumentException("JSON objects need fields.");
        String table = sanitizeIdentifier(removeExtension(original));
        Path fresh = newDatabasePath();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fresh)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE " + quoteIdentifier(table) + " (" + keys.stream().map(k -> quoteIdentifier(k) + " TEXT").collect(Collectors.joining(", ")) + ")");
            }
            String cols = keys.stream().map(ApiController::quoteIdentifier).collect(Collectors.joining(", "));
            String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + quoteIdentifier(table) + " (" + cols + ") VALUES (" + placeholders + ")")) {
                for (JsonNode item : array) {
                    int i = 1;
                    for (String k : keys) {
                        JsonNode value = item.get(k);
                        if (value == null || value.isNull()) ps.setNull(i++, Types.VARCHAR);
                        else ps.setString(i++, value.isContainerNode() ? value.toString() : value.asText());
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
        replaceActive(fresh);
    }

    private void importSqlite(Path input) throws Exception {
        byte[] header = new byte[16];
        try (InputStream in = Files.newInputStream(input)) {
            if (in.read(header) != 16 || !new String(header, StandardCharsets.US_ASCII).equals("SQLite format 3\u0000")) {
                throw new IllegalArgumentException("This file is not a valid SQLite database.");
            }
        }
        // Validate it opens and has at least a readable schema before replacing active DB.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + input);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1")) {
            // Opening and reading sqlite_master validates the file.
        }
        Path fresh = newDatabasePath();
        Files.copy(input, fresh, StandardCopyOption.REPLACE_EXISTING);
        replaceActive(fresh);
    }

    private void importSqlDump(Path input) throws Exception {
        String dump = Files.readString(input, StandardCharsets.UTF_8);
        if (dump.length() > MAX_BYTES) throw new IllegalArgumentException("SQL dump is too large.");
        Path fresh = newDatabasePath();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fresh); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            for (String raw : dump.split(";")) {
                String statement = raw.trim();
                if (statement.isBlank()) continue;
                String normalized = statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (!(normalized.startsWith("create table ") || normalized.startsWith("create index ") ||
                      normalized.startsWith("create unique index ") || normalized.startsWith("insert into "))) {
                    throw new IllegalArgumentException("SQL imports only allow CREATE TABLE, CREATE INDEX, and INSERT INTO statements.");
                }
                if (normalized.contains("attach ") || normalized.contains("pragma ") || normalized.contains("drop ") ||
                    normalized.contains("delete ") || normalized.contains("update ") || normalized.contains("alter ")) {
                    throw new IllegalArgumentException("Unsafe statement in SQL dump.");
                }
                st.execute(statement);
            }
            c.commit();
        } catch (Exception e) {
            Files.deleteIfExists(fresh);
            throw e;
        }
        replaceActive(fresh);
    }

    private void createDemoDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ACTIVE_DB);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schools (school_id INTEGER PRIMARY KEY, school_name TEXT, district TEXT, school_type TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS enrolment (school_id INTEGER, academic_year INTEGER, student_count INTEGER, updated_at TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS districts (district_id INTEGER PRIMARY KEY, district_name TEXT, region TEXT)");
            st.execute("INSERT INTO schools(school_id,school_name,district,school_type) VALUES (1,'Government Senior Secondary School','North District','Government'),(2,'Central Public School','East District','Private'),(3,'Model Higher Secondary','West District','Government')");
            st.execute("INSERT INTO enrolment(school_id,academic_year,student_count,updated_at) VALUES (1,2022,1240,'2022-09-01'),(1,2023,1098,'2023-09-01'),(2,2022,980,'2022-09-01'),(2,2023,901,'2023-09-01'),(3,2022,870,'2022-09-01'),(3,2023,832,'2023-09-01')");
            st.execute("INSERT INTO districts(district_id,district_name,region) VALUES (1,'North District','North'),(2,'East District','East'),(3,'West District','West')");
        }
    }

    private String schemaDescription() throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet tables = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (tables.next()) {
                String table = tables.getString(1);
                out.append("TABLE ").append(table).append("(");
                List<String> cols = new ArrayList<>();
                try (Statement cs = c.createStatement();
                     ResultSet rs = cs.executeQuery("PRAGMA table_info(" + quoteIdentifier(table) + ")")) {
                    while (rs.next()) cols.add(rs.getString("name") + " " + rs.getString("type"));
                }
                out.append(String.join(", ", cols)).append(")\n");
            }
        }
        return out.toString();
    }

    private List<String> getTableNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + ACTIVE_DB);
    }

    private Path newDatabasePath() throws IOException {
        return Files.createTempFile(DATA_DIR, "newdb-", ".sqlite");
    }

    private void replaceActive(Path fresh) throws IOException {
        try {
            Files.move(fresh, ACTIVE_DB, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(fresh, ACTIVE_DB, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String sourceName() {
        try {
            return Files.exists(ACTIVE_DB) ? "Active SQLite database" : "Demo database";
        } catch (Exception e) { return "QueryPilot database"; }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String sanitizeIdentifier(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (clean.isBlank()) clean = "data";
        if (Character.isDigit(clean.charAt(0))) clean = "col_" + clean;
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private record AiPlan(String sql, String answer) {}
}

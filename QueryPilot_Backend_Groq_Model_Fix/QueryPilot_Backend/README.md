# QueryPilot Java Backend

Spring Boot backend for the uploaded `QueryPilot_HTML_Only(1).html` frontend.

## Requirements
- Java 17 or newer
- Maven 3.9+
- Internet access only if you want Groq-powered analysis (Maven downloads dependencies the first run)

## Start
Open a terminal in this folder and run:

```cmd
mvn spring-boot:run
```

Keep that terminal open. The API runs at `http://localhost:8080`.

Health check:
- `http://localhost:8080/api/health`

## Frontend API contract
- `GET /api/health`
- `GET /api/schema`
- `POST /api/database/upload` — multipart form field must be named `file`
- `POST /api/analyze` — JSON `{ "question": "How many records are there?", "conversationId": null }`
- Optional request header: `X-Groq-API-Key: gsk_...`

The analysis response includes `success`, `answer`, `title`, `sql`, `rows`, `rowCount`, `evidence`, `source`, `executionTime`, `conversationId`, and `aiUsed`.

## Groq key
The frontend's **Use key** button keeps the key in page memory and sends it to this backend in the `X-Groq-API-Key` request header. This project does not save that custom key to disk or log it. You can also configure a server-side key using an environment variable.

Windows CMD (only for this terminal session):
```cmd
set GROQ_API_KEY=gsk_your_real_key_here
mvn spring-boot:run
```

PowerShell:
```powershell
$env:GROQ_API_KEY="gsk_your_real_key_here"
mvn spring-boot:run
```

Never commit a real API key to source control or share screenshots that show it.

## Supported imports
- CSV: header row becomes column names, all columns imported as text.
- JSON: array of objects, or `{ "data": [...] }`.
- SQLite: `.db`, `.sqlite`, `.sqlite3`.
- SQL dump: restricted to `CREATE TABLE`, `CREATE INDEX`, and `INSERT INTO` statements. It is not a general-purpose SQL script runner.

The app creates a small sample school database on first startup so the UI can be tested immediately. Uploading a file replaces the active dataset. The active database is stored under `querypilot-data/active.sqlite`.

## Security notes
- Analysis only executes a single read-only `SELECT` or `WITH` query, with query-only SQLite mode enabled and result limits.
- This is a hackathon starter, not production security. Add authentication, per-user data isolation, stronger SQL parsing/validation, audit logging, and deployment secrets management before exposing it publicly.
- If port 8080 is busy, stop the old Java process or run with `mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"` and change `API_BASE` in the HTML accordingly.


Groq model configured in this backend: `openai/gpt-oss-120b`. To change it, edit `GROQ_MODEL` in `ApiController.java`.

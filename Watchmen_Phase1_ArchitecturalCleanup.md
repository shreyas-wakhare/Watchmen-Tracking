# Watchmen Tracker — Phase 1 Architectural Cleanup

**Date:** September 8, 2026  
**Repository:** `C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker`  
**Database:** PostgreSQL (`watchmen_tracker` on `localhost:5432`)  
**Status:** Completed & Verified  

---

## 1. Objective

During the initial Phase 1 health check, the core Alembic setup and PostgreSQL baseline passed successfully, but three non-blocking architectural warnings were documented:

1. **Application-level `create_all()` coexistence:** `Base.metadata.create_all(bind=engine)` remained in `backend/main.py`, meaning application startup still had the ability to emit DDL.
2. **Alembic coupling to `main.py`:** `alembic/env.py` imported `Base` directly from `backend/main.py`, requiring directory guards to avoid static file mount issues and needlessly executing FastAPI app initialization during migration checks.
3. **Implicit SQLite fallback:** `DATABASE_URL` defaulted to `sqlite:///./watchmen_test.db` if unset, creating a risk that FastAPI could silently fall back to local SQLite if environment variables were missing.

This cleanup task resolves all three warnings, decoupling Alembic, establishing Alembic as the sole schema authority, and hardening database configuration before Phase 2 Authentication.

---

## 2. Changes Made

### 1. Dedicated Database & Models Layer Extracted
Created a modular, dedicated database package under `backend/db/`:
- **[`backend/db/base.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/base.py)**:
  - Enforces `DATABASE_URL` presence: fails fast with an actionable `RuntimeError` if missing, empty, or configured for SQLite.
  - Instantiates SQLAlchemy `engine` with PostgreSQL pre-ping and statement timeouts.
  - Creates `SessionLocal = sessionmaker(bind=engine)` and `Base = declarative_base()`.
  - Provides the `get_db()` dependency generator.
- **[`backend/db/models.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/models.py)**:
  - Houses all 12 SQLAlchemy models semantically unchanged: `TelemetryDB`, `AlertDB`, `AnnouncementDB`, `TrialFailureDB`, `GeofenceDB`, `GeofenceEventDB`, `IncidentDB`, `CheckpointDB`, `CrashReportDB`, `BugReportDB`, `SecurityAlertDB`, and `AnnouncementReceiptDB`.
  - Houses the `utc_now()` timestamp helper used by model column defaults.
- **[`backend/db/__init__.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/__init__.py)**:
  - Cleanly exports `Base`, `engine`, `SessionLocal`, `get_db`, `DATABASE_URL`, and all 12 models.

### 2. Alembic Decoupled from `main.py`
Updated **[`alembic/env.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/alembic/env.py)**:
- Replaced `from main import Base` and working-directory switching (`os.chdir`) with direct imports from `backend.db`:
  ```python
  try:
      from backend.db import Base
  except ImportError:
      from db import Base

  target_metadata = Base.metadata
  ```
- Running `alembic check`, `alembic current`, or `alembic heads` now loads only the SQLAlchemy metadata without importing `backend/main.py`, without mounting static files, and without initializing FastAPI or Zeroconf.

### 3. Application-level `create_all()` Removed
Updated **[`backend/main.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/main.py)**:
- Removed lines 61–83 (inline `Base`, `DATABASE_URL`, `create_engine`, `SessionLocal`, `get_db`).
- Imported `Base`, `engine`, `SessionLocal`, `get_db`, and all 12 models from `backend.db`.
- Moved Pydantic request schemas `OfflineTelemetryPoint` and `AnnouncementAck` into the `# -------------------- PYDANTIC SCHEMAS --------------------` section.
- **Completely removed `Base.metadata.create_all(bind=engine)`**.
- Application startup now exclusively relies on existing PostgreSQL tables managed by Alembic.

### 4. SQLite Fallback Removed & Fail-Fast Enforced
Updated **[`backend/db/base.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/base.py)**:
- If `DATABASE_URL` is missing or empty, startup raises `RuntimeError: DATABASE_URL environment variable is missing or empty...`.
- If `DATABASE_URL` contains `sqlite`, startup raises `RuntimeError: DATABASE_URL points to SQLite... SQLite fallback is strictly prohibited.`.

---

## 3. Database Safety Verification

- ✅ **PostgreSQL database was NOT dropped or recreated.**
- ✅ **Existing tables were NOT dropped, altered, or recreated.**
- ✅ **Table rows were NOT deleted or modified.**
- ✅ **No SQLite runtime data was migrated.**
- ✅ **`0001_baseline_existing_schema.py` was NOT modified.**
- ✅ **No new Alembic migrations were generated** (pure code-level refactor; zero schema drift).
- ✅ **No destructive SQL was executed.**

---

## 4. New Database Architecture

### Directory Structure
```text
WatchmenTracker/
├── alembic.ini                              [Root runner config]
├── alembic/
│   ├── env.py                              [Imports Base from backend.db]
│   ├── script.py.mako
│   └── versions/
│       └── 0001_baseline_existing_schema.py [Baseline revision]
└── backend/
    ├── alembic.ini                         [Backend directory runner config]
    ├── main.py                             [FastAPI application, routes & WebSocket]
    ├── requirements.txt                    [Contains alembic>=1.14.0]
    └── db/                                 [DEDICATED DATABASE PACKAGE]
        ├── __init__.py                     [Exports Base, engine, models]
        ├── base.py                         [Engine, SessionLocal, get_db, fail-fast validation]
        └── models.py                       [All 12 SQLAlchemy models & utc_now]
```

### Dependency Flow
```text
                 alembic/env.py
                       │
                       ▼
               backend/db/base.py  ◄──  backend/db/models.py
                       ▲
                       │
               backend/main.py
          (FastAPI routes & WebSocket)
```

Alembic and FastAPI both depend downward on `backend.db`. Neither depends on the other.

---

## 5. Verification Results

### A. Alembic Decoupling
Executed in isolated Python process to trace imports:
```powershell
$env:DATABASE_URL="postgresql+psycopg2://postgres:***@localhost:5432/watchmen_tracker"
python -c "import sys; from alembic.config import Config; from alembic import command; cfg = Config('alembic.ini'); command.check(cfg); print('IS_MAIN_IMPORTED:', 'main' in sys.modules or 'backend.main' in sys.modules)"
```
**Output:**
```text
No new upgrade operations detected.
IS_MAIN_IMPORTED: False
```
`backend/main.py` is **not** imported during Alembic operations.

### B. Alembic Commands
- `alembic current` $\rightarrow$ `0001_baseline_existing_schema (head)`
- `alembic heads` $\rightarrow$ `0001_baseline_existing_schema (head)`
- `alembic check` $\rightarrow$ `No new upgrade operations detected.` (Exit Code 0)

Commands were verified working from both the workspace root and the `backend/` directory.

### C. PostgreSQL Database State
Queried via `psycopg2` directly against `watchmen_tracker`:
- **Total Public Tables:** 13
- **Application Tables (12):** `alerts`, `announcement_receipts`, `announcements`, `bug_reports`, `checkpoints`, `crash_reports`, `geofence_events`, `geofences`, `incidents`, `security_alerts`, `telemetry`, `trial_failures`.
- **Migration Tracking Table (1):** `alembic_version` with value `['0001_baseline_existing_schema']`.
- **Row Counts:** All 12 tables contain exactly 0 rows.

### D. Fail-Fast Configuration Testing
1. **Missing `DATABASE_URL`:**
   ```text
   RuntimeError: DATABASE_URL environment variable is missing or empty. Watchmen Tracker requires an explicit PostgreSQL connection string...
   ```
2. **SQLite `DATABASE_URL`:**
   ```text
   RuntimeError: DATABASE_URL points to SQLite (sqlite:///./watchmen_test.db). Watchmen Tracker operates exclusively on PostgreSQL in this environment. SQLite fallback is strictly prohibited.
   ```

### E. Application Startup & Endpoints
Launched Uvicorn with `DATABASE_URL` set to PostgreSQL:
```text
INFO:     Started server process [24112]
INFO:     Waiting for application startup.
2026-09-08 13:00:48,763 [INFO] 📡 mDNS zeroconf service registered: WatchmenTrackerBackend on 192.168.1.53:8000
INFO:     Application startup complete.
INFO:     Uvicorn running on http://127.0.0.1:8000 (Press CTRL+C to quit)
```

Live HTTP verification:
- `GET /` $\rightarrow$ **200 OK**
- `GET /health` $\rightarrow$ **200 OK** (`{"status":"healthy","telemetry_count":0,"alerts_count":0,"security_alerts_count":0,"active_connections":0}`)
- `GET /data` $\rightarrow$ **200 OK**
- `GET /static/dashboard.js` $\rightarrow$ **200 OK**
- `GET /devices` $\rightarrow$ **404 Not Found** (preserved legacy route parity)

---

## 6. SQLite Hardening

A search for `sqlite:///` across the repository shows:
- `backend/test_discovery.py:9` (`os.environ.setdefault("DATABASE_URL", "sqlite:///./watchmen_test.db")`): **Test-only**. Used exclusively for running discovery unit tests in isolation without requiring a live PostgreSQL instance.
- `backend/db/base.py`: Explicitly rejects SQLite with `RuntimeError`.
- `alembic/env.py`: Explicitly rejects SQLite with `RuntimeError`.
- `watchmen_test.db`: **Historical/Local artifact**. Inactive and untouched.

**Classification:** Active application runtime has zero SQLite fallback.

---

## 7. Git Diff Review

### Files Modified / Created in this Cleanup Task
| File | Status | Rationale |
|---|:---:|---|
| [`backend/db/base.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/base.py) | **NEW** | Engine, SessionLocal, Base, fail-fast `DATABASE_URL` validation. |
| [`backend/db/models.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/models.py) | **NEW** | All 12 SQLAlchemy models extracted with identical columns, types, indexes, and defaults. |
| [`backend/db/__init__.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/__init__.py) | **NEW** | Package exports for models and database utilities. |
| [`alembic/env.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/alembic/env.py) | **MODIFY** | Direct import from `backend.db`; decoupled from `main.py`; removed `os.chdir`. |
| [`backend/main.py`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/main.py) | **MODIFY** | Replaced inline models/setup with `backend.db` imports; removed `create_all()`. |

### Unrelated Files NOT Modified
- Android client (`app/*`): Untouched.
- Frontend / Dashboard (`backend/static/*`): Untouched.
- API endpoints / Business logic in `main.py`: Untouched.
- Baseline migration (`alembic/versions/0001_baseline_existing_schema.py`): Untouched.
- PostgreSQL database schema: Untouched.

---

## 8. Remaining Technical Debt

1. **Relative Static Directory Mount in `main.py:53`:** `main.py` uses `StaticFiles(directory="static")`. When launching Uvicorn, CWD must be `backend/`. This is outside the DB scope and does not affect Alembic now that Alembic is decoupled.
2. **Duplicate Routes in `main.py`:** Pre-existing shadowed routes (`POST /incident` and `GET /incidents` defined twice in `main.py`) remain from prior project history and are queued for endpoint cleanup.

---

## 9. Final Status

### **PASS**

All three architectural warnings from Phase 1 have been completely resolved:
- `create_all()` is removed from application startup.
- Alembic is decoupled from `main.py` via `backend/db/`.
- Implicit SQLite fallback is removed; fail-fast PostgreSQL enforcement is active.

### **READY FOR PHASE 2 AUTHENTICATION: YES**

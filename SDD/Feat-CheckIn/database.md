# Watchmen Tracker — Check-In / Check-Out
# Database Design Specification

---

## 1. Purpose

This document provides the definitive database architecture specification for introducing **Check-In / Check-Out**, **Scheduled Shift Management**, and **Attendance Lifecycle Tracking** into the Watchmen Tracker system.

The primary objective is to establish a high-integrity, normalized relational database foundation in PostgreSQL that:
1. Incorporates the **5 Locked Business Rules** agreed upon before implementation.
2. Distinguishes between **Expected Shift Schedules** and **Actual Attendance Records**.
3. Preserves historical audit integrity via **schedule snapshotting** (schedule modifications never retroactively alter past attendance records).
4. Binds attendance records directly to authenticated user accounts (`users.id`) established during Phase 2A authentication.
5. Provides deterministic, derived state calculation (`NOT_STARTED`, `CLOCKED_IN`, `CLOCKED_OUT`) without redundant, mutable status flags.
6. Enforces **server-authoritative timestamps** with UTC storage and deterministic site-timezone conversion (`Asia/Dubai`).
7. Guarantees **idempotent attendance transactions** against network drops and duplicate client retries.
8. Enforces database-level guarantees against duplicate active shifts and multiple shifts per day via PostgreSQL constraints.
9. Outlines the prospective Alembic migration footprint (`0003_add_attendance_tables`) without modifying existing historical migrations (`0001_baseline_existing_schema` and `0002_add_authentication_tables`).

---

## 🔒 Locked Business Rules (Source of Truth)

Before finalizing this database specification, the following five core business decisions were locked:

```text
===================================================================================
                             LOCKED BUSINESS RULES
===================================================================================

[RULE 1] SCHEDULE HISTORY — SIMPLE CURRENT SCHEDULE
         - A watchman has exactly ONE active recurring schedule at any given time.
         - Updating a schedule deactivates/replaces the current active schedule.
         - No complex schedule versioning/effective-date tables at this stage.
         - Historical attendance remains 100% accurate because attendance records
           snapshot the scheduled start/end times at the moment of duty.

[RULE 2] MULTIPLE SHIFTS — ONE CONTINUOUS SHIFT PER WORKING DAY
         - A watchman has exactly ONE continuous duty shift per working day.
         - Clock In = duty starts; Clock Out = duty ends.
         - Short breaks (lunch, tea, rest) do NOT create separate attendance sessions.
         - Split shifts and multiple scheduled sessions per day are NOT supported.

[RULE 3] NETWORK FAILURE — SERVER IS THE AUTHORITY + IDEMPOTENT RETRIES
         - Clock In/Out are strictly server-authoritative. Server generates timestamps.
         - The mobile phone's local clock is NEVER the official attendance time.
         - Offline actions are NOT officially completed until confirmed by the backend.
         - Lost-response network retries are handled via client-generated request UUIDs
           ensuring idempotent replay without duplicate attendance records.

[RULE 4] FORGOTTEN CLOCK OUT — NEVER INVENT ATTENDANCE
         - The system NEVER fabricates or auto-guesses a clock-out timestamp.
         - If a watchman forgets to clock out, clock_out_at remains NULL indefinitely.
         - An unclosed shift remains OPEN.
         - A watchman CANNOT clock in for a new shift while an old shift remains open.
         - Open sessions must be resolved via future authorized supervisor workflows.

[RULE 5] TIMEZONE — SITE TIME IS THE SOURCE OF TRUTH
         - Attendance operates strictly according to the SITE TIMEZONE (e.g. Asia/Dubai).
         - The mobile phone's local timezone is completely ignored.
         - Scheduled shift times represent local wall-clock time (e.g. 07:00 -> 19:00).
         - Actual clock times are stored as UTC TIMESTAMPTZ and converted to site time.
         - work_date represents the LOCAL DATE ON WHICH THE DUTY SHIFT STARTED
           (ensuring overnight shifts belong to the shift start calendar day).
===================================================================================
```

---

## 2. Existing System Analysis

### 2.1 Current Database Architecture
The active Watchmen Tracker backend uses **PostgreSQL** managed through **SQLAlchemy ORM** and **Alembic** migrations. As verified in `backend/config.py`, the system explicitly mandates PostgreSQL (`DATABASE_URL` verification strictly prohibits SQLite fallback). Connection management in `backend/db/base.py` enforces connection pooling (`pool_pre_ping=True`) and a 60-second statement timeout (`-c statement_timeout=60000`).

Database migrations are tracked in `alembic/versions/`:
- `0001_baseline_existing_schema.py`: Baseline migration establishing the core operations tables.
- `0002_add_authentication_tables.py` (Revision `d2c79d4e86ed`): Migration establishing user authentication and refresh token tables.

All existing timestamps are declared as `DateTime(timezone=True)` with server-side default generator `utc_now()` returning `datetime.now(timezone.utc)`. Local display conversions are handled via `to_uae()` in `backend/main.py` using `timezone(timedelta(hours=4))`.

### 2.2 Relevant Existing Tables

| Existing Table | Primary Key | Key Columns | Relationships / FKs | Indexes | Current Role | Suitability for Check-In Feature |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `users` | `id` (Integer) | `email`, `password_hash`, `full_name`, `is_active`, `created_at`, `updated_at`, `last_login_at` | Referenced by `refresh_tokens.user_id` (1:N) | `ix_users_id`, `ix_users_email` (unique) | Identity & authentication credentials. | **Authoritative identity.** Every attendance record must link to `users.id`. Should NOT be burdened with mutable daily attendance state. |
| `refresh_tokens` | `id` (Integer) | `user_id`, `token_hash`, `expires_at`, `revoked_at`, `created_at` | `FK -> users.id` (CASCADE) | `ix_refresh_tokens_id`, `ix_refresh_tokens_user_id`, `token_hash`, `expires_at` | JWT session persistence. | Relevant for verifying authenticated API access; untouched by attendance schema. |
| `telemetry` | `id` (Integer) | `device_id`, `device_name`, `project_number`, `latitude`, `longitude`, `speed`, `steps`, `battery`, `tracking_state`, `timestamp` | None (standalone time-series) | `device_id`, `timestamp`, `tracking_state`, `project_number` | High-frequency sensor & GPS log (every 3–10s). | **Unsuitable.** Time-series telemetry cannot store discrete daily attendance session records. |
| `alerts` | `id` (Integer) | `device_id`, `alert_type`, `violation_type`, `latitude`, `longitude`, `battery`, `timestamp`, `resolved`, `liveness_verified` | None | `alert_type`, `device_id`, `timestamp` | Security violations, panic alarms, spoofing detections, facial liveness verifications. | **Unsuitable.** Attendance is normal operational workflow, not a security breach or violation. Combining them pollutes security KPI metrics. |
| `security_alerts` | `id` (Integer) | `device_id`, `alert_type`, `details`, `timestamp` | None | `alert_type`, `device_id`, `timestamp` | Hardware/OS tamper events (root, hook, debug). | **Unsuitable.** Strictly for device integrity alerts. |
| `incidents` | `id` (Integer) | `device_id`, `incident_type`, `description`, `photo_path`, `timestamp`, `resolved` | None | `incident_type`, `device_id`, `timestamp` | On-site manual incident reports by guards. | **Unsuitable.** Incidents represent external security events, not worker attendance. |
| `checkpoints` | `id` (Integer) | `device_id`, `checkpoint_id`, `checkpoint_name`, `latitude`, `longitude`, `timestamp` | None | `checkpoint_id`, `device_id`, `timestamp` | Physical patrol checkpoint scans. | **Unsuitable.** Checkpoints record patrol tour waypoints, not shift start/end boundaries. |
| `geofences` | `id` (Integer) | `name`, `type`, `latitude`, `longitude`, `radius`, `coordinates`, `enabled` | Referenced by `geofence_events.geofence_id` | `id`, `name` | Perimeter definitions for site boundaries. | Reusable in future for geofence-validated clock-in; untouched for basic check-in. |
| `geofence_events`| `id` (Integer) | `device_id`, `geofence_id`, `event_type`, `latitude`, `longitude`, `timestamp` | `FK -> geofences.id` | `device_id`, `event_type`, `timestamp` | Entry/exit transition log. | Untouched. |

### 2.3 Current User Identity
User authentication is managed through the `users` table created in Phase 2A:
- Fields: `id` (Integer PK), `email` (String, unique), `password_hash` (Argon2), `full_name` (String), `is_active` (Boolean).
- In the Android application:
  - `AuthApiClient.kt` logs in the user via `POST /auth/login`.
  - `AuthManager.kt` securely caches the user session in `watchmen_auth_prefs`: `user_id`, `user_email`, `user_full_name`, `access_token`, `refresh_token`.
  - `MainActivity.kt` enforces login at startup (redirects to `LoginActivity` if `!AuthManager.isLoggedIn()`).
  - The JWT access token generated by `backend/auth/security.py` embeds `{"sub": str(user.id), "type": "access"}`.
- **Architectural Conclusion**: The watchman identity is canonical and already resolved: it is `users.id`. There is zero requirement for a separate `watchmen` or `employees` table.

### 2.4 Current Device Identity
In the current Android implementation:
- `SetupActivity.kt` collects `device_name`, `supervisor_phone`, `project_number`, and persists them in SharedPreferences (`watchmen_prefs`).
- `TrackingService.kt` generates a composite `device_id` string:
  ```kotlin
  val newId = "${projectNumber}_${deviceName}_${androidId.take(8)}"
  ```
- This `device_id` is an unstructured client-side string without a backing relational `devices` table in PostgreSQL.
- **Architectural Conclusion**: Attendance records must primarily link to `users.id` (relational FK). Storing `device_id` as an auxiliary, indexed `VARCHAR` column on the attendance record is valuable for hardware auditing, cross-referencing `telemetry.device_id`, and multi-device fraud detection, but it must not be treated as a foreign key.

### 2.5 Current Event/Alert Architecture
The system currently processes real-time events via:
1. Ingestion REST endpoints (`POST /telemetry`, `POST /alert`, `POST /incident`).
2. Persistence into respective PostgreSQL tables (`telemetry`, `alerts`, `incidents`).
3. Immediate fan-out to active dashboard clients via an in-memory WebSocket manager (`await manager.broadcast({...})`).
4. Dashboard polling/refreshing via REST queries (`GET /data`, `GET /alerts?hours=24`, `GET /incidents`).

Currently, the dashboard has no dedicated concept of **Guard Attendance**, **Shift Roster**, or **Working Duty Durations**.

---

## 3. Feature Requirements

### 3.1 Scheduled Shift
- **Expected Schedule Definition**: During the initial Setup flow (or supervisor roster assignment), the watchman defines expected daily duty timing:
  - Example: `07:00 AM` (Shift Start) to `07:00 PM` (Shift End).
- **Domain Role**: The scheduled shift represents the **EXPECTED** duty baseline. It is immutable for that day once the shift begins, enabling variance analysis (early arrival, late arrival, overtime, early departure).
- **Locked Rule Alignment**: Exactly one active recurring schedule exists per user. Historical attendance preserves its own schedule snapshot.

### 3.2 Clock In
- **Action**: Guard reaches site and taps **CLOCK IN** (e.g., at `06:54 AM`).
- **Data Capture**:
  - Exact server-side timestamp (`clock_in_at`).
  - Snapshot of scheduled start and end times (`scheduled_start_time`, `scheduled_end_time`, `timezone`).
  - Guard user reference (`user_id`).
  - Client request identifier (`clock_in_request_id`) for idempotency.
  - Client device reference (`clock_in_device_id`).
  - Optional capture of clock-in GPS coordinates (`clock_in_lat`, `clock_in_lon`).
- **Immediate State**: Transitions watchman state from `NOT_STARTED` to `CLOCKED_IN`.

### 3.3 Active Shift
- **Running Counter**: The mobile UI displays an active working duration timer:
  $$\text{Working Duration} = \text{Current Time} - \text{Actual Clock-In Time}$$
- **Recovery Requirement**: If the Android app is killed, restarted, or device reboots, the running duration must be completely recoverable by querying the active attendance session from the backend. The client must never rely on local ephemeral timers.

### 3.4 Clock Out
- **Action**: Guard finishes duty and taps **CLOCK OUT** (e.g., at `07:02 PM`).
- **Data Capture**:
  - Exact server-side timestamp (`clock_out_at`).
  - Client request identifier (`clock_out_request_id`) for idempotency.
  - Clock-out device reference and optional GPS coordinates.
  - Final worked duration in minutes:
    $$\text{Worked Minutes} = \frac{\text{clock\_out\_at} - \text{clock\_in\_at}}{60}$$
    (Example: 06:54 AM to 07:02 PM = 12 hours 8 minutes = 728 minutes).
- **Final State**: Transitions watchman state from `CLOCKED_IN` to `CLOCKED_OUT`.

### 3.5 Dashboard Events
- The web dashboard requires both real-time alerts and aggregated shift visibility:
  - 🟢 **Clock-In Event**: Michael clocked in at 06:54 AM (Expected: 07:00 AM — Early by 6m).
  - 🔵 **Clock-Out Event**: Michael clocked out at 07:02 PM (Worked: 12h 08m — Overtime: 2m).
  - **Live Duty Roster**: Table/card showing guards currently on duty, hours elapsed, and scheduled end time.

### 3.6 Attendance History
- The database must retain an append-only historical log of all daily shifts across weeks, months, and years for payroll, attendance reports, and SLA compliance auditing.

---

## 4. Database Design Decision

### 4.1 Tables Reused
- **`users`**: Reused without structural changes as the foreign key target for attendance records (`attendance_records.user_id -> users.id`). User authentication, identity, and active status are already cleanly encapsulated here.

### 4.2 Tables Modified
- **None**: Modifying existing tables is unnecessary and architecturally undesirable.
  - *Why not add shift columns to `users`?* Adding `shift_start`, `shift_end`, or active attendance IDs to `users` violates Single Responsibility Principle (SRP). The `users` table handles authentication credentials. Furthermore, storing daily shift state on `users` destroys attendance history.

### 4.3 New Tables

We introduce two clean, normalized tables:

1. **`user_shift_schedules`**:
   - **Purpose**: Stores the watchman's configured recurring shift schedule (expected duty hours).
   - **Enforcement**: Constrained by a partial unique index (`uq_user_active_schedule`) to guarantee **at most one active schedule per user** (Locked Decision #1).
   - **Rationale**: Decouples operational scheduling from authentication credentials. Updating a schedule deactivates the previous schedule and inserts/activates the new one without breaking historical attendance.

2. **`attendance_records`**:
   - **Purpose**: Authoritative daily ledger of actual attendance sessions (clock-in, clock-out, GPS, device, duration, schedule snapshot, and idempotency request IDs).
   - **Enforcement**:
     - `uq_attendance_user_work_date`: Constrains `(user_id, work_date)` to ensure **one continuous duty shift per working day** (Locked Decision #2).
     - `uq_user_single_active_shift`: Partial unique index (`WHERE clock_out_at IS NULL`) ensuring **at most one open shift across any date** (Locked Decision #4).
     - `uq_attendance_clock_in_request_id`: Partial unique index ensuring **idempotent clock-in retries** (Locked Decision #3).
   - **Rationale**: Provides historical auditability. Each record snapshots the scheduled shift baseline at the moment of duty, ensuring that future schedule changes do not invalidate past attendance audits.

### 4.4 Tables Explicitly NOT Modified

| Table Name | Reason for Leaving Untouched |
| :--- | :--- |
| `users` | Preserves Phase 2A authentication architecture and clean separation of concerns. |
| `refresh_tokens` | Handles JWT session lifecycle only. |
| `telemetry` | High-frequency time-series table. Storing discrete daily attendance states here would cause severe query degradation and schema pollution. |
| `alerts` | Dedicated to security breaches, panics, and anti-spoofing violations. Attendance is standard operations. |
| `security_alerts`| Dedicated to hardware and OS tampering events. |
| `incidents` | Dedicated to field incident reporting. |
| `checkpoints` | Dedicated to physical NFC/QR patrol waypoints. |
| `geofences` & `geofence_events` | Geofence definitions and transition logs remain independent. |
| `announcements` & receipts | Audio broadcast messaging system remains independent. |
| `bug_reports`, `crash_reports`, `trial_failures` | Diagnostic and error tables remain independent. |

---

## 5. Proposed Schema

### 5.1 Table: `user_shift_schedules`

Stores the expected / scheduled duty shift parameters for each watchman. Enforces **Locked Decision #1** (One active recurring schedule at a time).

| Column | Type | Nullable | Default | Constraints / Indexes | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `False` | Autoincrement | `PRIMARY KEY` | Surrogate primary key. |
| `user_id` | `INTEGER` | `False` | — | `FOREIGN KEY (users.id) ON DELETE CASCADE`, `INDEX` | Owning watchman account. |
| `shift_name` | `VARCHAR(50)` | `False` | `'Default Shift'` | — | Descriptive label (e.g., "Day Shift", "Night Shift"). |
| `start_time` | `TIME WITHOUT TIME ZONE` | `False` | — | — | Expected shift start time (e.g., `07:00:00`). Local wall-clock time. |
| `end_time` | `TIME WITHOUT TIME ZONE` | `False` | — | — | Expected shift end time (e.g., `19:00:00`). Local wall-clock time. |
| `timezone` | `VARCHAR(50)` | `False` | `'Asia/Dubai'` | — | IANA timezone name governing wall-clock times. |
| `days_of_week` | `VARCHAR(30)` | `False` | `'1,2,3,4,5,6,7'` | — | Comma-separated ISO day of week (1=Mon, 7=Sun) schedule applies to. |
| `is_active` | `BOOLEAN` | `False` | `True` | `INDEX` | Flag indicating if schedule is currently in effect. |
| `created_at` | `TIMESTAMP WITH TIME ZONE`| `False` | `utc_now` | — | Creation audit timestamp. |
| `updated_at` | `TIMESTAMP WITH TIME ZONE`| `False` | `utc_now` | — | Last update audit timestamp. |

#### Constraints and Indexes for `user_shift_schedules`:
- **PK**: `PRIMARY KEY (id)`
- **FK**: `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- **Index**: `ix_user_shift_schedules_user_id` on `(user_id)`
- **Partial Unique Index (LOCKED RULE #1)**: Guarantees that a user has **at most one active schedule** at any time:
  ```sql
  CREATE UNIQUE INDEX uq_user_active_schedule ON user_shift_schedules (user_id) WHERE is_active = TRUE;
  ```

---

### 5.2 Table: `attendance_records`

The authoritative daily attendance session record. Enforces **Locked Decisions #2, #3, #4, and #5**.

| Column | Type | Nullable | Default | Constraints / Indexes | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `False` | Autoincrement | `PRIMARY KEY` | Surrogate primary key. |
| `user_id` | `INTEGER` | `False` | — | `FOREIGN KEY (users.id) ON DELETE RESTRICT`, `INDEX` | Watchman who clocked in. RESTRICT prevents deleting user if attendance exists. |
| `work_date` | `DATE` | `False` | — | `UNIQUE (user_id, work_date)` | Local date on which shift started in site timezone (Locked Decision #5). |
| `scheduled_start_time` | `TIME WITHOUT TIME ZONE` | `False` | — | — | Snapshot of expected start time for this shift session (Locked Decision #1). |
| `scheduled_end_time` | `TIME WITHOUT TIME ZONE` | `False` | — | — | Snapshot of expected end time for this shift session (Locked Decision #1). |
| `timezone` | `VARCHAR(50)` | `False` | `'Asia/Dubai'` | — | Site timezone snapshot for the scheduled times and work date. |
| `clock_in_at` | `TIMESTAMP WITH TIME ZONE`| `False` | — | `INDEX` | Server-generated UTC timestamp of clock-in (Locked Decision #3). |
| `clock_out_at` | `TIMESTAMP WITH TIME ZONE`| `True` | `NULL` | `INDEX` | Server-generated UTC timestamp of clock-out (`NULL` while shift is active, never fabricated). |
| `clock_in_request_id` | `VARCHAR(64)` | `True` | `NULL` | `UNIQUE WHERE NOT NULL` | Client UUID for idempotent Clock In retries (Locked Decision #3). |
| `clock_out_request_id` | `VARCHAR(64)` | `True` | `NULL` | `UNIQUE WHERE NOT NULL` | Client UUID for idempotent Clock Out retries (Locked Decision #3). |
| `clock_in_device_id` | `VARCHAR(100)` | `True` | `NULL` | `INDEX` | Device identifier from which clock-in was transmitted. |
| `clock_out_device_id` | `VARCHAR(100)` | `True` | `NULL` | — | Device identifier from which clock-out was transmitted. |
| `clock_in_lat` | `DOUBLE PRECISION` | `True` | `NULL` | — | GPS latitude at clock-in. |
| `clock_in_lon` | `DOUBLE PRECISION` | `True` | `NULL` | — | GPS longitude at clock-in. |
| `clock_out_lat` | `DOUBLE PRECISION` | `True` | `NULL` | — | GPS latitude at clock-out. |
| `clock_out_lon` | `DOUBLE PRECISION` | `True` | `NULL` | — | GPS longitude at clock-out. |
| `total_worked_minutes` | `INTEGER` | `True` | `NULL` | — | Stored total duration in minutes upon clock-out for fast aggregations. |
| `notes` | `TEXT` | `True` | `NULL` | — | Optional supervisor or guard notes. |
| `created_at` | `TIMESTAMP WITH TIME ZONE`| `False` | `utc_now` | — | Record creation audit timestamp. |
| `updated_at` | `TIMESTAMP WITH TIME ZONE`| `False` | `utc_now` | — | Record update audit timestamp. |

#### Constraints and Indexes for `attendance_records`:
- **PK**: `PRIMARY KEY (id)`
- **FK**: `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT`
- **Check Constraint**: Clock-out cannot be earlier than clock-in:
  ```sql
  CONSTRAINT ck_attendance_clock_out_after_in CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
  ```
- **Check Constraint**: Worked minutes must be non-negative:
  ```sql
  CONSTRAINT ck_attendance_worked_minutes_positive CHECK (total_worked_minutes IS NULL OR total_worked_minutes >= 0)
  ```
- **Unique Constraint (LOCKED RULE #2 — One Continuous Shift Per Day)**:
  Guarantees that a user can have **at most one attendance record per work_date**:
  ```sql
  CONSTRAINT uq_attendance_user_work_date UNIQUE (user_id, work_date)
  ```
- **Partial Unique Index (LOCKED RULE #4 — Single Active Shift / No Forgotten Overlaps)**:
  Guarantees that a user can have **at most one open shift across any date**:
  ```sql
  CREATE UNIQUE INDEX uq_user_single_active_shift ON attendance_records (user_id) WHERE clock_out_at IS NULL;
  ```
  *(Prevents clocking in today if yesterday's shift was never clocked out, and prevents concurrent double-clock-in races).*
- **Partial Unique Index (LOCKED RULE #3 — Idempotent Clock In Retry)**:
  ```sql
  CREATE UNIQUE INDEX uq_attendance_clock_in_request_id ON attendance_records (clock_in_request_id) WHERE clock_in_request_id IS NOT NULL;
  ```
- **Partial Unique Index (LOCKED RULE #3 — Idempotent Clock Out Retry)**:
  ```sql
  CREATE UNIQUE INDEX uq_attendance_clock_out_request_id ON attendance_records (clock_out_request_id) WHERE clock_out_request_id IS NOT NULL;
  ```
- **Dashboard Feed Index**: Fast retrieval of recent active and completed shifts:
  ```sql
  CREATE INDEX ix_attendance_recent_activity ON attendance_records (work_date DESC, clock_in_at DESC);
  ```
- **Device Audit Index**: Cross-table queries against telemetry:
  ```sql
  CREATE INDEX ix_attendance_records_clock_in_device_id ON attendance_records (clock_in_device_id);
  ```

---

## 6. Relationships

### 6.1 Entity-Relationship Diagram

```text
===================================================================================
                       WATCHMEN TRACKER RELATIONAL SCHEMA
===================================================================================

       +-------------------------------------------------------+
       |                        users                          |
       +-------------------------------------------------------+
       | PK  id             : INTEGER                          |
       |     email          : VARCHAR (unique)                 |
       |     password_hash  : VARCHAR                          |
       |     full_name      : VARCHAR                          |
       |     is_active      : BOOLEAN                          |
       |     created_at     : TIMESTAMPTZ                      |
       |     updated_at     : TIMESTAMPTZ                      |
       |     last_login_at  : TIMESTAMPTZ                      |
       +-------------------------------------------------------+
             │                                   │
             │ 1:N (CASCADE)                     │ 1:N (RESTRICT)
             ▼                                   ▼
+-----------------------------+     +-----------------------------------------------+
|    user_shift_schedules     |     |              attendance_records               |
+-----------------------------+     +-----------------------------------------------+
| PK  id          : INTEGER   |     | PK  id                    : INTEGER           |
| FK  user_id     : INTEGER   |     | FK  user_id               : INTEGER           |
|     shift_name  : VARCHAR   |     |     work_date             : DATE (unique/usr) |
|     start_time  : TIME      |     |     scheduled_start_time  : TIME (snapshot)   |
|     end_time    : TIME      |     |     scheduled_end_time    : TIME (snapshot)   |
|     timezone    : VARCHAR   |     |     timezone              : VARCHAR (snapshot)|
|     days_of_week: VARCHAR   |     |     clock_in_at           : TIMESTAMPTZ (UTC) |
|     is_active   : BOOLEAN   |     |     clock_out_at          : TIMESTAMPTZ (UTC) |
|     created_at  : TIMESTAMPTZ|    |     clock_in_request_id   : VARCHAR (idempot) |
|     updated_at  : TIMESTAMPTZ|    |     clock_out_request_id  : VARCHAR (idempot) |
+-----------------------------+     |     clock_in_device_id    : VARCHAR (audit)   |
 (uq_user_active_schedule:          |     clock_out_device_id   : VARCHAR (audit)   |
  WHERE is_active=TRUE)             |     clock_in_lat/lon      : DOUBLE PRECISION  |
                                    |     clock_out_lat/lon     : DOUBLE PRECISION  |
                                    |     total_worked_minutes  : INTEGER           |
                                    |     created_at            : TIMESTAMPTZ       |
                                    |     updated_at            : TIMESTAMPTZ       |
                                    +-----------------------------------------------+
                                     (uq_user_single_active_shift:
                                      WHERE clock_out_at IS NULL)
                                                          │
                                                          │ (Auditing reference by string,
                                                          │  no FK constraint)
                                                          ▼
                                    +-----------------------------------------------+
                                    |                   telemetry                   |
                                    |   (device_id matches clock_in_device_id)      |
                                    +-----------------------------------------------+
```

### 6.2 Structural Notes
- **User Deletion Rules**:
  - `user_shift_schedules`: Cascade delete (`ON DELETE CASCADE`) if a user is deleted.
  - `attendance_records`: Protected by `ON DELETE RESTRICT`. A user with attendance history cannot be deleted. Deactivation is handled via `users.is_active = FALSE`.
- **Decoupling from Device Identity**:
  - `clock_in_device_id` and `clock_out_device_id` are stored as loose string attributes. They match the client's current hardware identifier for auditing, without foreign keys to non-existent hardware tables.

---

## 7. Time and Timezone Strategy

Conforms strictly to **Locked Decision #5**:

### 7.1 Server Timestamps vs Client Timestamps
- **Rule**: All timestamps representing reality (`clock_in_at`, `clock_out_at`, `created_at`, `updated_at`) **MUST be generated server-side** using `datetime.now(timezone.utc)` (`TIMESTAMPTZ`).
- **Client Security**: Mobile phone clocks are easily drifted, manipulated, or incorrect. The client request triggers the action; the server sets the official timestamp.

### 7.2 Scheduled Shift Wall-Clock Representation
- A shift schedule such as `07:00 AM — 07:00 PM` represents a **local recurring wall-clock interval**, not a fixed UTC instant.
- Scheduled times are stored as `TIME WITHOUT TIME ZONE` (e.g., `07:00:00` and `19:00:00`) alongside an explicit IANA `timezone` (e.g., `'Asia/Dubai'`).
- This completely isolates schedule definitions from daylight saving changes and client timezone settings.

### 7.3 Work Date Identity (`work_date`)
- `work_date` represents the **LOCAL DATE ON WHICH THE DUTY SHIFT STARTED** in the site timezone.
- **Normal Daytime Shift Example**:
  - Scheduled: `07:00` to `19:00`
  - Actual Clock In: `2026-09-10 07:03 AM` in `Asia/Dubai` (UTC: `2026-09-10 03:03:00Z`).
  - `work_date`: `2026-09-10`.
- **Overnight Shift Example**:
  - Scheduled: `19:00` to `07:00`
  - Actual Clock In: `2026-09-10 19:03` in `Asia/Dubai` (UTC: `2026-09-10 15:03:00Z`).
  - Actual Clock Out: `2026-09-11 07:02` in `Asia/Dubai` (UTC: `2026-09-11 03:02:00Z`).
  - `work_date`: `2026-09-10` (the date duty started).
  - The shift belongs to September 10, even though clock-out occurred on September 11.

### 7.4 Consistency with Existing Backend Architecture
- In `backend/main.py`, the existing convention uses `UTC` for storage and `to_uae()` (`UTC+4`) for display.
- Outgoing API responses will serialize:
  - `clock_in_at`: ISO 8601 UTC string (`2026-09-10T03:03:00Z`).
  - `clock_in_local`: Formatted local string (`07:03:00` / `2026-09-10T07:03:00+04:00`).

---

## 8. Attendance State Model

### 8.1 Derived States vs Stored States
We explicitly **reject** adding an enum/varchar `status` column to `attendance_records`. 

A stored status column creates dual-source-of-truth hazards. Instead, the attendance state is **100% deterministically derived** from the presence of timestamps:

```text
                  +-----------------------------------+
                  |        No active record           |
                  |     for today's work_date         |
                  +-----------------------------------+
                                    │
                                    ▼
                          State: NOT_STARTED
                                    │
                         Guard presses CLOCK IN
                                    │
                                    ▼
                  +-----------------------------------+
                  |       clock_in_at IS NOT NULL     |
                  |       clock_out_at IS NULL        |
                  +-----------------------------------+
                                    │
                                    ▼
                          State: CLOCKED_IN
                                    │
                         Guard presses CLOCK OUT
                                    │
                                    ▼
                  +-----------------------------------+
                  |       clock_in_at IS NOT NULL     |
                  |       clock_out_at IS NOT NULL    |
                  +-----------------------------------+
                                    │
                                    ▼
                          State: CLOCKED_OUT
```

### 8.2 Computed Pydantic / API Output Schema
At the API layer, the model surfaces the derived state dynamically:
```python
@property
def status(self) -> str:
    if self.clock_out_at is not None:
        return "CLOCKED_OUT"
    if self.clock_in_at is not None:
        return "CLOCKED_IN"
    return "NOT_STARTED"
```

---

## 9. Data Lifecycle

```text
[Watchman First-Time Setup]
         │
         ▼
1. Supervisor or Watchman saves expected schedule in SetupActivity.
   - Deactivates any existing active schedule for user (`is_active = FALSE`).
   - Inserts row into `user_shift_schedules`:
     (user_id=1, start_time='07:00', end_time='19:00', timezone='Asia/Dubai', is_active=TRUE)
         │
         ▼
[Daily Working Routine]
         │
         ▼
2. App opens: Queries `GET /attendance/today`.
   - Backend checks:
     a) Is there an unclosed shift from any past day? (`WHERE clock_out_at IS NULL`)
        If YES -> Return `state: "PENDING_RESOLUTION"`, alert guard that previous shift is unclosed.
     b) Does a record exist for today's work_date?
        If NO -> Return `state: "NOT_STARTED"` with scheduled timing.
        If YES and clock_out_at IS NULL -> Return `state: "CLOCKED_IN"` with running duration.
        If YES and clock_out_at IS NOT NULL -> Return `state: "CLOCKED_OUT"` (day complete).
         │
         ▼
3. Watchman presses CLOCK IN at 06:54 AM:
   - Client generates UUID: `request_id = "c7a8b9d0-1234-5678-..."`
   - Client sends: `POST /attendance/clock-in` with `request_id`.
   - Backend checks `clock_in_request_id = request_id`:
     If already processed -> Returns existing record (Idempotent replay).
   - Backend checks `WHERE user_id = :uid AND clock_out_at IS NULL`:
     If open shift exists -> Rejects with HTTP 409 ("Unclosed previous shift exists").
   - Backend checks `WHERE user_id = :uid AND work_date = :today`:
     If already clocked in today -> Rejects with HTTP 409 ("Shift already completed today").
   - Backend queries active schedule from `user_shift_schedules`.
   - Backend creates `attendance_records` row:
     - `user_id`: 1
     - `work_date`: '2026-09-10'
     - `scheduled_start_time`: '07:00:00' (snapshot)
     - `scheduled_end_time`: '19:00:00' (snapshot)
     - `timezone`: 'Asia/Dubai' (snapshot)
     - `clock_in_at`: 2026-09-10 02:54:00+00 (06:54 AM UAE)
     - `clock_out_at`: NULL
     - `clock_in_request_id`: request_id
   - Backend broadcasts WebSocket event: `{"event": "WATCHMAN_CLOCKED_IN", ...}`
         │
         ▼
4. During Shift (Active State):
   - UI updates button to [ CLOCK OUT ].
   - UI starts running timer: `now() - clock_in_at`.
   - If phone restarts or app crashes, app calls `GET /attendance/today` and resumes timer immediately.
         │
         ▼
5. Watchman presses CLOCK OUT at 07:02 PM:
   - Client generates UUID: `request_id = "d8b9c0e1-4321-8765-..."`
   - Client sends: `POST /attendance/clock-out` with `request_id`.
   - Backend checks `clock_out_request_id = request_id`:
     If already processed -> Returns completed record (Idempotent replay).
   - Backend locates open record (`WHERE user_id = :uid AND clock_out_at IS NULL`).
   - Backend updates row:
     - `clock_out_at`: 2026-09-10 15:02:00+00 (07:02 PM UAE)
     - `clock_out_request_id`: request_id
     - `total_worked_minutes`: 728
     - `updated_at`: utc_now()
   - Backend broadcasts WebSocket event: `{"event": "WATCHMAN_CLOCKED_OUT", ...}`
         │
         ▼
6. Shift Completed:
   - Historical record remains permanently indexed in `attendance_records`.
```

---

## 10. Edge Cases (Aligned with Locked Rules)

### 10.1 Multiple Shifts in a Single Day (Locked Decision #2)
- **Rule**: Exactly one continuous duty shift per working day.
- **Database Enforcement**: `CONSTRAINT uq_attendance_user_work_date UNIQUE (user_id, work_date)`.
- **Behavior**: If a guard attempts to clock in a second time on the same `work_date`, the database immediately rejects the transaction with a unique constraint violation. Split shifts are strictly prohibited.

### 10.2 Overnight Shifts (Locked Decision #5)
- **Scenario**: Shift begins at 19:00 on Sept 10 and ends at 07:00 on Sept 11.
- **Database Handling**:
  - `work_date` is stamped at clock-in as `2026-09-10` (the date duty started in site timezone).
  - `clock_in_at` = `2026-09-10 15:00:00+00` (UTC).
  - `clock_out_at` = `2026-09-11 03:00:00+00` (UTC).
  - Both UTC timestamps preserve chronological sequence; `clock_out_at >= clock_in_at` evaluates to `TRUE`.
  - Next day's shift (starting 19:00 on Sept 11) has `work_date = 2026-09-11`, cleanly satisfying `UNIQUE (user_id, work_date)`.

### 10.3 Forgotten Clock Out (Locked Decision #4)
- **Rule**: **The system must NEVER invent an actual Clock Out timestamp.**
- **Database Handling**:
  - `clock_out_at` remains `NULL` indefinitely.
  - No automated timer, midnight job, or next app launch will auto-close or guess a clock-out time.
  - The unclosed session remains OPEN in the database.
  - **Block on Next Shift**: The partial unique index `uq_user_single_active_shift` (`WHERE clock_out_at IS NULL`) physically blocks the user from clocking into any new shift while an existing session is open.
  - **Resolution**: The guard or an authorized supervisor must resolve the open shift before a new shift can begin (via future supervisor workflow).

### 10.4 Network Failure at Clock In/Out (Locked Decision #3)
- **Rule**: Server is the authority. Clock In/Out is NOT officially completed without backend confirmation.
- **Behavior**: If the mobile phone loses connection, the action fails locally and prompts the user to reconnect and retry. The phone's local time is NEVER stored as the official attendance time.

### 10.5 Lost-Response Retry & Idempotency (Locked Decision #3)
- **Scenario**:
  1. Phone sends `POST /attendance/clock-in` with client UUID `request_id = "abc-123"`.
  2. Backend successfully inserts the row with `clock_in_request_id = "abc-123"`.
  3. Network drops before the HTTP 200 response reaches the phone.
  4. Phone retries `POST /attendance/clock-in` with the same `request_id = "abc-123"`.
- **Database Handling**:
  - The backend performs: `SELECT * FROM attendance_records WHERE clock_in_request_id = 'abc-123'`.
  - Record is found! Backend returns the existing attendance record with HTTP 200.
  - `uq_attendance_clock_in_request_id` prevents duplicate insertion.
  - Exactly one attendance record exists with the authoritative server timestamp.

### 10.6 Cross-Device Switching
- **Scenario**: Guard clocks in on Phone A, phone battery dies, guard logs into Phone B to clock out.
- **Database Handling**:
  - Shift is linked to `users.id`, not the hardware.
  - Phone B retrieves the active shift and clocks out successfully.
  - Record logs `clock_in_device_id = 'DEVICE_A'` and `clock_out_device_id = 'DEVICE_B'`, preserving an audit trail of the hardware switch.

---

## 11. Security and Data Integrity

1. **Authenticated User Context (`user_id`)**:
   - The user ID is **never accepted from the client request body**.
   - It is strictly extracted from the validated JWT token claims (`sub`) in the `Authorization: Bearer <token>` header.
2. **Server-Generated Timestamps**:
   - Client-provided datetimes are ignored. The database receives `utc_now()`.
3. **Historical Schedule Immutability**:
   - `scheduled_start_time`, `scheduled_end_time`, and `timezone` are snapshotted on `attendance_records` at clock-in. Future schedule updates in `user_shift_schedules` cannot alter past compliance benchmarks.
4. **Relational Deletion Protection**:
   - Foreign key constraint `ON DELETE RESTRICT` on `attendance_records.user_id` prevents accidental deletion of user accounts that possess historical attendance records.

---

## 12. Indexing Strategy

| Index Name | Table | Columns | Type | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `pk_user_shift_schedules` | `user_shift_schedules` | `id` | B-tree | Primary Key lookup. |
| `ix_user_shift_schedules_user_id` | `user_shift_schedules` | `user_id` | B-tree | Fast schedule retrieval during clock-in. |
| `uq_user_active_schedule` | `user_shift_schedules` | `user_id` WHERE `is_active = TRUE` | B-tree (Partial Unique) | **Locked Rule #1**: Enforces at most one active schedule per user. |
| `pk_attendance_records` | `attendance_records` | `id` | B-tree | Primary Key lookup. |
| `uq_attendance_user_work_date` | `attendance_records` | `user_id`, `work_date` | B-tree (Unique) | **Locked Rule #2**: Enforces one continuous shift per working day. |
| `uq_user_single_active_shift` | `attendance_records` | `user_id` WHERE `clock_out_at IS NULL`| B-tree (Partial Unique) | **Locked Rule #4**: Prevents opening new shift if old shift is unclosed. |
| `uq_attendance_clock_in_request_id` | `attendance_records` | `clock_in_request_id` WHERE `clock_in_request_id IS NOT NULL` | B-tree (Partial Unique) | **Locked Rule #3**: Guarantees idempotent Clock In retries. |
| `uq_attendance_clock_out_request_id` | `attendance_records` | `clock_out_request_id` WHERE `clock_out_request_id IS NOT NULL` | B-tree (Partial Unique) | **Locked Rule #3**: Guarantees idempotent Clock Out retries. |
| `ix_attendance_recent_activity`| `attendance_records` | `work_date DESC`, `clock_in_at DESC` | B-tree | Dashboard live activity feed and reports. |
| `ix_attendance_records_clock_in_device_id` | `attendance_records` | `clock_in_device_id` | B-tree | Cross-table auditing with telemetry device streams. |

---

## 13. Dashboard Query Requirements

The web dashboard (`dashboard.html` / `dashboard.js`) requires data for:

1. **Live On-Duty Roster** (Guards currently clocked in):
   ```sql
   SELECT a.id, u.full_name, a.scheduled_start_time, a.scheduled_end_time, 
          a.clock_in_at, a.clock_in_device_id
   FROM attendance_records a
   JOIN users u ON a.user_id = u.id
   WHERE a.clock_out_at IS NULL
   ORDER BY a.clock_in_at DESC;
   ```

2. **Today's Attendance Summary & Compliance**:
   ```sql
   SELECT a.id, u.full_name, a.work_date, a.scheduled_start_time, a.scheduled_end_time,
          a.clock_in_at, a.clock_out_at, a.total_worked_minutes
   FROM attendance_records a
   JOIN users u ON a.user_id = u.id
   WHERE a.work_date = CURRENT_DATE
   ORDER BY a.clock_in_at DESC;
   ```

3. **Real-Time WebSocket Feed Payloads**:
   ```json
   {
     "type": "ATTENDANCE_EVENT",
     "event": "CLOCK_IN",
     "user_id": 1,
     "user_name": "Michael",
     "time_local": "06:54:00",
     "scheduled_start": "07:00:00",
     "status": "EARLY_ARRIVAL"
   }
   ```

---

## 14. Example Data

### 14.1 Row in `users` (Existing)
```text
id            : 1
email         : michael.guard@watchmen.ae
password_hash : $argon2id$v=19$m=65536,t=3,p=4$...
full_name     : Michael
is_active     : TRUE
created_at    : 2026-09-08 10:00:00+00
updated_at    : 2026-09-08 10:00:00+00
last_login_at : 2026-09-10 02:30:00+00
```

### 14.2 Row in `user_shift_schedules` (New)
```text
id            : 101
user_id       : 1
shift_name    : Site A Day Shift
start_time    : 07:00:00
end_time      : 19:00:00
timezone      : Asia/Dubai
days_of_week  : 1,2,3,4,5,6,7
is_active     : TRUE
created_at    : 2026-09-08 10:05:00+00
updated_at    : 2026-09-08 10:05:00+00
```

### 14.3 Row in `attendance_records` (Completed Shift Example)
```text
id                   : 5001
user_id              : 1
work_date            : 2026-09-10
scheduled_start_time : 07:00:00
scheduled_end_time   : 19:00:00
timezone             : Asia/Dubai
clock_in_at          : 2026-09-10 02:54:00+00  (06:54 AM UAE)
clock_out_at         : 2026-09-10 15:02:00+00  (07:02 PM UAE)
clock_in_request_id  : c7a8b9d0-1234-5678-9abc-def012345678
clock_out_request_id : d8b9c0e1-4321-8765-fedc-ba9876543210
clock_in_device_id   : SiteA_GuardPhone_a1b2c3d4
clock_out_device_id  : SiteA_GuardPhone_a1b2c3d4
clock_in_lat         : 25.1972
clock_in_lon         : 55.2744
clock_out_lat        : 25.1975
clock_out_lon        : 55.2741
total_worked_minutes : 728                      (12 hours 8 minutes)
notes                : NULL
created_at           : 2026-09-10 02:54:00+00
updated_at           : 2026-09-10 15:02:00+00
```

---

## 15. Alembic Migration Impact

### 15.1 Revision Metadata
- **New Revision ID**: `0003_add_attendance_tables`
- **Down Revision**: `d2c79d4e86ed` (from `0002_add_authentication_tables.py`)
- **Historical Immutability**: Neither `0001_baseline_existing_schema.py` nor `0002_add_authentication_tables.py` will be modified.

### 15.2 Migration Operations (`upgrade()`)
1. Create table `user_shift_schedules` with foreign key referencing `users.id` (`ondelete="CASCADE"`).
2. Create index `ix_user_shift_schedules_user_id` on `user_shift_schedules(user_id)`.
3. Create partial unique index `uq_user_active_schedule` on `user_shift_schedules(user_id) WHERE is_active = TRUE`.
4. Create table `attendance_records` with foreign key referencing `users.id` (`ondelete="RESTRICT"`).
5. Add check constraints:
   - `ck_attendance_clock_out_after_in`
   - `ck_attendance_worked_minutes_positive`
6. Add unique constraint `uq_attendance_user_work_date` on `(user_id, work_date)`.
7. Create partial unique index `uq_user_single_active_shift` on `attendance_records(user_id) WHERE clock_out_at IS NULL`.
8. Create partial unique index `uq_attendance_clock_in_request_id` on `attendance_records(clock_in_request_id) WHERE clock_in_request_id IS NOT NULL`.
9. Create partial unique index `uq_attendance_clock_out_request_id` on `attendance_records(clock_out_request_id) WHERE clock_out_request_id IS NOT NULL`.
10. Create query indexes:
    - `ix_attendance_recent_activity` on `(work_date DESC, clock_in_at DESC)`
    - `ix_attendance_records_clock_in_device_id` on `(clock_in_device_id)`

---

## 16. Rollback Strategy

The rollback plan for `downgrade()` reverses the operations in exact reverse dependency order:
```python
def downgrade() -> None:
    # 1. Drop attendance_records indexes and table
    op.drop_index('ix_attendance_records_clock_in_device_id', table_name='attendance_records')
    op.drop_index('ix_attendance_recent_activity', table_name='attendance_records')
    op.drop_index('uq_attendance_clock_out_request_id', table_name='attendance_records')
    op.drop_index('uq_attendance_clock_in_request_id', table_name='attendance_records')
    op.drop_index('uq_user_single_active_shift', table_name='attendance_records')
    op.drop_constraint('uq_attendance_user_work_date', 'attendance_records', type_='unique')
    op.drop_table('attendance_records')

    # 2. Drop user_shift_schedules indexes and table
    op.drop_index('uq_user_active_schedule', table_name='user_shift_schedules')
    op.drop_index('ix_user_shift_schedules_user_id', table_name='user_shift_schedules')
    op.drop_table('user_shift_schedules')
```
- **Zero Risk to Existing Data**: Existing tables (`users`, `telemetry`, `alerts`, etc.) are completely unmodified. Rolling back leaves all existing data intact.

---

## 17. Alternatives Considered

### Alternative 1: Full Schedule Versioning / Effective Date Tables
- **Description**: Tables like `shift_schedule_versions` with `effective_from` and `effective_until`.
- **Why Rejected**: Over-engineered for current business requirements (Locked Decision #1). One active recurring schedule + snapshotting on `attendance_records` achieves 100% audit integrity with much simpler code and queries.

### Alternative 2: Allowing Multiple Open Shifts or Split Shifts
- **Description**: Allowing guards to clock in and out multiple times a day for lunch or multiple duty sessions.
- **Why Rejected**: Explicitly rejected by Locked Decision #2. Guards work one continuous shift. Temporary breaks do not end attendance.

### Alternative 3: Auto-Closing Forgotten Shifts via Background Jobs
- **Description**: Automatically setting `clock_out_at = 19:00` or `midnight` if guard forgets to clock out.
- **Why Rejected**: Explicitly prohibited by Locked Decision #4. Fabricating attendance times corrupts payroll and legal compliance. The record must remain open until formally resolved.

---

## 18. Final Recommendation

The recommended architecture strictly enforces the 5 Locked Business Rules:
1. **Zero Modifications** to existing tables (`users`, `telemetry`, `alerts`).
2. **Two New Tables**:
   - `user_shift_schedules`: 1 active recurring schedule per user (`uq_user_active_schedule`).
   - `attendance_records`: 1 continuous duty session per day (`uq_attendance_user_work_date`), referencing `users.id` with `ON DELETE RESTRICT`.
3. **Concurrency & Forgotten Shift Protection**:
   - `uq_user_single_active_shift` (`WHERE clock_out_at IS NULL`) guarantees no duplicate active shifts and blocks new shifts while an old shift is unclosed.
4. **Idempotency Protection**:
   - Client-generated `clock_in_request_id` (`uq_attendance_clock_in_request_id`) and `clock_out_request_id` (`uq_attendance_clock_out_request_id`) partial unique indexes guarantee that lost-response network retries for both Clock In and Clock Out can never create duplicate attendance records or state corruption.
5. **Timezone & Historical Integrity**:
   - Server-authoritative UTC storage + site timezone conversion (`Asia/Dubai`), with `work_date` anchored to duty start day.

---

## 19. Open Questions & Future Enhancements

The following items are recognized as **future enhancements** outside the immediate scope of this database migration:
1. **Supervisor Attendance Correction Subsystem**:
   - When a watchman forgets to clock out and a session remains open, how will a supervisor formally approve a corrected clock-out timestamp? *(Future feature: will introduce a supervisor override audit table or approval workflow).*
2. **Geofence Clock-In Radius Enforcement**:
   - Should clock-in be rejected if the guard's GPS is outside a designated site geofence (`geofences.id`), or permitted with an "off-site" audit flag? *(Supported at schema level via `clock_in_lat` / `clock_in_lon`, to be configured in backend policy).*

# Watchmen Tracker — Phase 2A Basic Authentication DB Design

## 1. Objective

Phase 2A establishes the minimal, high-integrity database foundation required for basic user **Signup** and **Login** in Watchmen Tracker. The primary objective is to define the SQLAlchemy models, constraints, and relationships for user identities and session refresh tokens in memory within Python, while leaving the live PostgreSQL database and Alembic baseline strictly untouched.

---

## 2. Scope

Phase 2A is intentionally simple and tightly scoped:
- **Models Introduced**: Only `User` (`users`) and `RefreshToken` (`refresh_tokens`) SQLAlchemy models were created.
- **Strict Exclusions**: This phase does **NOT** introduce:
  - ❌ Organizations, `organization_id`, or multi-tenancy
  - ❌ Roles, permissions, RBAC, or `user_roles`
  - ❌ OAuth, social login, or SSO
  - ❌ MFA / TOTP tables
  - ❌ Password reset or email verification tables
  - ❌ Audit logs, login history, or device fingerprinting tables
  - ❌ API keys or bearer token generation logic
  - ❌ Signup or Login API endpoints (`/auth/signup`, `/auth/login`)
  - ❌ Password hashing functions or token signing utilities

---

## 3. Authentication Architecture

```text
                    AUTHENTICATION MODEL

                         User (users)
                          │
                          │ 1:N (cascade="all, delete-orphan")
                          ▼
                   RefreshToken (refresh_tokens)
```

### Future Workflow Support
1. **Signup**: Creates a new row in `users` with `email`, `password_hash`, and `full_name`.
2. **Login**: Validates credentials against `password_hash`, updates `last_login_at`, and issues short-lived JWT access tokens alongside a persisted `RefreshToken` row.
3. **Access Token Refresh**: Authenticates incoming refresh tokens against `refresh_tokens.token_hash` where `expires_at > NOW()` and `revoked_at IS NULL`.
4. **Logout / Token Revocation**: Sets `revoked_at = NOW()` on the matching `refresh_tokens` record without deleting historical session metadata.

---

## 4. `users` Schema

Table Name: `users`

| Column | Type | Nullable | Default | Constraints / Indexes | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `Integer` | `False` | Primary Key (Autoincrement) | `primary_key=True`, `index=True` | Unique surrogate identifier for the user account. |
| `email` | `String` | `False` | — | `unique=True`, `index=True`, `NOT NULL` | Account login identity. Indexed for fast lookup and constrained unique to prevent duplicate accounts. |
| `password_hash` | `String` | `False` | — | `NOT NULL` | Secure bcrypt/Argon2 password hash. Plaintext passwords are never stored. |
| `full_name` | `String` | `False` | — | `NOT NULL` | User's display / full name for UI components. |
| `is_active` | `Boolean` | `False` | `True` | `NOT NULL`, Default: `True` | Account status flag allowing accounts to be deactivated without deletion. |
| `created_at` | `DateTime(timezone=True)` | `False` | `utc_now` | `NOT NULL`, Default: UTC timestamp | Timestamp indicating when the account was registered. |
| `updated_at` | `DateTime(timezone=True)` | `False` | `utc_now` | `NOT NULL`, OnUpdate: `utc_now` | Timestamp indicating when the account record was last modified. |
| `last_login_at` | `DateTime(timezone=True)` | `True` | `None` | Nullable | Timestamp of the user's most recent successful login (`NULL` until first login). |

---

## 5. `refresh_tokens` Schema

Table Name: `refresh_tokens`

| Column | Type | Nullable | Default | Constraints / Indexes | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `Integer` | `False` | Primary Key (Autoincrement) | `primary_key=True`, `index=True` | Unique primary key for the refresh token session record. |
| `user_id` | `Integer` | `False` | — | `ForeignKey("users.id", ondelete="CASCADE")`, `index=True` | Foreign key referencing the owning user. Cascades deletion when a user is deleted. |
| `token_hash` | `String` | `False` | — | `index=True`, `NOT NULL` | SHA256 hash of the refresh token. Raw refresh tokens are never persisted. |
| `expires_at` | `DateTime(timezone=True)` | `False` | — | `index=True`, `NOT NULL` | Expiration timestamp for the refresh token. Indexed for efficient expired token cleanup queries. |
| `revoked_at` | `DateTime(timezone=True)` | `True` | `None` | Nullable | Timestamp when token was explicitly revoked/logged out (`NULL` if token is active). |
| `created_at` | `DateTime(timezone=True)` | `False` | `utc_now` | `NOT NULL`, Default: UTC timestamp | Timestamp when the refresh token session was initiated. |

---

## 6. Relationships

The models use existing SQLAlchemy `Base` and `relationship` declarations:

```python
# User model side (1:N)
refresh_tokens = relationship("RefreshToken", back_populates="user", cascade="all, delete-orphan")

# RefreshToken model side (N:1)
user = relationship("User", back_populates="refresh_tokens")
```

- **`User.refresh_tokens`**: Accesses all session tokens associated with a user account. Deleting a `User` cascades deletion to all dependent `RefreshToken` rows (`cascade="all, delete-orphan"`).
- **`RefreshToken.user`**: Accesses the parent `User` instance for an active token.

---

## 7. Security Design

1. **Password Hash Storage**: The user table explicitly defines `password_hash` (`NOT NULL`). No plaintext `password` column exists.
2. **Token Hash Storage**: Refresh tokens are stored strictly as `token_hash` (`NOT NULL`). No raw `refresh_token` column exists.
3. **Session Revocation**: The nullable `revoked_at` column enables soft token revocation on logout while preserving audit integrity.
4. **Duplicate Prevention**: `users.email` is declared with `unique=True` and `index=True`, guaranteeing uniqueness at both model metadata and database layer.
5. **No Secret Hardcoding**: Environment variable `DATABASE_URL` is required for connection, and no JWT secrets, API keys, or credentials are hardcoded anywhere in the codebase.

---

## 8. Database Safety

The Phase 2A database safety rules were strictly enforced:
- **Live PostgreSQL Database (`watchmen_tracker`)**: Unmodified. Still contains exactly 12 application tables + `alembic_version`.
- **Alembic Baseline**: `alembic/versions/0001_baseline_existing_schema.py` remains untouched.
- **No Migration Created**: No Alembic migration file was generated in `alembic/versions/`.
- **No DDL Executed**: No `alembic upgrade`, `alembic downgrade`, `alembic revision`, or SQL DDL statements were executed against PostgreSQL.
- **No `create_all()`**: `Base.metadata.create_all()` was NOT introduced or called.

---

## 9. Validation Results

| Test / Check | Goal | Status | Result / Findings |
| :--- | :--- | :--- | :--- |
| **Model Import** | Import `User` and `RefreshToken` from `backend.db` | **PASSED** | Clean import without errors or circular dependencies. |
| **Declarative Base Registration** | Verify models inherit from existing `Base` | **PASSED** | `issubclass(User, Base)` and `issubclass(RefreshToken, Base)` both evaluate to `True`. |
| **SQLAlchemy Metadata Parity** | Confirm 14 total tables registered in `Base.metadata` | **PASSED** | Exactly 14 tables registered in memory (12 operational + `users` + `refresh_tokens`). |
| **Operational Models Preserved** | Confirm no original model was altered or removed | **PASSED** | All 12 original models (`telemetry`, `alerts`, `announcements`, etc.) remain registered. |
| **Relationship Resolution** | Verify bi-directional relationship mapping | **PASSED** | `User.refresh_tokens` maps to `RefreshToken` and `RefreshToken.user` maps to `User`. |
| **Live PostgreSQL Table Audit** | Verify live database table count and names | **PASSED** | Live database contains exactly 13 tables (12 operational + `alembic_version`). Neither `users` nor `refresh_tokens` exist in live DB. |
| **Alembic Baseline Check** | Verify migration history status | **PASSED** | `alembic current` confirms `0001_baseline_existing_schema (head)`. `alembic/versions/` contains only 1 baseline file. |
| **Application Regression Check** | Verify FastAPI startup & endpoints | **PASSED** | Server starts cleanly. `GET /`, `GET /health`, `GET /data` return `200 OK`. `GET /auth/signup` returns `404 Not Found`. |

---

## 10. Files Changed

1. **[`backend/db/models.py`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/models.py)**: Added `relationship` import from `sqlalchemy.orm`, defined `User` model, defined `RefreshToken` model.
2. **[`backend/db/__init__.py`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/backend/db/__init__.py)**: Exported `User` and `RefreshToken` in both package import paths and added them to `__all__`.
3. **[`Watchmen_Phase2A_AuthDB_Design.md`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/Watchmen_Phase2A_AuthDB_Design.md)**: Created this Phase 2A architecture documentation report.

---

## 11. Files Not Changed

- ❌ `alembic/versions/0001_baseline_existing_schema.py` — Untouched
- ❌ `alembic/env.py` — Untouched
- ❌ `backend/main.py` — Untouched
- ❌ `backend/db/base.py` — Untouched
- ❌ `backend/requirements.txt` — Untouched
- ❌ Existing operational models in `backend/db/models.py` — Untouched
- ❌ Live PostgreSQL tables in `watchmen_tracker` — Untouched
- ❌ Android source code — Untouched
- ❌ Frontend / dashboard source code — Untouched

---

## 12. Future Phase

The next stage (**Phase 2B**) will:
1. Generate the official Alembic migration file for `users` and `refresh_tokens` (`0002_add_authentication_tables.py`).
2. Upgrade the PostgreSQL database schema to apply the `users` and `refresh_tokens` tables.
3. Verify live database schema migration and revision stamping.

import os
import sys
import logging
from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine
from sqlalchemy.engine.url import make_url

# ----------------------------------------------------------------------
# 1. LOGGING SETUP
# ----------------------------------------------------------------------
config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

logger = logging.getLogger("alembic.env")

# ----------------------------------------------------------------------
# 2. DATABASE_URL RESOLUTION & VALIDATION
# ----------------------------------------------------------------------
try:
    from backend.config import get_database_url
except ImportError:
    from config import get_database_url

db_url = get_database_url()

if not db_url:
    error_msg = (
        "DATABASE_URL environment variable is not set.\n"
        "Please provide a valid PostgreSQL connection string, e.g.:\n"
        "  $env:DATABASE_URL=\"postgresql+psycopg2://postgres:<PASSWORD>@localhost:5432/watchmen_tracker\"\n"
        "SQLite fallback is strictly prohibited for Alembic migrations in Watchmen Tracker."
    )
    logger.error(error_msg)
    raise RuntimeError(error_msg)

if "sqlite" in db_url.lower():
    error_msg = (
        f"DATABASE_URL points to SQLite ({db_url}).\n"
        "Alembic in Watchmen Tracker is configured exclusively for PostgreSQL.\n"
        "SQLite fallback is not permitted."
    )
    logger.error(error_msg)
    raise RuntimeError(error_msg)

# Redact credentials for logging
try:
    parsed_url = make_url(db_url)
    safe_url = parsed_url.render_as_string(hide_password=True)
    logger.info("Database URL detected: %s", safe_url)
except Exception as e:
    logger.info("Database URL detected (credential redaction parsing failed: %s)", e)

# Override sqlalchemy.url in Alembic config
config.set_main_option("sqlalchemy.url", db_url)

# ----------------------------------------------------------------------
# 3. LOAD SQLALCHEMY METADATA SAFELY FROM DEDICATED DB PACKAGE
# ----------------------------------------------------------------------
# Ensure backend directory is in sys.path
current_dir = os.path.dirname(os.path.abspath(__file__))
possible_backend_dirs = [
    os.path.abspath(os.path.join(current_dir, "..", "backend")),
    os.path.abspath(os.path.join(current_dir, "backend")),
    os.path.abspath(os.path.join(current_dir, "..")),
]

backend_dir = None
for candidate in possible_backend_dirs:
    if os.path.isdir(os.path.join(candidate, "db")):
        backend_dir = candidate
        break

if not backend_dir:
    raise RuntimeError(f"Could not locate backend directory with db package from {current_dir}")

if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

# Import Base directly from dedicated database package (decoupled from main.py)
try:
    from backend.db import Base
except ImportError:
    from db import Base

target_metadata = Base.metadata

# Validate expected tables
EXPECTED_TABLES = {
    "alerts",
    "announcement_receipts",
    "announcements",
    "bug_reports",
    "checkpoints",
    "crash_reports",
    "geofence_events",
    "geofences",
    "incidents",
    "security_alerts",
    "telemetry",
    "trial_failures",
}

found_tables = set(target_metadata.tables.keys())
missing_tables = EXPECTED_TABLES - found_tables
if missing_tables:
    error_msg = f"Missing expected tables in Base.metadata: {missing_tables}"
    logger.error(error_msg)
    raise RuntimeError(error_msg)

logger.info(
    "SQLAlchemy metadata loaded: %d expected tables registered (%s)",
    len(found_tables),
    ", ".join(sorted(found_tables))
)

# ----------------------------------------------------------------------
# 4. OFFLINE MIGRATIONS
# ----------------------------------------------------------------------
def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode."""
    logger.info("Running migrations in offline mode.")
    context.configure(
        url=db_url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
        compare_server_default=True,
    )

    with context.begin_transaction():
        context.run_migrations()

# ----------------------------------------------------------------------
# 5. ONLINE MIGRATIONS
# ----------------------------------------------------------------------
def run_migrations_online() -> None:
    """Run migrations in 'online' mode."""
    logger.info("Initializing Alembic online migration environment.")
    
    connect_args = {}
    if "postgresql" in db_url or "postgres" in db_url:
        connect_args = {"options": "-c statement_timeout=60000"}

    connectable = create_engine(
        db_url,
        pool_pre_ping=True,
        connect_args=connect_args,
    )

    with connectable.connect() as connection:
        logger.info("Database connection successful.")
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
            compare_server_default=True,
        )

        with context.begin_transaction():
            context.run_migrations()

if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()

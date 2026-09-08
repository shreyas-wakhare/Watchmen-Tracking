import os
import logging
from pathlib import Path

logger = logging.getLogger("watchmen.config")

# Resolve Repository Root Directory
BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent

# Locate root .env file
ENV_PATH = REPO_ROOT / ".env"


def load_environment() -> None:
    """
    Centralized environment bootstrap for Watchmen Tracker.
    Loads root .env file into os.environ with override=False (process env precedence).
    """
    try:
        from dotenv import load_dotenv
        if ENV_PATH.exists():
            load_dotenv(dotenv_path=ENV_PATH, override=False)
            logger.debug(f"Loaded environment variables from {ENV_PATH}")
        else:
            # Fallback to current working directory .env
            load_dotenv(override=False)
    except ImportError:
        logger.warning("python-dotenv is not installed. Relying strictly on process environment variables.")


# Automatically execute environment loading on module import
load_environment()


# ------------------------------------------------------------
# CONFIGURATION GETTERS & VALIDATORS
# ------------------------------------------------------------

def get_database_url() -> str:
    """
    Retrieve and validate DATABASE_URL.
    Must be a valid PostgreSQL connection string. SQLite is strictly prohibited.
    """
    db_url = os.environ.get("DATABASE_URL")
    if not db_url or not db_url.strip():
        raise RuntimeError(
            "DATABASE_URL environment variable is missing or empty. "
            "Watchmen Tracker requires an explicit PostgreSQL connection string in .env or environment, e.g.:\n"
            "  DATABASE_URL=postgresql+psycopg2://postgres:<PASSWORD>@localhost:5432/watchmen_tracker"
        )
    
    db_url = db_url.strip()
    if "sqlite" in db_url.lower():
        raise RuntimeError(
            "DATABASE_URL points to SQLite. "
            "Watchmen Tracker operates exclusively on PostgreSQL in this environment. "
            "SQLite fallback is strictly prohibited."
        )
    
    return db_url


def get_jwt_secret_key() -> str:
    """
    Retrieve and validate JWT_SECRET_KEY.
    Must be a non-empty string. Never returns fallback defaults.
    """
    secret = os.environ.get("JWT_SECRET_KEY")
    if not secret or not secret.strip():
        raise RuntimeError(
            "JWT_SECRET_KEY environment variable is missing or empty. "
            "Authentication requires an explicitly set JWT_SECRET_KEY in .env or environment."
        )
    return secret.strip()


def get_jwt_algorithm() -> str:
    """
    Retrieve and validate JWT_ALGORITHM.
    Restricted to HS256 algorithm only.
    """
    algorithm = os.environ.get("JWT_ALGORITHM", "HS256").strip()
    allowed_algorithms = {"HS256"}
    if algorithm not in allowed_algorithms:
        raise RuntimeError(
            f"Unsupported JWT_ALGORITHM: '{algorithm}'. "
            f"Allowed algorithms: {', '.join(sorted(allowed_algorithms))}"
        )
    return algorithm


def get_access_token_expire_minutes() -> int:
    """
    Retrieve and validate ACCESS_TOKEN_EXPIRE_MINUTES.
    Must be a positive integer. Defaults to 30.
    """
    raw_val = os.environ.get("ACCESS_TOKEN_EXPIRE_MINUTES")
    if raw_val is None or not raw_val.strip():
        return 30
    try:
        val = int(raw_val.strip())
        if val <= 0:
            raise ValueError()
        return val
    except ValueError:
        raise RuntimeError(
            f"Invalid ACCESS_TOKEN_EXPIRE_MINUTES environment variable: '{raw_val}'. "
            "Must be a positive integer."
        )


def get_refresh_token_expire_days() -> int:
    """
    Retrieve and validate REFRESH_TOKEN_EXPIRE_DAYS.
    Must be a positive integer. Defaults to 30.
    """
    raw_val = os.environ.get("REFRESH_TOKEN_EXPIRE_DAYS")
    if raw_val is None or not raw_val.strip():
        return 30
    try:
        val = int(raw_val.strip())
        if val <= 0:
            raise ValueError()
        return val
    except ValueError:
        raise RuntimeError(
            f"Invalid REFRESH_TOKEN_EXPIRE_DAYS environment variable: '{raw_val}'. "
            "Must be a positive integer."
        )


def get_server_port() -> int:
    """
    Retrieve SERVER PORT. Defaults to 8000.
    """
    raw_val = os.environ.get("PORT", "8000")
    try:
        return int(raw_val.strip())
    except ValueError:
        return 8000

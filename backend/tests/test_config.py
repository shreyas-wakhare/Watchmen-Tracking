import os
import sys
from pathlib import Path

# Ensure backend directory is in sys.path
repo_root = Path(__file__).resolve().parent.parent.parent
backend_dir = repo_root / "backend"
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from backend.config import (
    get_database_url,
    get_jwt_secret_key,
    get_jwt_algorithm,
    get_access_token_expire_minutes,
    get_refresh_token_expire_days,
    load_environment,
)

TEST_RESULTS = []


def record_test(test_id, category, name, expected, actual, status_str):
    TEST_RESULTS.append({
        "id": test_id,
        "category": category,
        "name": name,
        "expected": expected,
        "actual": actual,
        "status": status_str
    })
    print(f"[{status_str}] {test_id} - {name} | Expected: {expected} | Actual: {actual}")


def test_config_suite():
    print("============================================================")
    print("   WATCHMEN TRACKER - CONFIGURATION & SECRETS TEST SUITE    ")
    print("============================================================\n")

    # Save initial environment
    orig_env = dict(os.environ)

    try:
        # ---------------------------------------------------------
        # TEST 1: Automatic .env loading
        # ---------------------------------------------------------
        load_environment()
        db_url = os.environ.get("DATABASE_URL")
        jwt_key = os.environ.get("JWT_SECRET_KEY")
        if db_url and jwt_key:
            record_test("CFG-AUTO-001", "Env Loading", "Automatic .env loading on startup", "DATABASE_URL & JWT_SECRET_KEY present", "Loaded successfully", "PASS")
        else:
            record_test("CFG-AUTO-001", "Env Loading", "Automatic .env loading on startup", "DATABASE_URL & JWT_SECRET_KEY present", "Missing required env vars", "FAIL")

        # ---------------------------------------------------------
        # TEST 2: Process Environment Precedence (System Env > .env)
        # ---------------------------------------------------------
        os.environ["JWT_SECRET_KEY"] = "override-secret-key-process-env-12345"
        os.environ["JWT_ALGORITHM"] = "HS256"
        load_environment() # Should NOT overwrite existing process env vars
        actual_key = get_jwt_secret_key()
        if actual_key == "override-secret-key-process-env-12345":
            record_test("CFG-PREC-001", "Precedence", "Process ENV overrides .env (override=False)", "override-secret-key-process-env-12345", "Precedence preserved", "PASS")
        else:
            record_test("CFG-PREC-001", "Precedence", "Process ENV overrides .env (override=False)", "override-secret-key-process-env-12345", actual_key, "FAIL")

        # ---------------------------------------------------------
        # TEST 3: Missing JWT Secret Key Fail-Fast
        # ---------------------------------------------------------
        if "JWT_SECRET_KEY" in os.environ:
            del os.environ["JWT_SECRET_KEY"]
        try:
            get_jwt_secret_key()
            record_test("CFG-SEC-001", "JWT Secret", "Missing JWT_SECRET_KEY fail-fast", "RuntimeError", "No error raised", "FAIL")
        except RuntimeError as e:
            record_test("CFG-SEC-001", "JWT Secret", "Missing JWT_SECRET_KEY fail-fast", "RuntimeError", "Safely caught RuntimeError", "PASS")

        # Empty string JWT Secret Key check
        os.environ["JWT_SECRET_KEY"] = "   "
        try:
            get_jwt_secret_key()
            record_test("CFG-SEC-002", "JWT Secret", "Blank JWT_SECRET_KEY fail-fast", "RuntimeError", "No error raised", "FAIL")
        except RuntimeError:
            record_test("CFG-SEC-002", "JWT Secret", "Blank JWT_SECRET_KEY fail-fast", "RuntimeError", "Safely caught RuntimeError", "PASS")

        # ---------------------------------------------------------
        # TEST 4: Invalid JWT Algorithm Fail-Fast
        # ---------------------------------------------------------
        os.environ["JWT_ALGORITHM"] = "none"
        try:
            get_jwt_algorithm()
            record_test("CFG-ALG-001", "Algorithm", "Unsupported JWT algorithm 'none' fail-fast", "RuntimeError", "No error raised", "FAIL")
        except RuntimeError:
            record_test("CFG-ALG-001", "Algorithm", "Unsupported JWT algorithm 'none' fail-fast", "RuntimeError", "Safely caught RuntimeError", "PASS")

        os.environ["JWT_ALGORITHM"] = "HS256"
        if get_jwt_algorithm() == "HS256":
            record_test("CFG-ALG-002", "Algorithm", "Supported HS256 algorithm", "HS256", "HS256", "PASS")
        else:
            record_test("CFG-ALG-002", "Algorithm", "Supported HS256 algorithm", "HS256", get_jwt_algorithm(), "FAIL")

        # ---------------------------------------------------------
        # TEST 5: Expiration Settings Fail-Fast & Defaults
        # ---------------------------------------------------------
        if "ACCESS_TOKEN_EXPIRE_MINUTES" in os.environ:
            del os.environ["ACCESS_TOKEN_EXPIRE_MINUTES"]
        if get_access_token_expire_minutes() == 30:
            record_test("CFG-EXP-001", "Expiration", "Default ACCESS_TOKEN_EXPIRE_MINUTES", "30", "30", "PASS")
        else:
            record_test("CFG-EXP-001", "Expiration", "Default ACCESS_TOKEN_EXPIRE_MINUTES", "30", str(get_access_token_expire_minutes()), "FAIL")

        os.environ["ACCESS_TOKEN_EXPIRE_MINUTES"] = "-5"
        try:
            get_access_token_expire_minutes()
            record_test("CFG-EXP-002", "Expiration", "Negative expiration fail-fast", "RuntimeError", "No error raised", "FAIL")
        except RuntimeError:
            record_test("CFG-EXP-002", "Expiration", "Negative expiration fail-fast", "RuntimeError", "Safely caught RuntimeError", "PASS")

        # ---------------------------------------------------------
        # TEST 6: DATABASE_URL SQLite Fail-Fast
        # ---------------------------------------------------------
        os.environ["DATABASE_URL"] = "sqlite:///./test.db"
        try:
            get_database_url()
            record_test("CFG-DB-001", "Database URL", "SQLite DATABASE_URL fail-fast", "RuntimeError", "No error raised", "FAIL")
        except RuntimeError:
            record_test("CFG-DB-001", "Database URL", "SQLite DATABASE_URL fail-fast", "RuntimeError", "Safely caught RuntimeError", "PASS")

        # ---------------------------------------------------------
        # TEST 7: Missing DATABASE_URL Fail-Fast
        # ---------------------------------------------------------
        if "DATABASE_URL" in os.environ:
            del os.environ["DATABASE_URL"]
        try:
            get_database_url()
            record_test("CFG-DB-002", "Database URL", "Missing DATABASE_URL fail-fast", "RuntimeError", "No error raised", "FAIL")
        except RuntimeError:
            record_test("CFG-DB-002", "Database URL", "Missing DATABASE_URL fail-fast", "RuntimeError", "Safely caught RuntimeError", "PASS")

    finally:
        # Restore original environment
        os.environ.clear()
        os.environ.update(orig_env)

    passed_count = sum(1 for t in TEST_RESULTS if t["status"] == "PASS")
    failed_count = sum(1 for t in TEST_RESULTS if t["status"] == "FAIL")

    print("\n============================================================")
    print(f"   CONFIG TEST SUMMARY: Executed={len(TEST_RESULTS)} | Passed={passed_count} | Failed={failed_count}")
    print("============================================================\n")

    assert failed_count == 0, f"Config test suite failed with {failed_count} errors"


if __name__ == "__main__":
    test_config_suite()

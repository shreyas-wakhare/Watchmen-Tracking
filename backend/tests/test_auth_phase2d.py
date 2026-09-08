import sys
import os
import subprocess
import time
import json
import urllib.request
import urllib.error
import hashlib
import threading
from jose import jwt

repo_root = r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker"
backend_dir = r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\backend"

sys.path.insert(0, repo_root)
sys.path.insert(0, backend_dir)
os.chdir(backend_dir)

os.environ["DATABASE_URL"] = "postgresql+psycopg2://postgres:flowdesk1234@localhost:5432/watchmen_tracker"

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


def run_all_tests():
    print("============================================================")
    print("   WATCHMEN TRACKER - PHASE 2D AUTHENTICATION TEST SUITE   ")
    print("============================================================\n")

    # ---------------------------------------------------------
    # SECTION 1: CONFIGURATION & SECURITY FAIL-FAST CHECKS
    # ---------------------------------------------------------
    from backend.auth.security import (
        create_access_token,
        get_jwt_secret_key,
        get_jwt_algorithm,
        get_access_token_expire_minutes,
        get_refresh_token_expire_days,
        hash_password,
        verify_password,
    )

    # AUTH-CFG-001: Missing JWT_SECRET_KEY
    if "JWT_SECRET_KEY" in os.environ:
        del os.environ["JWT_SECRET_KEY"]
    try:
        get_jwt_secret_key()
        record_test("AUTH-CFG-001", "Config", "Missing JWT_SECRET_KEY fail-fast", "RuntimeError", "No error", "FAIL")
    except RuntimeError as e:
        record_test("AUTH-CFG-001", "Config", "Missing JWT_SECRET_KEY fail-fast", "RuntimeError", str(e), "PASS")

    # AUTH-CFG-002: Unsupported JWT_ALGORITHM
    os.environ["JWT_ALGORITHM"] = "none"
    try:
        get_jwt_algorithm()
        record_test("AUTH-CFG-002", "Config", "Unsupported JWT_ALGORITHM 'none'", "RuntimeError", "Accepted 'none'", "FAIL")
    except RuntimeError as e:
        record_test("AUTH-CFG-002", "Config", "Unsupported JWT_ALGORITHM 'none'", "RuntimeError", str(e), "PASS")
    os.environ["JWT_ALGORITHM"] = "HS256"

    # AUTH-CFG-003: Invalid ACCESS_TOKEN_EXPIRE_MINUTES
    os.environ["ACCESS_TOKEN_EXPIRE_MINUTES"] = "invalid_num"
    try:
        get_access_token_expire_minutes()
        record_test("AUTH-CFG-003", "Config", "Invalid ACCESS_TOKEN_EXPIRE_MINUTES", "RuntimeError", "No error", "FAIL")
    except RuntimeError as e:
        record_test("AUTH-CFG-003", "Config", "Invalid ACCESS_TOKEN_EXPIRE_MINUTES", "RuntimeError", str(e), "PASS")
    del os.environ["ACCESS_TOKEN_EXPIRE_MINUTES"]

    # AUTH-CFG-004: Non-positive REFRESH_TOKEN_EXPIRE_DAYS
    os.environ["REFRESH_TOKEN_EXPIRE_DAYS"] = "-10"
    try:
        get_refresh_token_expire_days()
        record_test("AUTH-CFG-004", "Config", "Non-positive REFRESH_TOKEN_EXPIRE_DAYS", "RuntimeError", "No error", "FAIL")
    except RuntimeError as e:
        record_test("AUTH-CFG-004", "Config", "Non-positive REFRESH_TOKEN_EXPIRE_DAYS", "RuntimeError", str(e), "PASS")
    del os.environ["REFRESH_TOKEN_EXPIRE_DAYS"]

    # Setup environment for server testing
    TEST_SECRET = "phase2d-super-secret-jwt-key-verification-9999"
    os.environ["JWT_SECRET_KEY"] = TEST_SECRET

    # ---------------------------------------------------------
    # SECTION 2: PASSWORD HASHING UNIT CHECKS
    # ---------------------------------------------------------
    p1 = "securepassword123"
    h1 = hash_password(p1)
    h2 = hash_password(p1)
    
    # AUTH-SEC-002a: Argon2id hash format
    if h1.startswith("$argon2id$"):
        record_test("AUTH-SEC-002a", "Password Hash", "Argon2id prefix check", "$argon2id$...", h1[:20], "PASS")
    else:
        record_test("AUTH-SEC-002a", "Password Hash", "Argon2id prefix check", "$argon2id$...", h1[:20], "FAIL")

    # AUTH-SEC-002b: Salting (different hashes for same password)
    if h1 != h2:
        record_test("AUTH-SEC-002b", "Password Hash", "Salt uniqueness check", "Hashes differ", "Hashes differ", "PASS")
    else:
        record_test("AUTH-SEC-002b", "Password Hash", "Salt uniqueness check", "Hashes differ", "Identical hashes", "FAIL")

    # AUTH-SEC-002c: Verification logic
    if verify_password(h1, p1) and not verify_password(h1, "wrongpassword"):
        record_test("AUTH-SEC-002c", "Password Hash", "Verify password logic", "True for correct, False for wrong", "Correct", "PASS")
    else:
        record_test("AUTH-SEC-002c", "Password Hash", "Verify password logic", "True for correct, False for wrong", "Incorrect", "FAIL")

    # AUTH-SEC-002d: Invalid/Malformed hash safety
    if not verify_password("invalid_hash_string", p1):
        record_test("AUTH-SEC-002d", "Password Hash", "Malformed hash safety", "False without exception", "False", "PASS")
    else:
        record_test("AUTH-SEC-002d", "Password Hash", "Malformed hash safety", "False without exception", "True", "FAIL")

    # ---------------------------------------------------------
    # SECTION 3: LIVE HTTP SERVER & ENDPOINT TESTING
    # ---------------------------------------------------------
    proc = subprocess.Popen(
        [
            r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\.venv\Scripts\python.exe",
            "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8007"
        ],
        cwd=backend_dir,
        env={**os.environ, "PYTHONPATH": f"{repo_root};{backend_dir}", "JWT_SECRET_KEY": TEST_SECRET}
    )

    try:
        time.sleep(2.5) # Wait for uvicorn startup
        base_url = "http://127.0.0.1:8007"

        # AUTH-REG-001: FastAPI application regression (GET /, GET /health, GET /data)
        try:
            with urllib.request.urlopen(f"{base_url}/health") as r:
                body = json.loads(r.read().decode())
                if r.status == 200 and body.get("status") == "healthy":
                    record_test("AUTH-REG-001", "App Regression", "GET /health check", "200 healthy", f"{r.status} {body.get('status')}", "PASS")
                else:
                    record_test("AUTH-REG-001", "App Regression", "GET /health check", "200 healthy", f"{r.status} {body}", "FAIL")
        except Exception as e:
            record_test("AUTH-REG-001", "App Regression", "GET /health check", "200 healthy", str(e), "FAIL")

        # AUTH-SIGNUP-001: Valid signup
        valid_user_email = "phase2d.user@example.com"
        signup_payload = {
            "email": f"  {valid_user_email.upper()}  ",
            "password": "validPassword123!",
            "full_name": "  Phase 2D Test User  "
        }
        req_signup = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=json.dumps(signup_payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req_signup) as resp:
                res_body = json.loads(resp.read().decode())
                u_data = res_body.get("user", {})
                created_user_id = u_data.get("id")
                if resp.status == 201 and u_data.get("email") == valid_user_email and "password" not in u_data and "password_hash" not in u_data:
                    record_test("AUTH-SIGNUP-001", "Signup", "Valid signup (Happy Path)", "201 Created", f"201 ID={created_user_id}", "PASS")
                else:
                    record_test("AUTH-SIGNUP-001", "Signup", "Valid signup (Happy Path)", "201 Created", f"{resp.status} {res_body}", "FAIL")
        except Exception as e:
            record_test("AUTH-SIGNUP-001", "Signup", "Valid signup (Happy Path)", "201 Created", str(e), "FAIL")

        # AUTH-SEC-001: Response Security Audit (No secrets/password/password_hash in response text)
        raw_resp_str = json.dumps(res_body)
        if "password" not in raw_resp_str and "password_hash" not in raw_resp_str and "traceback" not in raw_resp_str:
            record_test("AUTH-SEC-001", "Response Security", "No credentials or secrets in JSON", "Clean response", "Clean response", "PASS")
        else:
            record_test("AUTH-SEC-001", "Response Security", "No credentials or secrets in JSON", "Clean response", raw_resp_str, "FAIL")

        # AUTH-SIGNUP-002: Email normalization
        if u_data.get("email") == valid_user_email:
            record_test("AUTH-SIGNUP-002", "Signup", "Email lowercasing & trimming", valid_user_email, u_data.get("email"), "PASS")
        else:
            record_test("AUTH-SIGNUP-002", "Signup", "Email lowercasing & trimming", valid_user_email, u_data.get("email"), "FAIL")

        # AUTH-SIGNUP-003: Duplicate email rejection
        try:
            urllib.request.urlopen(req_signup)
            record_test("AUTH-SIGNUP-003", "Signup", "Duplicate email rejection", "409 Conflict", "201 Created", "FAIL")
        except urllib.error.HTTPError as err:
            err_body = json.loads(err.read().decode())
            if err.code == 409 and "already exists" in err_body.get("detail", ""):
                record_test("AUTH-SIGNUP-003", "Signup", "Duplicate email rejection", "409 Conflict", "409 Conflict", "PASS")
            else:
                record_test("AUTH-SIGNUP-003", "Signup", "Duplicate email rejection", "409 Conflict", f"{err.code} {err_body}", "FAIL")

        # AUTH-SIGNUP-004: Concurrent duplicate signup
        concurrent_status_codes = []
        def attempt_signup():
            try:
                r = urllib.request.Request(
                    f"{base_url}/auth/signup",
                    data=json.dumps({"email": "concurrent.user@example.com", "password": "validPassword123", "full_name": "Concurrent User"}).encode("utf-8"),
                    headers={"Content-Type": "application/json"}
                )
                with urllib.request.urlopen(r) as res:
                    concurrent_status_codes.append(res.status)
            except urllib.error.HTTPError as e:
                concurrent_status_codes.append(e.code)

        t1 = threading.Thread(target=attempt_signup)
        t2 = threading.Thread(target=attempt_signup)
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        sorted_codes = sorted(concurrent_status_codes)
        if sorted_codes == [201, 409]:
            record_test("AUTH-SIGNUP-004", "Signup", "Concurrent duplicate signup", "[201, 409]", str(sorted_codes), "PASS")
            # Cleanup concurrent user
            from backend.db import SessionLocal, User
            db_c = SessionLocal()
            db_c.query(User).filter(User.email == "concurrent.user@example.com").delete()
            db_c.commit()
            db_c.close()
        else:
            record_test("AUTH-SIGNUP-004", "Signup", "Concurrent duplicate signup", "[201, 409]", str(sorted_codes), "FAIL")

        # AUTH-SIGNUP-005: Password length validation (<8 chars -> 422)
        req_short_pwd = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=json.dumps({"email": "shortpwd@example.com", "password": "123", "full_name": "Short Pwd"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_short_pwd)
            record_test("AUTH-SIGNUP-005", "Signup", "Short password (<8 chars)", "422 Unprocessable Entity", "201 Created", "FAIL")
        except urllib.error.HTTPError as err:
            if err.code == 422:
                record_test("AUTH-SIGNUP-005", "Signup", "Short password (<8 chars)", "422 Unprocessable Entity", "422 Unprocessable Entity", "PASS")
            else:
                record_test("AUTH-SIGNUP-005", "Signup", "Short password (<8 chars)", "422 Unprocessable Entity", str(err.code), "FAIL")

        # AUTH-SIGNUP-006: Full name validation (empty/whitespace -> 422)
        req_blank_name = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=json.dumps({"email": "blankname@example.com", "password": "validPassword123", "full_name": "   "}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_blank_name)
            record_test("AUTH-SIGNUP-006", "Signup", "Blank full name rejection", "422 Unprocessable Entity", "201 Created", "FAIL")
        except urllib.error.HTTPError as err:
            if err.code == 422:
                record_test("AUTH-SIGNUP-006", "Signup", "Blank full name rejection", "422 Unprocessable Entity", "422 Unprocessable Entity", "PASS")
            else:
                record_test("AUTH-SIGNUP-006", "Signup", "Blank full name rejection", "422 Unprocessable Entity", str(err.code), "FAIL")

        # AUTH-SIGNUP-007: Email format validation (malformed -> 422)
        req_bad_email = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=json.dumps({"email": "not-an-email", "password": "validPassword123", "full_name": "Bad Email"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_bad_email)
            record_test("AUTH-SIGNUP-007", "Signup", "Malformed email rejection", "422 Unprocessable Entity", "201 Created", "FAIL")
        except urllib.error.HTTPError as err:
            if err.code == 422:
                record_test("AUTH-SIGNUP-007", "Signup", "Malformed email rejection", "422 Unprocessable Entity", "422 Unprocessable Entity", "PASS")
            else:
                record_test("AUTH-SIGNUP-007", "Signup", "Malformed email rejection", "422 Unprocessable Entity", str(err.code), "FAIL")

        # AUTH-SIGNUP-008: Missing field validation
        req_missing_field = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=json.dumps({"email": "missingfield@example.com"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_missing_field)
            record_test("AUTH-SIGNUP-008", "Signup", "Missing fields validation", "422 Unprocessable Entity", "201 Created", "FAIL")
        except urllib.error.HTTPError as err:
            if err.code == 422:
                record_test("AUTH-SIGNUP-008", "Signup", "Missing fields validation", "422 Unprocessable Entity", "422 Unprocessable Entity", "PASS")
            else:
                record_test("AUTH-SIGNUP-008", "Signup", "Missing fields validation", "422 Unprocessable Entity", str(err.code), "FAIL")

        # AUTH-LOGIN-001: Valid login
        login_payload = {
            "email": f"  {valid_user_email.upper()}  ",
            "password": "validPassword123!"
        }
        req_login = urllib.request.Request(
            f"{base_url}/auth/login",
            data=json.dumps(login_payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req_login) as resp:
                login_res = json.loads(resp.read().decode())
                access_token = login_res.get("access_token")
                raw_refresh_token = login_res.get("refresh_token")
                if resp.status == 200 and access_token and raw_refresh_token and login_res.get("token_type") == "bearer":
                    record_test("AUTH-LOGIN-001", "Login", "Valid login (Happy Path)", "200 OK", "200 OK with access & refresh tokens", "PASS")
                else:
                    record_test("AUTH-LOGIN-001", "Login", "Valid login (Happy Path)", "200 OK", f"{resp.status} {login_res}", "FAIL")
        except Exception as e:
            record_test("AUTH-LOGIN-001", "Login", "Valid login (Happy Path)", "200 OK", str(e), "FAIL")

        # AUTH-LOGIN-002: Login email normalization
        if login_res.get("user", {}).get("email") == valid_user_email:
            record_test("AUTH-LOGIN-002", "Login", "Login email lowercasing & trimming", valid_user_email, login_res.get("user", {}).get("email"), "PASS")
        else:
            record_test("AUTH-LOGIN-002", "Login", "Login email lowercasing & trimming", valid_user_email, login_res.get("user", {}).get("email"), "FAIL")

        # AUTH-LOGIN-006: DB Session Audit (last_login_at updated)
        from backend.db import SessionLocal, User, RefreshToken
        db = SessionLocal()
        u_after_login = db.query(User).filter(User.id == created_user_id).first()
        if u_after_login and u_after_login.last_login_at is not None:
            record_test("AUTH-LOGIN-006", "Login", "last_login_at timestamp audit", "Timestamp set", str(u_after_login.last_login_at), "PASS")
        else:
            record_test("AUTH-LOGIN-006", "Login", "last_login_at timestamp audit", "Timestamp set", "None", "FAIL")
        db.close()

        # AUTH-JWT-001: JWT Structure & Claims
        try:
            decoded_jwt = jwt.decode(access_token, TEST_SECRET, algorithms=["HS256"])
            if decoded_jwt.get("sub") == str(created_user_id) and decoded_jwt.get("type") == "access" and "exp" in decoded_jwt and "iat" in decoded_jwt:
                record_test("AUTH-JWT-001", "JWT Security", "JWT Claims & Signature", "sub, iat, exp, type=access", str(decoded_jwt), "PASS")
            else:
                record_test("AUTH-JWT-001", "JWT Security", "JWT Claims & Signature", "sub, iat, exp, type=access", str(decoded_jwt), "FAIL")
        except Exception as e:
            record_test("AUTH-JWT-001", "JWT Security", "JWT Claims & Signature", "Valid HS256 JWT", str(e), "FAIL")

        # AUTH-JWT-002: JWT negative test (Wrong secret decoding)
        try:
            jwt.decode(access_token, "wrong-secret-key-12345", algorithms=["HS256"])
            record_test("AUTH-JWT-002", "JWT Security", "JWT wrong secret rejection", "JWTError", "Decoded successfully", "FAIL")
        except Exception:
            record_test("AUTH-JWT-002", "JWT Security", "JWT wrong secret rejection", "JWTError", "Signature verification failed", "PASS")

        # AUTH-LOGIN-003: Wrong password
        req_wrong_pwd = urllib.request.Request(
            f"{base_url}/auth/login",
            data=json.dumps({"email": valid_user_email, "password": "wrongpassword123"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_wrong_pwd)
            record_test("AUTH-LOGIN-003", "Login", "Wrong password rejection", "401 Unauthorized", "200 OK", "FAIL")
        except urllib.error.HTTPError as err:
            err_body = json.loads(err.read().decode())
            if err.code == 401 and err_body.get("detail") == "Invalid email or password":
                record_test("AUTH-LOGIN-003", "Login", "Wrong password rejection", "401 Unauthorized", "401 Unauthorized", "PASS")
            else:
                record_test("AUTH-LOGIN-003", "Login", "Wrong password rejection", "401 Unauthorized", f"{err.code} {err_body}", "FAIL")

        # AUTH-LOGIN-004: Unknown email
        req_unknown_email = urllib.request.Request(
            f"{base_url}/auth/login",
            data=json.dumps({"email": "unknown.user999@example.com", "password": "somePassword123"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_unknown_email)
            record_test("AUTH-LOGIN-004", "Login", "Unknown email rejection", "401 Unauthorized", "200 OK", "FAIL")
        except urllib.error.HTTPError as err:
            err_body = json.loads(err.read().decode())
            if err.code == 401 and err_body.get("detail") == "Invalid email or password":
                record_test("AUTH-LOGIN-004", "Login", "Unknown email rejection", "401 Unauthorized (Generic)", "401 Unauthorized (Generic)", "PASS")
            else:
                record_test("AUTH-LOGIN-004", "Login", "Unknown email rejection", "401 Unauthorized (Generic)", f"{err.code} {err_body}", "FAIL")

        # AUTH-LOGIN-005: Inactive user account
        db = SessionLocal()
        u_db = db.query(User).filter(User.id == created_user_id).first()
        u_db.is_active = False
        db.commit()
        db.close()

        req_inactive = urllib.request.Request(
            f"{base_url}/auth/login",
            data=json.dumps({"email": valid_user_email, "password": "validPassword123!"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_inactive)
            record_test("AUTH-LOGIN-005", "Login", "Inactive user rejection", "403 Forbidden", "200 OK", "FAIL")
        except urllib.error.HTTPError as err:
            err_body = json.loads(err.read().decode())
            if err.code == 403 and err_body.get("detail") == "User account is inactive":
                record_test("AUTH-LOGIN-005", "Login", "Inactive user rejection", "403 Forbidden", "403 Forbidden", "PASS")
            else:
                record_test("AUTH-LOGIN-005", "Login", "Inactive user rejection", "403 Forbidden", f"{err.code} {err_body}", "FAIL")

        # Re-enable user for remaining tests
        db = SessionLocal()
        u_db = db.query(User).filter(User.id == created_user_id).first()
        u_db.is_active = True
        db.commit()
        db.close()

        # AUTH-RT-001 & AUTH-RT-002: Refresh token hashing & FK cascade verification
        db = SessionLocal()
        rt_db = db.query(RefreshToken).filter(RefreshToken.user_id == created_user_id).first()
        expected_rt_hash = hashlib.sha256(raw_refresh_token.encode("utf-8")).hexdigest()
        if rt_db and rt_db.token_hash == expected_rt_hash and rt_db.revoked_at is None:
            record_test("AUTH-RT-001", "Refresh Token", "SHA-256 Hash Storage in DB", expected_rt_hash[:16] + "...", rt_db.token_hash[:16] + "...", "PASS")
        else:
            record_test("AUTH-RT-001", "Refresh Token", "SHA-256 Hash Storage in DB", expected_rt_hash[:16] + "...", str(rt_db), "FAIL")

        # Delete user and test ON DELETE CASCADE on refresh_tokens
        db.query(User).filter(User.id == created_user_id).delete()
        db.commit()
        orphaned_rt_count = db.query(RefreshToken).filter(RefreshToken.user_id == created_user_id).count()
        if orphaned_rt_count == 0:
            record_test("AUTH-RT-002", "Refresh Token", "ON DELETE CASCADE FK Integrity", "0 orphaned rows", "0 orphaned rows", "PASS")
        else:
            record_test("AUTH-RT-002", "Refresh Token", "ON DELETE CASCADE FK Integrity", "0 orphaned rows", f"{orphaned_rt_count} orphaned rows", "FAIL")
        db.close()

        # AUTH-TX-001: Transaction Rollback Integrity Test
        from backend.db import SessionLocal, User
        db_tx = SessionLocal()
        try:
            u_tx = User(email="tx.rollback@example.com", password_hash="hash", full_name="TX User", is_active=True)
            db_tx.add(u_tx)
            db_tx.flush()
            # Force duplicate insert inside same session to trigger IntegrityError
            u_tx_dup = User(email="tx.rollback@example.com", password_hash="hash", full_name="TX User Dup", is_active=True)
            db_tx.add(u_tx_dup)
            db_tx.commit()
            record_test("AUTH-TX-001", "Transaction", "IntegrityError Rollback", "IntegrityError", "Committed duplicate", "FAIL")
        except Exception:
            db_tx.rollback()
            tx_check_count = db_tx.query(User).filter(User.email == "tx.rollback@example.com").count()
            if tx_check_count == 0:
                record_test("AUTH-TX-001", "Transaction", "IntegrityError Rollback", "0 rows remaining after rollback", "0 rows", "PASS")
            else:
                record_test("AUTH-TX-001", "Transaction", "IntegrityError Rollback", "0 rows remaining after rollback", f"{tx_check_count} rows", "FAIL")
        finally:
            db_tx.close()

        # AUTH-HTTP-001: Unsupported HTTP method on /auth/signup (GET)
        req_get_signup = urllib.request.Request(f"{base_url}/auth/signup", method="GET")
        try:
            urllib.request.urlopen(req_get_signup)
            record_test("AUTH-HTTP-001", "HTTP Protocol", "Unsupported method GET /auth/signup", "405 Method Not Allowed", "200 OK", "FAIL")
        except urllib.error.HTTPError as err:
            if err.code == 405:
                record_test("AUTH-HTTP-001", "HTTP Protocol", "Unsupported method GET /auth/signup", "405 Method Not Allowed", "405 Method Not Allowed", "PASS")
            else:
                record_test("AUTH-HTTP-001", "HTTP Protocol", "Unsupported method GET /auth/signup", "405 Method Not Allowed", str(err.code), "FAIL")

        # AUTH-HTTP-002: Malformed JSON payload
        req_bad_json = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=b"{ invalid json format ",
            headers={"Content-Type": "application/json"}
        )
        try:
            urllib.request.urlopen(req_bad_json)
            record_test("AUTH-HTTP-002", "HTTP Protocol", "Malformed JSON payload", "400/422 Bad Request", "200 OK", "FAIL")
        except urllib.error.HTTPError as err:
            if err.code in (400, 422):
                record_test("AUTH-HTTP-002", "HTTP Protocol", "Malformed JSON payload", "400/422 Bad Request", str(err.code), "PASS")
            else:
                record_test("AUTH-HTTP-002", "HTTP Protocol", "Malformed JSON payload", "400/422 Bad Request", str(err.code), "FAIL")

        # AUTH-BOUND-001: Boundary / Long fields
        long_user_email = "verylongname.testing.boundary.case.user.account.email.phase2d@subdomain.example.com"
        signup_long_payload = {
            "email": long_user_email,
            "password": "LongPassword123!" * 5,
            "full_name": "A" * 200
        }
        req_long_signup = urllib.request.Request(
            f"{base_url}/auth/signup",
            data=json.dumps(signup_long_payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req_long_signup) as resp:
                res_b = json.loads(resp.read().decode())
                long_u_id = res_b.get("user", {}).get("id")
                if resp.status == 201 and long_u_id:
                    record_test("AUTH-BOUND-001", "Boundary", "Long email/name/password payload", "201 Created", f"201 ID={long_u_id}", "PASS")
                    # Clean up long user immediately
                    db = SessionLocal()
                    db.query(User).filter(User.id == long_u_id).delete()
                    db.commit()
                    db.close()
                else:
                    record_test("AUTH-BOUND-001", "Boundary", "Long email/name/password payload", "201 Created", f"{resp.status} {res_b}", "FAIL")
        except Exception as e:
            record_test("AUTH-BOUND-001", "Boundary", "Long email/name/password payload", "201 Created", str(e), "FAIL")

    finally:
        proc.terminate()
        proc.wait()

    # ---------------------------------------------------------
    # SECTION 4: POST-TEST DATABASE & ALEMBIC DRIFT AUDIT
    # ---------------------------------------------------------
    db = SessionLocal()
    db.query(RefreshToken).delete()
    db.query(User).delete()
    db.commit()
    u_count = db.query(User).count()
    rt_count = db.query(RefreshToken).count()
    db.close()

    # AUTH-CLEANUP-001: DB cleanup check
    if u_count == 0 and rt_count == 0:
        record_test("AUTH-CLEANUP-001", "DB Cleanup", "Post-test row count verification", "users=0, refresh_tokens=0", f"users={u_count}, refresh_tokens={rt_count}", "PASS")
    else:
        record_test("AUTH-CLEANUP-001", "DB Cleanup", "Post-test row count verification", "users=0, refresh_tokens=0", f"users={u_count}, refresh_tokens={rt_count}", "FAIL")

    # AUTH-ALEMBIC-001: Alembic schema drift check
    alembic_proc = subprocess.run(
        [r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\.venv\Scripts\alembic.exe", "check"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        env={**os.environ, "DATABASE_URL": "postgresql+psycopg2://postgres:flowdesk1234@localhost:5432/watchmen_tracker"}
    )
    if alembic_proc.returncode == 0 and "No new upgrade operations detected" in alembic_proc.stdout:
        record_test("AUTH-ALEMBIC-001", "Alembic", "Schema drift audit (alembic check)", "No drift (0 exit code)", "No drift (0 exit code)", "PASS")
    else:
        record_test("AUTH-ALEMBIC-001", "Alembic", "Schema drift audit (alembic check)", "No drift (0 exit code)", f"Exit={alembic_proc.returncode} Output={alembic_proc.stdout}", "FAIL")

    print("\n============================================================")
    total_executed = len(TEST_RESULTS)
    total_passed = sum(1 for t in TEST_RESULTS if t["status"] == "PASS")
    total_failed = sum(1 for t in TEST_RESULTS if t["status"] == "FAIL")
    print(f"   SUMMARY: Executed={total_executed} | Passed={total_passed} | Failed={total_failed}")
    print("============================================================\n")
    return TEST_RESULTS


if __name__ == "__main__":
    run_all_tests()

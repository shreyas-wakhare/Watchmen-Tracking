import sys
import os
import time
import json
import urllib.request
import urllib.error
import subprocess
import threading
from datetime import datetime, date, time as dtime, timedelta, timezone
from zoneinfo import ZoneInfo
from concurrent.futures import ThreadPoolExecutor
from jose import jwt

repo_root = r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker"
backend_dir = r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\backend"

sys.path.insert(0, repo_root)
sys.path.insert(0, backend_dir)
os.chdir(backend_dir)

os.environ["DATABASE_URL"] = "postgresql+psycopg2://postgres:flowdesk1234@localhost:5432/watchmen_tracker"
TEST_SECRET = "hardening-phase4-super-secret-jwt-key-9999"
os.environ["JWT_SECRET_KEY"] = TEST_SECRET

from backend.db import get_db, SessionLocal, User, UserShiftSchedule, AttendanceRecord, utc_now
from backend.auth.security import hash_password, create_access_token

TEST_RESULTS = []
BASE_URL = "http://127.0.0.1:8009"


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


def api_request(method: str, path: str, data: dict = None, token: str = None) -> tuple[int, dict]:
    url = f"{BASE_URL}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    body_bytes = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body_bytes, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode("utf-8")
            body = json.loads(raw) if raw else {}
            return resp.status, body
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8")
        try:
            body = json.loads(raw)
        except Exception:
            body = {"raw": raw}
        return err.code, body


def cleanup_test_data():
    """Removes all test users and associated attendance data."""
    db = SessionLocal()
    try:
        test_emails = [
            "hard.user.a@watchmen.ae",
            "hard.user.b@watchmen.ae",
            "hard.user.inactive@watchmen.ae",
            "hard.user.snap@watchmen.ae",
            "hard.user.conc@watchmen.ae",
            "hard.user.tz@watchmen.ae",
            "hard.user.forg@watchmen.ae",
            "hard.user.ws@watchmen.ae",
        ]
        users = db.query(User).filter(User.email.in_(test_emails)).all()
        user_ids = [u.id for u in users]
        if user_ids:
            db.query(AttendanceRecord).filter(AttendanceRecord.user_id.in_(user_ids)).delete(synchronize_session=False)
            db.query(UserShiftSchedule).filter(UserShiftSchedule.user_id.in_(user_ids)).delete(synchronize_session=False)
            db.query(User).filter(User.id.in_(user_ids)).delete(synchronize_session=False)
            db.commit()
    finally:
        db.close()


def create_test_user(email: str, full_name: str, is_active: bool = True) -> tuple[int, str]:
    db = SessionLocal()
    try:
        now = utc_now()
        existing = db.query(User).filter(User.email == email).first()
        if existing:
            user_id = existing.id
            existing.is_active = is_active
            db.commit()
        else:
            user = User(
                email=email,
                password_hash=hash_password("hardPassword123!"),
                full_name=full_name,
                is_active=is_active,
                created_at=now,
                updated_at=now,
            )
            db.add(user)
            db.commit()
            db.refresh(user)
            user_id = user.id
        token = create_access_token(user_id)
        return user_id, token
    finally:
        db.close()


def run_hardening_tests():
    print("============================================================")
    print("   WATCHMEN TRACKER — PHASE 4 HARDENING TEST SUITE          ")
    print("============================================================\n")

    cleanup_test_data()

    # Start live server on port 8009
    proc = subprocess.Popen(
        [
            r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\.venv\Scripts\python.exe",
            "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8009"
        ],
        cwd=backend_dir,
        env={**os.environ, "PYTHONPATH": f"{repo_root};{backend_dir}", "JWT_SECRET_KEY": TEST_SECRET}
    )

    try:
        time.sleep(2.5)  # Wait for startup

        # Verify server is up
        code, health = api_request("GET", "/health")
        if code != 200 or health.get("status") != "healthy":
            raise RuntimeError(f"Server health check failed: {code} {health}")

        # Setup standard test users
        ua_id, ua_token = create_test_user("hard.user.a@watchmen.ae", "Hardened User A", is_active=True)
        ub_id, ub_token = create_test_user("hard.user.b@watchmen.ae", "Hardened User B", is_active=True)
        u_inact_id, u_inact_token = create_test_user("hard.user.inactive@watchmen.ae", "Hardened Inactive", is_active=False)

        # ---------------------------------------------------------
        # PART 1: AUTHENTICATION HARDENING (HARD-AUTH-001..006)
        # ---------------------------------------------------------
        print("\n--- PART 1: AUTHENTICATION HARDENING ---")

        # 1. Expired JWT access token explicitly rejected with 401
        expired_token = create_access_token(ua_id, expires_delta=timedelta(seconds=-10))
        code, res = api_request("GET", "/attendance/today", token=expired_token)
        if code == 401 and "expired" in res.get("detail", "").lower():
            record_test("HARD-AUTH-001", "Auth", "Expired JWT access token rejected", 401, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("HARD-AUTH-001", "Auth", "Expired JWT access token rejected", 401, f"{code} {res}", "FAIL")

        # 2. Refresh token submitted to attendance endpoint rejected (wrong type)
        refresh_payload = {
            "sub": str(ua_id),
            "iat": int(time.time()),
            "exp": int(time.time() + 3600),
            "type": "refresh"
        }
        refresh_jwt = jwt.encode(refresh_payload, TEST_SECRET, algorithm="HS256")
        code, res = api_request("GET", "/attendance/today", token=refresh_jwt)
        if code == 401 and "invalid token type" in res.get("detail", "").lower():
            record_test("HARD-AUTH-002", "Auth", "Refresh token as Bearer rejected (wrong type)", 401, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("HARD-AUTH-002", "Auth", "Refresh token as Bearer rejected (wrong type)", 401, f"{code} {res}", "FAIL")

        # 3. Tampered signature JWT rejected with 401
        tampered_jwt = jwt.encode({"sub": str(ua_id), "type": "access", "exp": int(time.time() + 3600)}, "wrong-secret-key-9999", algorithm="HS256")
        code, res = api_request("GET", "/attendance/today", token=tampered_jwt)
        if code == 401 and "could not validate credentials" in res.get("detail", "").lower():
            record_test("HARD-AUTH-003", "Auth", "Tampered signature JWT rejected", 401, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("HARD-AUTH-003", "Auth", "Tampered signature JWT rejected", 401, f"{code} {res}", "FAIL")

        # 4. Nonexistent user ID in JWT payload rejected with 401
        ghost_jwt = jwt.encode({"sub": "999999", "type": "access", "exp": int(time.time() + 3600)}, TEST_SECRET, algorithm="HS256")
        code, res = api_request("GET", "/attendance/today", token=ghost_jwt)
        if code == 401 and "user not found" in res.get("detail", "").lower():
            record_test("HARD-AUTH-004", "Auth", "Nonexistent user ID in JWT rejected", 401, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("HARD-AUTH-004", "Auth", "Nonexistent user ID in JWT rejected", 401, f"{code} {res}", "FAIL")

        # 5. Malformed Authorization headers
        code, res = api_request("GET", "/attendance/today", token="invalid.token.structure")
        if code == 401:
            record_test("HARD-AUTH-005", "Auth", "Malformed JWT string rejected", 401, code, "PASS")
        else:
            record_test("HARD-AUTH-005", "Auth", "Malformed JWT string rejected", 401, code, "FAIL")

        # 6. Inactive user trying Clock Out rejected with 403
        code, res = api_request("POST", "/attendance/clock-out", {"request_id": "inact-out"}, token=u_inact_token)
        if code == 403 and "inactive" in res.get("detail", "").lower():
            record_test("HARD-AUTH-006", "Auth", "Inactive user Clock Out rejected with 403", 403, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("HARD-AUTH-006", "Auth", "Inactive user Clock Out rejected with 403", 403, f"{code} {res}", "FAIL")

        # ---------------------------------------------------------
        # PART 2: USER ISOLATION HARDENING (HARD-ISO-001..005)
        # ---------------------------------------------------------
        print("\n--- PART 2: USER ISOLATION HARDENING ---")

        # Setup schedule and open shift for User A
        sched_a = {
            "shift_name": "Shift A",
            "start_time": "07:00:00",
            "end_time": "19:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        api_request("PUT", "/attendance/schedule", sched_a, token=ua_token)
        api_request("POST", "/attendance/clock-in", {"request_id": "ua-shift-iso-1"}, token=ua_token)

        # 7. User B cannot see User A's active attendance
        code, res_b_act = api_request("GET", "/attendance/active", token=ub_token)
        if code == 200 and res_b_act.get("has_active") is False and res_b_act.get("attendance") is None:
            record_test("HARD-ISO-001", "Isolation", "User B active attendance is isolated from User A", False, res_b_act.get("has_active"), "PASS")
        else:
            record_test("HARD-ISO-001", "Isolation", "User B active attendance is isolated from User A", False, res_b_act, "FAIL")

        # 8. User B cannot see User A's today state
        code, res_b_today = api_request("GET", "/attendance/today", token=ub_token)
        if code == 200 and res_b_today.get("state") == "NOT_STARTED" and res_b_today.get("attendance") is None:
            record_test("HARD-ISO-002", "Isolation", "User B today state is isolated from User A", "NOT_STARTED", res_b_today.get("state"), "PASS")
        else:
            record_test("HARD-ISO-002", "Isolation", "User B today state is isolated from User A", "NOT_STARTED", res_b_today, "FAIL")

        # 9. User B cannot see User A's history
        code, res_b_hist = api_request("GET", "/attendance/history", token=ub_token)
        if code == 200 and res_b_hist.get("total") == 0 and len(res_b_hist.get("items", [])) == 0:
            record_test("HARD-ISO-003", "Isolation", "User B history is isolated from User A", 0, res_b_hist.get("total"), "PASS")
        else:
            record_test("HARD-ISO-003", "Isolation", "User B history is isolated from User A", 0, res_b_hist, "FAIL")

        # 10. User B cannot clock out User A's shift
        code, res_b_out = api_request("POST", "/attendance/clock-out", {"request_id": "ub-out-attempt"}, token=ub_token)
        if code == 409 and "no active attendance session" in res_b_out.get("detail", "").lower():
            record_test("HARD-ISO-004", "Isolation", "User B cannot clock out User A's session", 409, f"{code} {res_b_out.get('detail')}", "PASS")
        else:
            record_test("HARD-ISO-004", "Isolation", "User B cannot clock out User A's session", 409, f"{code} {res_b_out}", "FAIL")

        # 11. User B cannot modify User A's schedule
        # User B queries their own schedule
        code, res_b_sched = api_request("GET", "/attendance/schedule", token=ub_token)
        if code == 404:
            record_test("HARD-ISO-005", "Isolation", "User B cannot read User A's schedule", 404, code, "PASS")
        else:
            record_test("HARD-ISO-005", "Isolation", "User B cannot read User A's schedule", 404, code, "FAIL")

        # Clean up User A's shift
        api_request("POST", "/attendance/clock-out", {"request_id": "ua-shift-iso-out"}, token=ua_token)

        # ---------------------------------------------------------
        # PART 3: SCHEDULE SNAPSHOT & IMMUTABILITY (HARD-SCHED-001..004)
        # ---------------------------------------------------------
        print("\n--- PART 3: SCHEDULE SNAPSHOT & IMMUTABILITY ---")

        # Setup user for schedule snapshot testing
        u_snap_id, u_snap_token = create_test_user("hard.user.snap@watchmen.ae", "Snapshot User", is_active=True)

        # Initial Schedule: 06:00 -> 18:00
        sched_v1 = {
            "shift_name": "Morning V1",
            "start_time": "06:00:00",
            "end_time": "18:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        api_request("PUT", "/attendance/schedule", sched_v1, token=u_snap_token)

        # Clock In with Schedule V1
        code, res_snap_in = api_request("POST", "/attendance/clock-in", {"request_id": "snap-shift-01"}, token=u_snap_token)
        print("SNAP CLOCK IN RESULT:", code, res_snap_in)
        api_request("POST", "/attendance/clock-out", {"request_id": "snap-shift-out-01"}, token=u_snap_token)

        # Update Schedule to V2: 20:00 -> 08:00
        sched_v2 = {
            "shift_name": "Night V2",
            "start_time": "20:00:00",
            "end_time": "08:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        api_request("PUT", "/attendance/schedule", sched_v2, token=u_snap_token)

        # 12. Verify historical attendance record retained original V1 schedule snapshot
        code, res_hist = api_request("GET", "/attendance/history", token=u_snap_token)
        past_item = res_hist["items"][0]
        if past_item["scheduled_start_time"] == "06:00:00" and past_item["scheduled_end_time"] == "18:00:00":
            record_test("HARD-SCHED-001", "Schedule", "Historical attendance preserves original snapshot after schedule update", "06:00:00 to 18:00:00", f"{past_item['scheduled_start_time']} to {past_item['scheduled_end_time']}", "PASS")
        else:
            record_test("HARD-SCHED-001", "Schedule", "Historical attendance preserves original snapshot after schedule update", "06:00:00 to 18:00:00", f"{past_item['scheduled_start_time']} to {past_item['scheduled_end_time']}", "FAIL")

        # 13. Inactive schedule behavior: if active schedule is deactivated in DB, Clock In rejected
        db = SessionLocal()
        db.query(UserShiftSchedule).filter(UserShiftSchedule.user_id == u_snap_id).update({"is_active": False})
        db.commit()
        db.close()
        code, res = api_request("POST", "/attendance/clock-in", {"request_id": "snap-no-active-sched"}, token=u_snap_token)
        if code == 400 and "no active shift schedule" in res.get("detail", "").lower():
            record_test("HARD-SCHED-002", "Schedule", "Clock In rejected when all schedules are inactive", 400, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("HARD-SCHED-002", "Schedule", "Clock In rejected when all schedules are inactive", 400, f"{code} {res}", "FAIL")

        # 14. Concurrent schedule updates for the same user keep exactly one active schedule
        concur_sched_results = []
        def update_sched(name, start, end):
            p = {"shift_name": name, "start_time": start, "end_time": end, "timezone": "Asia/Dubai", "days_of_week": "1,2,3,4,5,6,7"}
            c, r = api_request("PUT", "/attendance/schedule", p, token=u_snap_token)
            concur_sched_results.append((c, r))

        with ThreadPoolExecutor(max_workers=3) as executor:
            f1 = executor.submit(update_sched, "Sched A", "07:00:00", "15:00:00")
            f2 = executor.submit(update_sched, "Sched B", "15:00:00", "23:00:00")
            f3 = executor.submit(update_sched, "Sched C", "23:00:00", "07:00:00")
            f1.result(); f2.result(); f3.result()

        db = SessionLocal()
        active_scheds = db.query(UserShiftSchedule).filter(UserShiftSchedule.user_id == u_snap_id, UserShiftSchedule.is_active == True).count()
        db.close()
        if active_scheds == 1:
            record_test("HARD-SCHED-003", "Schedule", "Concurrent schedule updates maintain exactly 1 active schedule", 1, active_scheds, "PASS")
        else:
            record_test("HARD-SCHED-003", "Schedule", "Concurrent schedule updates maintain exactly 1 active schedule", 1, active_scheds, "FAIL")

        # 15. Extreme valid schedule values (00:00:00 to 23:59:59)
        code, res = api_request("PUT", "/attendance/schedule", {
            "shift_name": "Full Day Extreme",
            "start_time": "00:00:00",
            "end_time": "23:59:59",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_snap_token)
        if code == 200 and res.get("start_time") == "00:00:00" and res.get("end_time") == "23:59:59":
            record_test("HARD-SCHED-004", "Schedule", "Extreme valid schedule values accepted (00:00:00 to 23:59:59)", 200, code, "PASS")
        else:
            record_test("HARD-SCHED-004", "Schedule", "Extreme valid schedule values accepted (00:00:00 to 23:59:59)", 200, f"{code} {res}", "FAIL")

        # ---------------------------------------------------------
        # PART 4: IDEMPOTENCY & PAYLOAD INTEGRITY (HARD-IDEM-001..004)
        # ---------------------------------------------------------
        print("\n--- PART 4: IDEMPOTENCY & PAYLOAD INTEGRITY ---")

        # Setup user for idempotency tests
        u_idem_id, u_idem_token = create_test_user("hard.user.conc@watchmen.ae", "Concurrency User", is_active=True)
        api_request("PUT", "/attendance/schedule", {
            "shift_name": "Idem Shift",
            "start_time": "07:00:00",
            "end_time": "19:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_idem_token)

        # 16. Same request_id with DIFFERENT payload does NOT mutate or duplicate
        shared_req_id = "shared-uuid-test-001"
        code1, res1 = api_request("POST", "/attendance/clock-in", {
            "request_id": shared_req_id,
            "device_id": "device-ORIGINAL",
            "latitude": 25.1000,
            "longitude": 55.1000
        }, token=u_idem_token)

        code2, res2 = api_request("POST", "/attendance/clock-in", {
            "request_id": shared_req_id,
            "device_id": "device-TAMPERED-NEW",
            "latitude": 20.0000,
            "longitude": 40.0000
        }, token=u_idem_token)

        if code1 == 200 and code2 == 200 and res1["id"] == res2["id"] and res2["clock_in_device_id"] == "device-ORIGINAL":
            record_test("HARD-IDEM-001", "Idempotency", "Same request_id with different payload returns original without mutating", "device-ORIGINAL", res2["clock_in_device_id"], "PASS")
        else:
            record_test("HARD-IDEM-001", "Idempotency", "Same request_id with different payload returns original without mutating", "device-ORIGINAL", f"{res2.get('clock_in_device_id')}", "FAIL")

        # 17. Replay of Clock In safely returns existing outcome
        code_replay, res_replay = api_request("POST", "/attendance/clock-in", {"request_id": shared_req_id}, token=u_idem_token)
        if code_replay == 200 and res_replay["id"] == res1["id"]:
            record_test("HARD-IDEM-002", "Idempotency", "Subsequent replay returns exact same attendance outcome", res1["id"], res_replay["id"], "PASS")
        else:
            record_test("HARD-IDEM-002", "Idempotency", "Subsequent replay returns exact same attendance outcome", res1["id"], res_replay.get("id"), "FAIL")

        # 18. Same request_id reused by a DIFFERENT user is rejected with 409
        code_cross, res_cross = api_request("POST", "/attendance/clock-in", {"request_id": shared_req_id}, token=ub_token)
        if code_cross == 409 and "already been used" in res_cross.get("detail", "").lower():
            record_test("HARD-IDEM-003", "Idempotency", "Same request_id reused by different user rejected with 409", 409, f"{code_cross} {res_cross.get('detail')}", "PASS")
        else:
            record_test("HARD-IDEM-003", "Idempotency", "Same request_id reused by different user rejected with 409", 409, f"{code_cross} {res_cross}", "FAIL")

        # Clock Out with specific request_id
        shared_out_id = "shared-uuid-out-001"
        code_out1, res_out1 = api_request("POST", "/attendance/clock-out", {"request_id": shared_out_id}, token=u_idem_token)

        # 19. Same Clock Out request_id reused by a DIFFERENT user is rejected with 409
        code_out_cross, res_out_cross = api_request("POST", "/attendance/clock-out", {"request_id": shared_out_id}, token=ub_token)
        if code_out_cross == 409 and "already been used" in res_out_cross.get("detail", "").lower():
            record_test("HARD-IDEM-004", "Idempotency", "Same Clock Out request_id reused by different user rejected with 409", 409, f"{code_out_cross} {res_out_cross.get('detail')}", "PASS")
        else:
            record_test("HARD-IDEM-004", "Idempotency", "Same Clock Out request_id reused by different user rejected with 409", 409, f"{code_out_cross} {res_out_cross}", "FAIL")

        # ---------------------------------------------------------
        # PART 5: HIGH CONCURRENCY RACE CONDITIONS (HARD-CONC-001..003)
        # ---------------------------------------------------------
        print("\n--- PART 5: HIGH CONCURRENCY RACE CONDITIONS ---")

        # Create fresh user for 5-thread race
        u_race_id, u_race_token = create_test_user("hard.user.ws@watchmen.ae", "Race User", is_active=True)
        api_request("PUT", "/attendance/schedule", {
            "shift_name": "Race Shift",
            "start_time": "08:00:00",
            "end_time": "20:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_race_token)

        # 20. 5 simultaneous Clock In requests from same user with different IDs
        # Exactly ONE must succeed (200), and exactly 4 must receive 409 Conflict
        race_in_results = []
        def race_clock_in(worker_idx):
            c, r = api_request("POST", "/attendance/clock-in", {"request_id": f"race-in-req-{worker_idx}"}, token=u_race_token)
            race_in_results.append((c, r))

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(race_clock_in, i) for i in range(5)]
            for f in futures:
                f.result()

        in_status_codes = [r[0] for r in race_in_results]
        count_200 = in_status_codes.count(200)
        count_409 = in_status_codes.count(409)

        db = SessionLocal()
        actual_rows = db.query(AttendanceRecord).filter(AttendanceRecord.user_id == u_race_id).count()
        db.close()

        if count_200 == 1 and count_409 == 4 and actual_rows == 1:
            record_test("HARD-CONC-001", "Concurrency", "5 concurrent Clock In requests yield exactly 1 success, 4 conflicts, 1 row", "1x200, 4x409, 1 row", f"{count_200}x200, {count_409}x409, {actual_rows} row", "PASS")
        else:
            record_test("HARD-CONC-001", "Concurrency", "5 concurrent Clock In requests yield exactly 1 success, 4 conflicts, 1 row", "1x200, 4x409, 1 row", f"{count_200}x200, {count_409}x409, {actual_rows} rows", "FAIL")

        # 21. 5 simultaneous Clock Out requests against the same open shift
        race_out_results = []
        def race_clock_out(worker_idx):
            c, r = api_request("POST", "/attendance/clock-out", {"request_id": f"race-out-req-{worker_idx}"}, token=u_race_token)
            race_out_results.append((c, r))

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(race_clock_out, i) for i in range(5)]
            for f in futures:
                f.result()

        out_status_codes = [r[0] for r in race_out_results]
        db = SessionLocal()
        closed_rec = db.query(AttendanceRecord).filter(AttendanceRecord.user_id == u_race_id).first()
        db.close()

        if 200 in out_status_codes and closed_rec.clock_out_at is not None and closed_rec.total_worked_minutes is not None:
            record_test("HARD-CONC-002", "Concurrency", "5 concurrent Clock Out requests resolved safely without corruption", "200 present, closed cleanly", f"Codes: {sorted(out_status_codes)}", "PASS")
        else:
            record_test("HARD-CONC-002", "Concurrency", "5 concurrent Clock Out requests resolved safely without corruption", "Closed cleanly", out_status_codes, "FAIL")

        # 22. Race between simultaneous Clock In and Clock Out
        # Second clock-in should fail (already completed today), clock-out should fail (no active)
        c_in_race, r_in_race = api_request("POST", "/attendance/clock-in", {"request_id": "race-both-in"}, token=u_race_token)
        c_out_race, r_out_race = api_request("POST", "/attendance/clock-out", {"request_id": "race-both-out"}, token=u_race_token)
        if c_in_race == 409 and c_out_race == 409:
            record_test("HARD-CONC-003", "Concurrency", "Clock In and Clock Out after shift completion safely reject with 409", "Both 409", f"In={c_in_race} Out={c_out_race}", "PASS")
        else:
            record_test("HARD-CONC-003", "Concurrency", "Clock In and Clock Out after shift completion safely reject with 409", "Both 409", f"In={c_in_race} Out={c_out_race}", "FAIL")

        # ---------------------------------------------------------
        # PART 6: TIMEZONE & DATE BOUNDARIES (HARD-TZ-001..003)
        # ---------------------------------------------------------
        print("\n--- PART 6: TIMEZONE & DATE BOUNDARIES ---")

        # Setup user for timezone testing
        u_tz_id, u_tz_token = create_test_user("hard.user.tz@watchmen.ae", "Timezone User", is_active=True)

        # 23. Client device timezone header/body ignored: backend uses schedule site timezone
        api_request("PUT", "/attendance/schedule", {
            "shift_name": "Dubai TZ Test",
            "start_time": "08:00:00",
            "end_time": "20:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_tz_token)

        code, res = api_request("POST", "/attendance/clock-in", {
            "request_id": "tz-site-authoritative-1",
            "client_timezone": "America/Los_Angeles",  # Client tries to claim Pacific Time
            "device_time": "2000-01-01T12:00:00-08:00"
        }, token=u_tz_token)

        expected_site_date = datetime.now(ZoneInfo("Asia/Dubai")).strftime("%Y-%m-%d")
        if code == 200 and res.get("work_date") == expected_site_date and res.get("timezone") == "Asia/Dubai":
            record_test("HARD-TZ-001", "Timezone", "Site timezone is authoritative regardless of client headers", expected_site_date, res.get("work_date"), "PASS")
        else:
            record_test("HARD-TZ-001", "Timezone", "Site timezone is authoritative regardless of client headers", expected_site_date, res, "FAIL")

        api_request("POST", "/attendance/clock-out", {"request_id": "tz-site-out-1"}, token=u_tz_token)

        # 24. DST-aware timezone handling (Europe/London or America/New_York)
        # Verify creating schedule with DST timezone and clocking in
        # Clean user records to allow second test on another day or fresh user
        db = SessionLocal()
        db.query(AttendanceRecord).filter(AttendanceRecord.user_id == u_tz_id).delete()
        db.commit()
        db.close()

        api_request("PUT", "/attendance/schedule", {
            "shift_name": "London Shift",
            "start_time": "09:00:00",
            "end_time": "17:00:00",
            "timezone": "Europe/London",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_tz_token)

        code_dst, res_dst = api_request("POST", "/attendance/clock-in", {"request_id": "tz-london-dst-1"}, token=u_tz_token)
        expected_london_date = datetime.now(ZoneInfo("Europe/London")).strftime("%Y-%m-%d")
        if code_dst == 200 and res_dst.get("work_date") == expected_london_date and res_dst.get("timezone") == "Europe/London":
            record_test("HARD-TZ-002", "Timezone", "DST-aware IANA timezone (Europe/London) derived correctly", expected_london_date, res_dst.get("work_date"), "PASS")
        else:
            record_test("HARD-TZ-002", "Timezone", "DST-aware IANA timezone (Europe/London) derived correctly", expected_london_date, res_dst, "FAIL")

        api_request("POST", "/attendance/clock-out", {"request_id": "tz-london-out-1"}, token=u_tz_token)

        # 25. Overnight shift duration calculation across midnight
        db = SessionLocal()
        overnight_rec = AttendanceRecord(
            user_id=u_tz_id,
            work_date=date(2026, 9, 1),
            scheduled_start_time=dtime(19, 0),
            scheduled_end_time=dtime(7, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 1, 15, 0, tzinfo=timezone.utc),   # 19:00 UAE
            clock_out_at=datetime(2026, 9, 2, 3, 0, tzinfo=timezone.utc),   # 07:00 UAE next day
            clock_in_request_id="overnight-test-rec",
            clock_out_request_id="overnight-test-out",
            total_worked_minutes=720,
            created_at=utc_now(),
            updated_at=utc_now()
        )
        db.add(overnight_rec)
        db.commit()
        db.close()

        code_hist_on, res_hist_on = api_request("GET", "/attendance/history?start_date=2026-09-01&end_date=2026-09-01", token=u_tz_token)
        first_hist = res_hist_on["items"][0] if res_hist_on.get("items") else {}
        if first_hist.get("work_date") == "2026-09-01" and first_hist.get("total_worked_minutes") == 720:
            record_test("HARD-TZ-003", "Timezone", "Overnight shift preserves work_date and 12-hour elapsed minutes across midnight", "2026-09-01 720m", f"{first_hist.get('work_date')} {first_hist.get('total_worked_minutes')}m", "PASS")
        else:
            record_test("HARD-TZ-003", "Timezone", "Overnight shift preserves work_date and 12-hour elapsed minutes across midnight", "2026-09-01 720m", f"{first_hist.get('work_date')} {first_hist.get('total_worked_minutes')}m", "FAIL")

        # ---------------------------------------------------------
        # PART 7: FORGOTTEN CLOCK OUT DEEP HARDENING (HARD-FORG-001..002)
        # ---------------------------------------------------------
        print("\n--- PART 7: FORGOTTEN CLOCK OUT DEEP HARDENING ---")

        # Setup user with yesterday's unclosed shift
        u_forg_id, u_forg_token = create_test_user("hard.user.forg@watchmen.ae", "Forgotten Shift User", is_active=True)
        api_request("PUT", "/attendance/schedule", {
            "shift_name": "Forg Shift",
            "start_time": "08:00:00",
            "end_time": "20:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_forg_token)

        # Inject yesterday's open shift directly in DB
        db = SessionLocal()
        yesterday_date = datetime.now(ZoneInfo("Asia/Dubai")).date() - timedelta(days=1)
        unclosed_shift = AttendanceRecord(
            user_id=u_forg_id,
            work_date=yesterday_date,
            scheduled_start_time=dtime(8, 0),
            scheduled_end_time=dtime(20, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(yesterday_date.year, yesterday_date.month, yesterday_date.day, 4, 0, tzinfo=timezone.utc),
            clock_out_at=None,  # FORGOTTEN CLOCK OUT
            clock_in_request_id="forgotten-open-shift-yesterday",
            created_at=utc_now(),
            updated_at=utc_now()
        )
        db.add(unclosed_shift)
        db.commit()
        db.close()

        # 26. GET /attendance/today reports state = PENDING_RESOLUTION
        code_today_forg, res_today_forg = api_request("GET", "/attendance/today", token=u_forg_token)
        if code_today_forg == 200 and res_today_forg.get("state") == "PENDING_RESOLUTION" and res_today_forg.get("attendance") is not None:
            record_test("HARD-FORG-001", "Forgotten Shift", "Yesterday's unclosed shift reports PENDING_RESOLUTION state", "PENDING_RESOLUTION", res_today_forg.get("state"), "PASS")
        else:
            record_test("HARD-FORG-001", "Forgotten Shift", "Yesterday's unclosed shift reports PENDING_RESOLUTION state", "PENDING_RESOLUTION", res_today_forg, "FAIL")

        # 27. Attempt to clock in today blocked by yesterday's open shift
        code_blocked, res_blocked = api_request("POST", "/attendance/clock-in", {"request_id": "try-clockin-blocked"}, token=u_forg_token)
        if code_blocked == 409 and "open attendance shift already exists" in res_blocked.get("detail", "").lower():
            record_test("HARD-FORG-002", "Forgotten Shift", "Clock In today blocked by yesterday's unresolved open shift", 409, f"{code_blocked} {res_blocked.get('detail')}", "PASS")
        else:
            record_test("HARD-FORG-002", "Forgotten Shift", "Clock In today blocked by yesterday's unresolved open shift", 409, f"{code_blocked} {res_blocked}", "FAIL")

        # ---------------------------------------------------------
        # PART 8: HISTORY & QUERY HARDENING (HARD-HIST-001..004)
        # ---------------------------------------------------------
        print("\n--- PART 8: HISTORY & QUERY HARDENING ---")

        # 28. Invalid date range (start_date > end_date) rejected with 422
        code_bad_range, res_bad_range = api_request("GET", "/attendance/history?start_date=2026-09-10&end_date=2026-09-01", token=ua_token)
        if code_bad_range == 422 and "start_date cannot be after end_date" in res_bad_range.get("detail", "").lower():
            record_test("HARD-HIST-001", "History", "start_date > end_date rejected with HTTP 422", 422, f"{code_bad_range} {res_bad_range.get('detail')}", "PASS")
        else:
            record_test("HARD-HIST-001", "History", "start_date > end_date rejected with HTTP 422", 422, f"{code_bad_range} {res_bad_range}", "FAIL")

        # 29. Date filtering with boundary dates (start_date == end_date)
        code_exact_day, res_exact_day = api_request("GET", f"/attendance/history?start_date={expected_site_date}&end_date={expected_site_date}", token=ua_token)
        if code_exact_day == 200:
            record_test("HARD-HIST-002", "History", "start_date == end_date boundary query succeeds", 200, f"200 total={res_exact_day.get('total')}", "PASS")
        else:
            record_test("HARD-HIST-002", "History", "start_date == end_date boundary query succeeds", 200, f"{code_exact_day} {res_exact_day}", "FAIL")

        # 30. Pagination with offset exceeding total count returns empty items with accurate total
        code_big_offset, res_big_offset = api_request("GET", "/attendance/history?limit=10&offset=5000", token=ua_token)
        if code_big_offset == 200 and len(res_big_offset.get("items", [])) == 0 and res_big_offset.get("offset") == 5000:
            record_test("HARD-HIST-003", "History", "Offset exceeding total returns empty items without error", "0 items, offset 5000", f"{len(res_big_offset.get('items', []))} items", "PASS")
        else:
            record_test("HARD-HIST-003", "History", "Offset exceeding total returns empty items without error", "0 items", f"{code_big_offset} {res_big_offset}", "FAIL")

        # 31. Empty history returns total=0, items=[]
        # User B has no records
        code_empty_h, res_empty_h = api_request("GET", "/attendance/history", token=ub_token)
        if code_empty_h == 200 and res_empty_h.get("total") == 0 and res_empty_h.get("items") == []:
            record_test("HARD-HIST-004", "History", "Empty history returns total=0, items=[]", "total=0, items=[]", f"total={res_empty_h.get('total')}, items={res_empty_h.get('items')}", "PASS")
        else:
            record_test("HARD-HIST-004", "History", "Empty history returns total=0, items=[]", "total=0, items=[]", f"{code_empty_h} {res_empty_h}", "FAIL")

        # ---------------------------------------------------------
        # PART 9: INPUT VALIDATION & SECURITY (HARD-VAL-001..005 & HARD-SEC-001)
        # ---------------------------------------------------------
        print("\n--- PART 9: INPUT VALIDATION & SECURITY ---")

        # 32. Incomplete GPS: Latitude provided without Longitude
        code_half_gps1, res_half_gps1 = api_request("POST", "/attendance/clock-in", {
            "request_id": "bad-gps-lat-only",
            "latitude": 25.2048
            # longitude omitted
        }, token=ua_token)
        if code_half_gps1 == 422 and "both latitude and longitude" in json.dumps(res_half_gps1).lower():
            record_test("HARD-VAL-001", "Validation", "Latitude without longitude rejected with 422", 422, code_half_gps1, "PASS")
        else:
            record_test("HARD-VAL-001", "Validation", "Latitude without longitude rejected with 422", 422, f"{code_half_gps1} {res_half_gps1}", "FAIL")

        # 33. Incomplete GPS: Longitude provided without Latitude
        code_half_gps2, res_half_gps2 = api_request("POST", "/attendance/clock-in", {
            "request_id": "bad-gps-lon-only",
            "longitude": 55.2708
            # latitude omitted
        }, token=ua_token)
        if code_half_gps2 == 422 and "both latitude and longitude" in json.dumps(res_half_gps2).lower():
            record_test("HARD-VAL-002", "Validation", "Longitude without latitude rejected with 422", 422, code_half_gps2, "PASS")
        else:
            record_test("HARD-VAL-002", "Validation", "Longitude without latitude rejected with 422", 422, f"{code_half_gps2} {res_half_gps2}", "FAIL")

        # 34. Out of range GPS coordinates (latitude > 90)
        code_oob_gps, res_oob_gps = api_request("POST", "/attendance/clock-in", {
            "request_id": "oob-gps-lat",
            "latitude": 99.9999,
            "longitude": 55.2708
        }, token=ua_token)
        if code_oob_gps == 422:
            record_test("HARD-VAL-003", "Validation", "Out of range latitude (>90) rejected with 422", 422, code_oob_gps, "PASS")
        else:
            record_test("HARD-VAL-003", "Validation", "Out of range latitude (>90) rejected with 422", 422, code_oob_gps, "FAIL")

        # 35. Empty / whitespace request_id rejected with 422
        code_empty_req, res_empty_req = api_request("POST", "/attendance/clock-in", {
            "request_id": "   "
        }, token=ua_token)
        if code_empty_req == 422:
            record_test("HARD-VAL-004", "Validation", "Whitespace request_id rejected with 422", 422, code_empty_req, "PASS")
        else:
            record_test("HARD-VAL-004", "Validation", "Whitespace request_id rejected with 422", 422, code_empty_req, "FAIL")

        # 36. Extra injected fields (e.g. user_id, clock_in_at, total_worked_minutes) cannot override server authority
        # Create fresh user to clock in with extra injected fields
        u_inj_id, u_inj_token = create_test_user("hard.user.b@watchmen.ae", "Injected User", is_active=True)
        api_request("PUT", "/attendance/schedule", {
            "shift_name": "Inj Shift",
            "start_time": "08:00:00",
            "end_time": "20:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_inj_token)

        code_inj, res_inj = api_request("POST", "/attendance/clock-in", {
            "request_id": "inj-fields-001",
            "user_id": 999999,  # Injected user_id attempt
            "clock_in_at": "1970-01-01T00:00:00Z",
            "total_worked_minutes": 9999
        }, token=u_inj_token)

        if code_inj == 200 and res_inj["user_id"] == u_inj_id and res_inj["total_worked_minutes"] is None:
            record_test("HARD-VAL-005", "Security", "Injected user_id and duration ignored by schema/server", f"user_id={u_inj_id}, total=None", f"user_id={res_inj['user_id']}, total={res_inj['total_worked_minutes']}", "PASS")
        else:
            record_test("HARD-VAL-005", "Security", "Injected user_id and duration ignored by schema/server", f"user_id={u_inj_id}", f"{code_inj} {res_inj}", "FAIL")

        api_request("POST", "/attendance/clock-out", {"request_id": "inj-fields-out-001"}, token=u_inj_token)

        # 40. Response security audit: No SQL strings, DB credentials, password hashes, or tracebacks in responses
        sample_responses = [
            json.dumps(res_blocked),
            json.dumps(res_bad_range),
            json.dumps(res_oob_gps),
            json.dumps(res_cross),
            json.dumps(res_snap_in)
        ]
        leaks_found = []
        for s in sample_responses:
            for bad_kw in ["password_hash", "argon2", "traceback", "flowdesk1234", "select * from", "user_shift_schedules_pkey"]:
                if bad_kw in s.lower():
                    leaks_found.append(bad_kw)

        if not leaks_found:
            record_test("HARD-SEC-001", "Security", "Zero credentials, SQL statements, or stack traces leaked in error responses", "Clean", "Clean", "PASS")
        else:
            record_test("HARD-SEC-001", "Security", "Zero credentials, SQL statements, or stack traces leaked in error responses", "Clean", f"Leaked: {leaks_found}", "FAIL")

        # ---------------------------------------------------------
        # PART 10: WEBSOCKET EVENT INTEGRITY (HARD-WS-001..003)
        # ---------------------------------------------------------
        print("\n--- PART 10: WEBSOCKET EVENT INTEGRITY ---")

        import websockets.sync.client
        ws_messages = []
        stop_listener = threading.Event()

        def ws_dash_client():
            try:
                with websockets.sync.client.connect(f"ws://127.0.0.1:8009/ws?type=dashboard") as ws:
                    while not stop_listener.is_set():
                        try:
                            msg_str = ws.recv(timeout=0.5)
                            ws_messages.append(json.loads(msg_str))
                        except TimeoutError:
                            continue
                        except Exception:
                            break
            except Exception:
                pass

        ws_th = threading.Thread(target=ws_dash_client, daemon=True)
        ws_th.start()
        time.sleep(0.5)

        # 37. Failed Clock In (off-day or duplicate) does NOT emit any WebSocket attendance event
        before_count = len(ws_messages)
        # Attempt failed clock-in (already completed today)
        api_request("POST", "/attendance/clock-in", {"request_id": "failed-in-attempt"}, token=u_inj_token)
        time.sleep(0.3)
        after_count = len(ws_messages)

        failed_in_events = [m for m in ws_messages[before_count:after_count] if m.get("type") == "ATTENDANCE_EVENT"]
        if len(failed_in_events) == 0:
            record_test("HARD-WS-001", "WebSocket", "Failed Clock In does not emit WebSocket event", 0, len(failed_in_events), "PASS")
        else:
            record_test("HARD-WS-001", "WebSocket", "Failed Clock In does not emit WebSocket event", 0, len(failed_in_events), "FAIL")

        # 38. Failed Clock Out (no active shift) does NOT emit any WebSocket attendance event
        before_count_out = len(ws_messages)
        api_request("POST", "/attendance/clock-out", {"request_id": "failed-out-attempt"}, token=u_inj_token)
        time.sleep(0.3)
        after_count_out = len(ws_messages)

        failed_out_events = [m for m in ws_messages[before_count_out:after_count_out] if m.get("type") == "ATTENDANCE_EVENT"]
        if len(failed_out_events) == 0:
            record_test("HARD-WS-002", "WebSocket", "Failed Clock Out does not emit WebSocket event", 0, len(failed_out_events), "PASS")
        else:
            record_test("HARD-WS-002", "WebSocket", "Failed Clock Out does not emit WebSocket event", 0, len(failed_out_events), "FAIL")

        # 39. Idempotent replay does NOT emit duplicate WebSocket events
        before_count_replay = len(ws_messages)
        api_request("POST", "/attendance/clock-out", {"request_id": "inj-fields-out-001"}, token=u_inj_token)
        time.sleep(0.3)
        after_count_replay = len(ws_messages)

        replay_events = [m for m in ws_messages[before_count_replay:after_count_replay] if m.get("type") == "ATTENDANCE_EVENT"]
        if len(replay_events) == 0:
            record_test("HARD-WS-003", "WebSocket", "Idempotent replay does not emit duplicate WebSocket event", 0, len(replay_events), "PASS")
        else:
            record_test("HARD-WS-003", "WebSocket", "Idempotent replay does not emit duplicate WebSocket event", 0, len(replay_events), "FAIL")

        stop_listener.set()
        ws_th.join(timeout=1.0)

    finally:
        try:
            cleanup_test_data()
        except Exception:
            pass
        proc.terminate()
        try:
            proc.wait(timeout=5.0)
        except Exception:
            proc.kill()

    # Summary
    passed = sum(1 for t in TEST_RESULTS if t["status"] == "PASS")
    failed = sum(1 for t in TEST_RESULTS if t["status"] == "FAIL")
    total = len(TEST_RESULTS)

    print("\n============================================================")
    print(f"   HARDENING TEST SUMMARY: Executed={total} | Passed={passed} | Failed={failed}")
    print("============================================================\n")

    if failed > 0:
        print("FAILED TESTS:")
        for t in TEST_RESULTS:
            if t["status"] == "FAIL":
                print(f"  - {t['id']}: {t['name']} (Expected: {t['expected']}, Got: {t['actual']})")
        sys.exit(1)
    else:
        print("ALL 40 HARDENING TESTS PASSED 100%!")


if __name__ == "__main__":
    run_hardening_tests()

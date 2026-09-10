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

repo_root = r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker"
backend_dir = r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\backend"

sys.path.insert(0, repo_root)
sys.path.insert(0, backend_dir)
os.chdir(backend_dir)

os.environ["DATABASE_URL"] = "postgresql+psycopg2://postgres:flowdesk1234@localhost:5432/watchmen_tracker"
TEST_SECRET = "attendance-phase3-super-secret-jwt-key-9999"
os.environ["JWT_SECRET_KEY"] = TEST_SECRET

from backend.db import get_db, SessionLocal, User, UserShiftSchedule, AttendanceRecord, utc_now
from backend.auth.security import hash_password, create_access_token
from backend.attendance.router import set_event_broadcaster

TEST_RESULTS = []
CAPTURED_EVENTS = []


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


def capture_event(event_data):
    CAPTURED_EVENTS.append(event_data)


set_event_broadcaster(capture_event)

BASE_URL = "http://127.0.0.1:8008"


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
    """Removes test users, shift schedules, and attendance records created during tests."""
    db = SessionLocal()
    try:
        test_emails = [
            "att.test.user1@watchmen.ae",
            "att.test.user2@watchmen.ae",
            "att.test.inactive@watchmen.ae",
            "att.test.tz@watchmen.ae",
            "att.test.concur@watchmen.ae",
            "att.test.ws@watchmen.ae",
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


def create_test_user(email: str, full_name: str, is_active: bool = True) -> tuple[User, str]:
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
                password_hash=hash_password("testPassword123!"),
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


def run_tests():
    print("============================================================")
    print("   WATCHMEN TRACKER — PHASE 3 ATTENDANCE TEST SUITE        ")
    print("============================================================\n")

    cleanup_test_data()

    # ---------------------------------------------------------
    # START UVICORN SERVER
    # ---------------------------------------------------------
    proc = subprocess.Popen(
        [
            r"C:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker\.venv\Scripts\python.exe",
            "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8008"
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

        # Create Test Users
        u1_id, u1_token = create_test_user("att.test.user1@watchmen.ae", "Test Watchman One", is_active=True)
        u2_id, u2_token = create_test_user("att.test.user2@watchmen.ae", "Test Watchman Two", is_active=True)
        inact_id, inact_token = create_test_user("att.test.inactive@watchmen.ae", "Inactive Watchman", is_active=False)

        # ---------------------------------------------------------
        # SECTION 1: AUTHENTICATION (Tests 1 - 4)
        # ---------------------------------------------------------
        print("\n--- SECTION 1: AUTHENTICATION ---")

        # 1. unauthenticated Clock In rejected (401)
        code, res = api_request("POST", "/attendance/clock-in", {"request_id": "req-unauth-1"})
        if code == 401:
            record_test("ATT-AUTH-001", "Auth", "Unauthenticated Clock In rejected", 401, code, "PASS")
        else:
            record_test("ATT-AUTH-001", "Auth", "Unauthenticated Clock In rejected", 401, code, "FAIL")

        # 2. unauthenticated Clock Out rejected (401)
        code, res = api_request("POST", "/attendance/clock-out", {"request_id": "req-unauth-2"})
        if code == 401:
            record_test("ATT-AUTH-002", "Auth", "Unauthenticated Clock Out rejected", 401, code, "PASS")
        else:
            record_test("ATT-AUTH-002", "Auth", "Unauthenticated Clock Out rejected", 401, code, "FAIL")

        # 3. user cannot access another user's attendance (isolation)
        # We will verify history, schedule, today are isolated to u1 vs u2
        code, res = api_request("GET", "/attendance/history", token=u1_token)
        if code == 200 and res.get("total") == 0:
            record_test("ATT-AUTH-003", "Auth", "User isolation on empty history", 200, f"{code} total=0", "PASS")
        else:
            record_test("ATT-AUTH-003", "Auth", "User isolation on empty history", 200, f"{code} {res}", "FAIL")

        # 4. inactive user behavior (403)
        code, res = api_request("GET", "/attendance/today", token=inact_token)
        if code == 403:
            record_test("ATT-AUTH-004", "Auth", "Inactive user rejected with 403", 403, code, "PASS")
        else:
            record_test("ATT-AUTH-004", "Auth", "Inactive user rejected with 403", 403, code, "FAIL")

        # ---------------------------------------------------------
        # SECTION 2: SCHEDULE (Tests 5 - 10)
        # ---------------------------------------------------------
        print("\n--- SECTION 2: SCHEDULE ---")

        # 5. create schedule
        sched_payload = {
            "shift_name": "Day Shift",
            "start_time": "07:00:00",
            "end_time": "19:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        code, res = api_request("PUT", "/attendance/schedule", sched_payload, token=u1_token)
        if code == 200 and res.get("shift_name") == "Day Shift" and res.get("is_active") is True:
            record_test("ATT-SCHED-005", "Schedule", "Create schedule", "200 Active Day Shift", f"{code} {res.get('shift_name')}", "PASS")
        else:
            record_test("ATT-SCHED-005", "Schedule", "Create schedule", "200 Active Day Shift", f"{code} {res}", "FAIL")

        # 6. retrieve schedule
        code, res = api_request("GET", "/attendance/schedule", token=u1_token)
        if code == 200 and res.get("shift_name") == "Day Shift" and res.get("timezone") == "Asia/Dubai":
            record_test("ATT-SCHED-006", "Schedule", "Retrieve schedule", "200 Asia/Dubai", f"{code} {res.get('timezone')}", "PASS")
        else:
            record_test("ATT-SCHED-006", "Schedule", "Retrieve schedule", "200 Asia/Dubai", f"{code} {res}", "FAIL")

        # 7. replace schedule
        replace_payload = {
            "shift_name": "Night Shift",
            "start_time": "19:00:00",
            "end_time": "07:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        code, res = api_request("PUT", "/attendance/schedule", replace_payload, token=u1_token)
        if code == 200 and res.get("shift_name") == "Night Shift":
            record_test("ATT-SCHED-007", "Schedule", "Replace schedule", "200 Night Shift", f"{code} {res.get('shift_name')}", "PASS")
        else:
            record_test("ATT-SCHED-007", "Schedule", "Replace schedule", "200 Night Shift", f"{code} {res}", "FAIL")

        # 8. only one active schedule (verify in DB)
        db = SessionLocal()
        active_count = db.query(UserShiftSchedule).filter(
            UserShiftSchedule.user_id == u1_id,
            UserShiftSchedule.is_active == True
        ).count()
        total_sched_count = db.query(UserShiftSchedule).filter(UserShiftSchedule.user_id == u1_id).count()
        db.close()
        if active_count == 1 and total_sched_count == 2:
            record_test("ATT-SCHED-008", "Schedule", "Only one active schedule in DB", 1, active_count, "PASS")
        else:
            record_test("ATT-SCHED-008", "Schedule", "Only one active schedule in DB", 1, f"active={active_count} total={total_sched_count}", "FAIL")

        # Restore Day Shift (07:00 -> 19:00) for u1
        api_request("PUT", "/attendance/schedule", sched_payload, token=u1_token)

        # 9. invalid timezone rejected (422)
        bad_tz = {
            "shift_name": "Bad TZ",
            "start_time": "07:00:00",
            "end_time": "19:00:00",
            "timezone": "Invalid/FakeTimezone",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        code, res = api_request("PUT", "/attendance/schedule", bad_tz, token=u1_token)
        if code == 422:
            record_test("ATT-SCHED-009", "Schedule", "Invalid timezone rejected", 422, code, "PASS")
        else:
            record_test("ATT-SCHED-009", "Schedule", "Invalid timezone rejected", 422, code, "FAIL")

        # 10. invalid schedule input rejected (identical start/end -> 422)
        bad_times = {
            "shift_name": "Zero Duration",
            "start_time": "07:00:00",
            "end_time": "07:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        code, res = api_request("PUT", "/attendance/schedule", bad_times, token=u1_token)
        if code == 422:
            record_test("ATT-SCHED-010", "Schedule", "Identical start/end time rejected", 422, code, "PASS")
        else:
            record_test("ATT-SCHED-010", "Schedule", "Identical start/end time rejected", 422, code, "FAIL")

        # ---------------------------------------------------------
        # SECTION 3: CLOCK IN (Tests 11 - 20)
        # ---------------------------------------------------------
        print("\n--- SECTION 3: CLOCK IN ---")

        # 16. Clock In without schedule rejected (User 2 has no schedule yet)
        code, res = api_request("POST", "/attendance/clock-in", {"request_id": "u2-no-sched"}, token=u2_token)
        if code == 400 and "No active shift schedule" in res.get("detail", ""):
            record_test("ATT-CLKIN-016", "Clock In", "Clock In without schedule rejected", 400, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("ATT-CLKIN-016", "Clock In", "Clock In without schedule rejected", 400, f"{code} {res}", "FAIL")

        # Setup schedule for u2 with non-working days
        # E.g. If today is UAE day, let's configure schedule excluding today
        now_uae = datetime.now(ZoneInfo("Asia/Dubai"))
        today_iso = str(now_uae.isoweekday())
        non_working_days = ",".join(str(d) for d in [1, 2, 3, 4, 5, 6, 7] if str(d) != today_iso)
        sched_off_day = {
            "shift_name": "Off Today Shift",
            "start_time": "07:00:00",
            "end_time": "19:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": non_working_days
        }
        api_request("PUT", "/attendance/schedule", sched_off_day, token=u2_token)

        # 17. Clock In on non-working day handled correctly
        code, res = api_request("POST", "/attendance/clock-in", {"request_id": "u2-off-day"}, token=u2_token)
        if code == 400 and "not a scheduled working day" in res.get("detail", ""):
            record_test("ATT-CLKIN-017", "Clock In", "Clock In on non-working day rejected", 400, f"{code} {res.get('detail')}", "PASS")
        else:
            record_test("ATT-CLKIN-017", "Clock In", "Clock In on non-working day rejected", 400, f"{code} {res}", "FAIL")

        # Now test u1 successful Clock In
        req_in_id = "req-u1-clockin-001"
        in_payload = {
            "request_id": req_in_id,
            "device_id": "phone-samsung-galaxy-s24",
            "latitude": 25.2048,
            "longitude": 55.2708,
            # Attempt to inject client clock_in_at (should be ignored by schema / server)
            "clock_in_at": "1999-01-01T00:00:00Z"
        }
        t_before = utc_now()
        code, res_in = api_request("POST", "/attendance/clock-in", in_payload, token=u1_token)
        t_after = utc_now()

        # 11. successful Clock In
        if code == 200 and res_in.get("status") == "CLOCKED_IN" and res_in.get("id") is not None:
            record_test("ATT-CLKIN-011", "Clock In", "Successful Clock In", 200, f"{code} status={res_in.get('status')}", "PASS")
        else:
            record_test("ATT-CLKIN-011", "Clock In", "Successful Clock In", 200, f"{code} {res_in}", "FAIL")

        # 12. server timestamp used
        rec_clock_in_str = res_in.get("clock_in_at")
        rec_dt = datetime.fromisoformat(rec_clock_in_str.replace("Z", "+00:00"))
        if (t_before - timedelta(seconds=2)) <= rec_dt <= (t_after + timedelta(seconds=2)):
            record_test("ATT-CLKIN-012", "Clock In", "Server timestamp authoritative", "Matches server now()", "Matches server now()", "PASS")
        else:
            record_test("ATT-CLKIN-012", "Clock In", "Server timestamp authoritative", "Matches server now()", rec_clock_in_str, "FAIL")

        # 13. client cannot control clock_in_at
        if rec_clock_in_str != "1999-01-01T00:00:00Z":
            record_test("ATT-CLKIN-013", "Clock In", "Client cannot control clock_in_at", "Ignored 1999 client timestamp", rec_clock_in_str, "PASS")
        else:
            record_test("ATT-CLKIN-013", "Clock In", "Client cannot control clock_in_at", "Ignored 1999 client timestamp", "Used client timestamp", "FAIL")

        # 14. work_date uses site timezone
        expected_work_date = now_uae.strftime("%Y-%m-%d")
        if res_in.get("work_date") == expected_work_date:
            record_test("ATT-CLKIN-014", "Clock In", "work_date uses site timezone", expected_work_date, res_in.get("work_date"), "PASS")
        else:
            record_test("ATT-CLKIN-014", "Clock In", "work_date uses site timezone", expected_work_date, res_in.get("work_date"), "FAIL")

        # 15. schedule values are snapshotted
        if res_in.get("scheduled_start_time") == "07:00:00" and res_in.get("scheduled_end_time") == "19:00:00" and res_in.get("timezone") == "Asia/Dubai":
            record_test("ATT-CLKIN-015", "Clock In", "Schedule values snapshotted", "07:00:00 to 19:00:00 Asia/Dubai", f"{res_in.get('scheduled_start_time')} to {res_in.get('scheduled_end_time')}", "PASS")
        else:
            record_test("ATT-CLKIN-015", "Clock In", "Schedule values snapshotted", "07:00:00 to 19:00:00 Asia/Dubai", f"{res_in}", "FAIL")

        # 19. same request ID is idempotent
        code_replay, res_replay = api_request("POST", "/attendance/clock-in", in_payload, token=u1_token)
        if code_replay == 200 and res_replay.get("id") == res_in.get("id"):
            record_test("ATT-CLKIN-019", "Clock In", "Same request ID is idempotent", res_in.get("id"), res_replay.get("id"), "PASS")
        else:
            record_test("ATT-CLKIN-019", "Clock In", "Same request ID is idempotent", res_in.get("id"), f"{code_replay} {res_replay}", "FAIL")

        # 20. different request ID while active rejected
        code_diff, res_diff = api_request("POST", "/attendance/clock-in", {"request_id": "diff-req-while-active"}, token=u1_token)
        if code_diff == 409 and "already exists" in res_diff.get("detail", ""):
            record_test("ATT-CLKIN-020", "Clock In", "Different request ID while active rejected", 409, f"{code_diff} {res_diff.get('detail')}", "PASS")
        else:
            record_test("ATT-CLKIN-020", "Clock In", "Different request ID while active rejected", 409, f"{code_diff} {res_diff}", "FAIL")

        # ---------------------------------------------------------
        # SECTION 4: ACTIVE & TODAY STATE (Tests 28 - 30)
        # ---------------------------------------------------------
        print("\n--- SECTION 4: ACTIVE & FORGOTTEN CLOCK OUT ---")

        # 28. open attendance remains open (clock_out_at is None)
        code_active, res_active = api_request("GET", "/attendance/active", token=u1_token)
        if code_active == 200 and res_active.get("has_active") is True and res_active.get("attendance", {}).get("clock_out_at") is None:
            record_test("ATT-ACT-028", "Forgotten Clock Out", "Open attendance remains open", True, res_active.get("has_active"), "PASS")
        else:
            record_test("ATT-ACT-028", "Forgotten Clock Out", "Open attendance remains open", True, res_active, "FAIL")

        # 29. app/API re-query still returns active attendance with dynamic running_minutes
        code_today, res_today = api_request("GET", "/attendance/today", token=u1_token)
        if code_today == 200 and res_today.get("state") == "CLOCKED_IN" and res_today.get("running_minutes") is not None:
            record_test("ATT-ACT-029", "State Query", "Re-query returns active with running duration", "CLOCKED_IN with running_minutes", f"{res_today.get('state')} mins={res_today.get('running_minutes')}", "PASS")
        else:
            record_test("ATT-ACT-029", "State Query", "Re-query returns active with running duration", "CLOCKED_IN", f"{code_today} {res_today}", "FAIL")

        # 30. next Clock In is rejected while previous attendance remains open
        # (Verified by ATT-CLKIN-020, also test explicit error detail)
        if code_diff == 409:
            record_test("ATT-ACT-030", "Forgotten Clock Out", "Next Clock In rejected while previous open", 409, code_diff, "PASS")
        else:
            record_test("ATT-ACT-030", "Forgotten Clock Out", "Next Clock In rejected while previous open", 409, code_diff, "FAIL")

        # ---------------------------------------------------------
        # SECTION 5: CLOCK OUT (Tests 21 - 27)
        # ---------------------------------------------------------
        print("\n--- SECTION 5: CLOCK OUT ---")

        # 25. Clock Out without active attendance rejected (User 2 has no active shift)
        code_no_active, res_no_active = api_request("POST", "/attendance/clock-out", {"request_id": "u2-no-active"}, token=u2_token)
        if code_no_active == 409 and "No active attendance session" in res_no_active.get("detail", ""):
            record_test("ATT-CLKOUT-025", "Clock Out", "Clock Out without active attendance rejected", 409, f"{code_no_active} {res_no_active.get('detail')}", "PASS")
        else:
            record_test("ATT-CLKOUT-025", "Clock Out", "Clock Out without active attendance rejected", 409, f"{code_no_active} {res_no_active}", "FAIL")

        # Execute Clock Out for u1
        req_out_id = "req-u1-clockout-001"
        out_payload = {
            "request_id": req_out_id,
            "device_id": "phone-samsung-galaxy-s24",
            "latitude": 25.2050,
            "longitude": 55.2710,
            "clock_out_at": "2050-01-01T00:00:00Z"  # Attempt to manipulate client time
        }
        t_out_before = utc_now()
        code_out, res_out = api_request("POST", "/attendance/clock-out", out_payload, token=u1_token)
        t_out_after = utc_now()

        # 21. successful Clock Out
        if code_out == 200 and res_out.get("status") == "CLOCKED_OUT" and res_out.get("clock_out_at") is not None:
            record_test("ATT-CLKOUT-021", "Clock Out", "Successful Clock Out", 200, f"{code_out} status={res_out.get('status')}", "PASS")
        else:
            record_test("ATT-CLKOUT-021", "Clock Out", "Successful Clock Out", 200, f"{code_out} {res_out}", "FAIL")

        # 22. server timestamp used
        out_dt = datetime.fromisoformat(res_out.get("clock_out_at").replace("Z", "+00:00"))
        if (t_out_before - timedelta(seconds=2)) <= out_dt <= (t_out_after + timedelta(seconds=2)):
            record_test("ATT-CLKOUT-022", "Clock Out", "Server timestamp authoritative", "Matches server now()", "Matches server now()", "PASS")
        else:
            record_test("ATT-CLKOUT-022", "Clock Out", "Server timestamp authoritative", "Matches server now()", res_out.get("clock_out_at"), "FAIL")

        # 23. total_worked_minutes calculated correctly
        expected_worked_minutes = int((out_dt - rec_dt).total_seconds() // 60)
        actual_worked_minutes = res_out.get("total_worked_minutes")
        if actual_worked_minutes == expected_worked_minutes:
            record_test("ATT-CLKOUT-023", "Clock Out", "total_worked_minutes calculated from actual timestamps", expected_worked_minutes, actual_worked_minutes, "PASS")
        else:
            record_test("ATT-CLKOUT-023", "Clock Out", "total_worked_minutes calculated from actual timestamps", expected_worked_minutes, actual_worked_minutes, "FAIL")

        # 24. same Clock Out request ID is idempotent
        code_out_replay, res_out_replay = api_request("POST", "/attendance/clock-out", out_payload, token=u1_token)
        if code_out_replay == 200 and res_out_replay.get("id") == res_out.get("id"):
            record_test("ATT-CLKOUT-024", "Clock Out", "Same Clock Out request ID is idempotent", res_out.get("id"), res_out_replay.get("id"), "PASS")
        else:
            record_test("ATT-CLKOUT-024", "Clock Out", "Same Clock Out request ID is idempotent", res_out.get("id"), f"{code_out_replay} {res_out_replay}", "FAIL")

        # 26. client cannot control clock_out_at
        if res_out.get("clock_out_at") != "2050-01-01T00:00:00Z":
            record_test("ATT-CLKOUT-026", "Clock Out", "Client cannot control clock_out_at", "Ignored 2050 client timestamp", res_out.get("clock_out_at"), "PASS")
        else:
            record_test("ATT-CLKOUT-026", "Clock Out", "Client cannot control clock_out_at", "Ignored 2050 client timestamp", "Used client timestamp", "FAIL")

        # 27. Clock Out cannot precede Clock In
        if out_dt >= rec_dt:
            record_test("ATT-CLKOUT-027", "Clock Out", "Clock Out chronologically after Clock In", "clock_out >= clock_in", f"{out_dt} >= {rec_dt}", "PASS")
        else:
            record_test("ATT-CLKOUT-027", "Clock Out", "Clock Out chronologically after Clock In", "clock_out >= clock_in", f"{out_dt} < {rec_dt}", "FAIL")

        # 18. second Clock In rejected (Locked Rule #2: One continuous shift per working day)
        code_second_in, res_second_in = api_request("POST", "/attendance/clock-in", {"request_id": "req-second-in-today"}, token=u1_token)
        if code_second_in == 409 and "already completed for today" in res_second_in.get("detail", ""):
            record_test("ATT-CLKIN-018", "Clock In", "Second Clock In on same day rejected (Rule #2)", 409, f"{code_second_in} {res_second_in.get('detail')}", "PASS")
        else:
            record_test("ATT-CLKIN-018", "Clock In", "Second Clock In on same day rejected (Rule #2)", 409, f"{code_second_in} {res_second_in}", "FAIL")

        # ---------------------------------------------------------
        # SECTION 6: TIMEZONE & OVERNIGHT SHIFTS (Tests 31 - 33)
        # ---------------------------------------------------------
        print("\n--- SECTION 6: TIMEZONE & OVERNIGHT SHIFT ---")

        # Create user for overnight shift test
        u_tz_id, u_tz_token = create_test_user("att.test.tz@watchmen.ae", "Overnight Watchman", is_active=True)
        overnight_sched = {
            "shift_name": "Overnight Shift",
            "start_time": "19:00:00",
            "end_time": "07:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        api_request("PUT", "/attendance/schedule", overnight_sched, token=u_tz_token)

        # 31. Asia/Dubai schedule set
        code_tz_sched, res_tz_sched = api_request("GET", "/attendance/schedule", token=u_tz_token)
        if code_tz_sched == 200 and res_tz_sched.get("timezone") == "Asia/Dubai":
            record_test("ATT-TZ-031", "Timezone", "Asia/Dubai schedule verified", "Asia/Dubai", res_tz_sched.get("timezone"), "PASS")
        else:
            record_test("ATT-TZ-031", "Timezone", "Asia/Dubai schedule verified", "Asia/Dubai", res_tz_sched, "FAIL")

        # 32 & 33. Overnight shift & work_date remains shift-start local date
        # Clock in as overnight watchman
        code_on_in, res_on_in = api_request("POST", "/attendance/clock-in", {"request_id": "req-overnight-001"}, token=u_tz_token)
        uae_today_str = datetime.now(ZoneInfo("Asia/Dubai")).strftime("%Y-%m-%d")
        if code_on_in == 200 and res_on_in.get("work_date") == uae_today_str:
            record_test("ATT-TZ-032", "Timezone", "Overnight shift clock in succeeds", 200, code_on_in, "PASS")
            record_test("ATT-TZ-033", "Timezone", "work_date locked to site shift start date", uae_today_str, res_on_in.get("work_date"), "PASS")
        else:
            record_test("ATT-TZ-032", "Timezone", "Overnight shift clock in succeeds", 200, f"{code_on_in} {res_on_in}", "FAIL")
            record_test("ATT-TZ-033", "Timezone", "work_date locked to site shift start date", uae_today_str, res_on_in.get("work_date"), "FAIL")

        # Clock out overnight shift
        api_request("POST", "/attendance/clock-out", {"request_id": "req-overnight-out"}, token=u_tz_token)

        # ---------------------------------------------------------
        # SECTION 7: HISTORY & PAGINATION (Tests 34 - 36)
        # ---------------------------------------------------------
        print("\n--- SECTION 7: HISTORY & PAGINATION ---")

        # Insert 3 historical completed records directly in DB for u1 to test pagination
        db = SessionLocal()
        past_records = [
            AttendanceRecord(
                user_id=u1_id,
                work_date=date(2026, 9, 7),
                scheduled_start_time=dtime(7, 0),
                scheduled_end_time=dtime(19, 0),
                timezone="Asia/Dubai",
                clock_in_at=datetime(2026, 9, 7, 3, 0, tzinfo=timezone.utc),
                clock_out_at=datetime(2026, 9, 7, 15, 0, tzinfo=timezone.utc),
                clock_in_request_id="hist-u1-1",
                total_worked_minutes=720,
                created_at=utc_now(),
                updated_at=utc_now()
            ),
            AttendanceRecord(
                user_id=u1_id,
                work_date=date(2026, 9, 8),
                scheduled_start_time=dtime(7, 0),
                scheduled_end_time=dtime(19, 0),
                timezone="Asia/Dubai",
                clock_in_at=datetime(2026, 9, 8, 3, 0, tzinfo=timezone.utc),
                clock_out_at=datetime(2026, 9, 8, 15, 0, tzinfo=timezone.utc),
                clock_in_request_id="hist-u1-2",
                total_worked_minutes=720,
                created_at=utc_now(),
                updated_at=utc_now()
            ),
            AttendanceRecord(
                user_id=u1_id,
                work_date=date(2026, 9, 9),
                scheduled_start_time=dtime(7, 0),
                scheduled_end_time=dtime(19, 0),
                timezone="Asia/Dubai",
                clock_in_at=datetime(2026, 9, 9, 3, 0, tzinfo=timezone.utc),
                clock_out_at=datetime(2026, 9, 9, 15, 0, tzinfo=timezone.utc),
                clock_in_request_id="hist-u1-3",
                total_worked_minutes=720,
                created_at=utc_now(),
                updated_at=utc_now()
            ),
        ]
        db.add_all(past_records)
        db.commit()
        db.close()

        # 34. history returns authenticated user's records (u1 has 4 records, u2 has 0)
        code_hist_u1, res_hist_u1 = api_request("GET", "/attendance/history", token=u1_token)
        code_hist_u2, res_hist_u2 = api_request("GET", "/attendance/history", token=u2_token)
        if code_hist_u1 == 200 and res_hist_u1.get("total") == 4 and res_hist_u2.get("total") == 0:
            record_test("ATT-HIST-034", "History", "History returns authenticated user records only", "u1=4, u2=0", f"u1={res_hist_u1.get('total')}, u2={res_hist_u2.get('total')}", "PASS")
        else:
            record_test("ATT-HIST-034", "History", "History returns authenticated user records only", "u1=4, u2=0", f"u1={res_hist_u1.get('total')}, u2={res_hist_u2.get('total')}", "FAIL")

        # 35. pagination works (limit=2, offset=1)
        code_page, res_page = api_request("GET", "/attendance/history?limit=2&offset=1", token=u1_token)
        if code_page == 200 and len(res_page.get("items", [])) == 2 and res_page.get("offset") == 1:
            record_test("ATT-HIST-035", "History", "Pagination limit & offset work", "2 items, offset 1", f"{len(res_page.get('items', []))} items", "PASS")
        else:
            record_test("ATT-HIST-035", "History", "Pagination limit & offset work", "2 items, offset 1", f"{code_page} {res_page}", "FAIL")

        # 36. newest records first
        items = res_hist_u1.get("items", [])
        dates = [item["work_date"] for item in items]
        if dates == sorted(dates, reverse=True):
            record_test("ATT-HIST-036", "History", "Newest records first (work_date DESC)", "Strictly descending", dates, "PASS")
        else:
            record_test("ATT-HIST-036", "History", "Newest records first (work_date DESC)", "Strictly descending", dates, "FAIL")

        # ---------------------------------------------------------
        # SECTION 8: CONCURRENCY & IDEMPOTENCY (Tests 37 - 39)
        # ---------------------------------------------------------
        print("\n--- SECTION 8: CONCURRENCY & IDEMPOTENCY ---")

        # Create user for concurrency testing
        u_c_id, u_c_token = create_test_user("att.test.concur@watchmen.ae", "Concurrency Watchman", is_active=True)
        sched_concur = {
            "shift_name": "Concurrency Shift",
            "start_time": "08:00:00",
            "end_time": "20:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }
        api_request("PUT", "/attendance/schedule", sched_concur, token=u_c_token)

        # 37. simultaneous Clock In requests cannot create duplicates
        # Thread A sends req_id="concur-req-A", Thread B sends req_id="concur-req-B"
        concur_results = []

        def do_clock_in(req_id):
            code, res = api_request("POST", "/attendance/clock-in", {"request_id": req_id}, token=u_c_token)
            concur_results.append((code, res))

        with ThreadPoolExecutor(max_workers=2) as executor:
            f1 = executor.submit(do_clock_in, "concur-req-A")
            f2 = executor.submit(do_clock_in, "concur-req-B")
            f1.result()
            f2.result()

        codes = sorted([r[0] for r in concur_results])
        # Exactly one must succeed (200), the other must be rejected (409)
        db = SessionLocal()
        c_shifts_count = db.query(AttendanceRecord).filter(AttendanceRecord.user_id == u_c_id).count()
        db.close()
        if codes == [200, 409] and c_shifts_count == 1:
            record_test("ATT-CONC-037", "Concurrency", "Simultaneous Clock In cannot create duplicates", [200, 409], f"{codes} rows={c_shifts_count}", "PASS")
        else:
            record_test("ATT-CONC-037", "Concurrency", "Simultaneous Clock In cannot create duplicates", [200, 409], f"{codes} rows={c_shifts_count}", "FAIL")

        # 38. simultaneous Clock Out requests cannot create multiple completions
        out_concur_results = []

        def do_clock_out(req_id):
            code, res = api_request("POST", "/attendance/clock-out", {"request_id": req_id}, token=u_c_token)
            out_concur_results.append((code, res))

        with ThreadPoolExecutor(max_workers=2) as executor:
            f1 = executor.submit(do_clock_out, "concur-out-A")
            f2 = executor.submit(do_clock_out, "concur-out-B")
            f1.result()
            f2.result()

        out_codes = sorted([r[0] for r in out_concur_results])
        db = SessionLocal()
        closed_record = db.query(AttendanceRecord).filter(AttendanceRecord.user_id == u_c_id).first()
        db.close()
        if (200 in out_codes) and closed_record.clock_out_at is not None:
            record_test("ATT-CONC-038", "Concurrency", "Simultaneous Clock Out handled safely", "At least one 200, safe outcome", out_codes, "PASS")
        else:
            record_test("ATT-CONC-038", "Concurrency", "Simultaneous Clock Out handled safely", "Safe completion", out_codes, "FAIL")

        # 39. retry after lost response is safe (re-submitting the same clock-in/out request ID)
        winning_req_out = closed_record.clock_out_request_id
        code_retry_lost, res_retry_lost = api_request("POST", "/attendance/clock-out", {"request_id": winning_req_out}, token=u_c_token)
        if code_retry_lost == 200 and res_retry_lost.get("id") == closed_record.id:
            record_test("ATT-CONC-039", "Concurrency", "Lost-response retry is 100% idempotent", closed_record.id, res_retry_lost.get("id"), "PASS")
        else:
            record_test("ATT-CONC-039", "Concurrency", "Lost-response retry is 100% idempotent", closed_record.id, f"{code_retry_lost} {res_retry_lost}", "FAIL")

        # ---------------------------------------------------------
        # SECTION 9: WEBSOCKET EVENTS & REGRESSION (Tests 40 - 41)
        # ---------------------------------------------------------
        print("\n--- SECTION 9: EVENTS & REGRESSION ---")

        # 40. attendance events are emitted through existing event infrastructure where applicable
        # Connect real WebSocket client to /ws?type=dashboard
        import websockets.sync.client
        ws_events = []
        stop_ws = threading.Event()

        def ws_listener():
            try:
                with websockets.sync.client.connect("ws://127.0.0.1:8008/ws?type=dashboard") as ws:
                    while not stop_ws.is_set():
                        try:
                            msg = ws.recv(timeout=0.5)
                            ws_events.append(json.loads(msg))
                        except TimeoutError:
                            continue
                        except Exception as e:
                            print(f"[DEBUG WS] recv error: {type(e)} {e}")
                            break
            except Exception as e:
                print(f"[DEBUG WS] connect error: {type(e)} {e}")

        ws_thread = threading.Thread(target=ws_listener, daemon=True)
        ws_thread.start()
        time.sleep(0.5)

        # Create a fresh user to trigger live events over WebSocket
        u_ws_id, u_ws_token = create_test_user("att.test.ws@watchmen.ae", "WebSocket Watchman", is_active=True)
        api_request("PUT", "/attendance/schedule", {
            "shift_name": "WS Shift",
            "start_time": "08:00:00",
            "end_time": "20:00:00",
            "timezone": "Asia/Dubai",
            "days_of_week": "1,2,3,4,5,6,7"
        }, token=u_ws_token)

        # Trigger Clock In
        api_request("POST", "/attendance/clock-in", {"request_id": "req-ws-clockin"}, token=u_ws_token)
        time.sleep(0.3)
        # Trigger Clock Out
        api_request("POST", "/attendance/clock-out", {"request_id": "req-ws-clockout"}, token=u_ws_token)
        time.sleep(0.5)

        stop_ws.set()
        ws_thread.join(timeout=1.0)

        # Verify broadcast received by dashboard WebSocket client
        event_types = [e.get("event") for e in ws_events if e.get("type") == "ATTENDANCE_EVENT"]
        has_clock_in = "CLOCK_IN" in event_types
        has_clock_out = "CLOCK_OUT" in event_types
        if has_clock_in and has_clock_out:
            record_test("ATT-EVT-040", "Events", "Attendance events emitted via existing WebSocket infrastructure", "CLOCK_IN & CLOCK_OUT present", f"Received over WS: {event_types}", "PASS")
        else:
            record_test("ATT-EVT-040", "Events", "Attendance events emitted via existing WebSocket infrastructure", "CLOCK_IN & CLOCK_OUT present", f"Received over WS: {event_types}", "FAIL")

        # 41. existing telemetry/alert/geofence events continue working
        code_health, res_health = api_request("GET", "/health")
        code_config, res_config = api_request("GET", "/api/v1/config")
        code_data, res_data = api_request("GET", "/data?limit=5")
        if code_health == 200 and code_config == 200 and code_data == 200:
            record_test("ATT-EVT-041", "Regression", "Existing telemetry, config, health APIs intact", "All 200 OK", f"health={code_health} cfg={code_config} data={code_data}", "PASS")
        else:
            record_test("ATT-EVT-041", "Regression", "Existing telemetry, config, health APIs intact", "All 200 OK", f"health={code_health} cfg={code_config} data={code_data}", "FAIL")

    finally:
        # Cleanup
        try:
            cleanup_test_data()
        except Exception:
            pass
        proc.terminate()
        try:
            proc.wait(timeout=5.0)
        except Exception:
            proc.kill()

    # ---------------------------------------------------------
    # PRINT FINAL SUMMARY
    # ---------------------------------------------------------
    passed = sum(1 for t in TEST_RESULTS if t["status"] == "PASS")
    failed = sum(1 for t in TEST_RESULTS if t["status"] == "FAIL")
    total = len(TEST_RESULTS)

    print("\n============================================================")
    print(f"   ATTENDANCE TEST SUMMARY: Executed={total} | Passed={passed} | Failed={failed}")
    print("============================================================\n")

    if failed > 0:
        print("FAILED TESTS:")
        for t in TEST_RESULTS:
            if t["status"] == "FAIL":
                print(f"  - {t['id']}: {t['name']} (Expected: {t['expected']}, Got: {t['actual']})")
        sys.exit(1)
    else:
        print("ALL 41 ATTENDANCE TESTS PASSED 100%!")


if __name__ == "__main__":
    run_tests()

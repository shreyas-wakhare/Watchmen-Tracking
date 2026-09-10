import sys
import os
sys.path.insert(0, os.path.abspath("."))
from datetime import datetime, date, time, timedelta, timezone
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from backend.db import SessionLocal, User, UserShiftSchedule, AttendanceRecord, utc_now

UTC = timezone.utc

def run_tests():
    print("============================================================")
    print("RUNNING FUNCTIONAL DATABASE CONSTRAINT TESTS")
    print("============================================================")

    db = SessionLocal()
    test_user_ids = []
    try:
        # Create a primary test user
        now = utc_now()
        test_user = User(
            email="test_attendance_user_1@watchmen.ae",
            password_hash="test_hash_123456",
            full_name="Test Guard Michael",
            is_active=True,
            created_at=now,
            updated_at=now,
        )
        db.add(test_user)
        db.commit()
        db.refresh(test_user)
        test_user_ids.append(test_user.id)
        uid = test_user.id
        print(f"[SETUP] Created test user with ID: {uid}")

        # ------------------------------------------------------------
        # TEST 1: Two active schedules for the same user must be rejected
        # ------------------------------------------------------------
        print("\n--- TEST 1: Two active schedules for the same user ---")
        sched1 = UserShiftSchedule(
            user_id=uid,
            shift_name="Day Shift",
            start_time=time(7, 0),
            end_time=time(19, 0),
            timezone="Asia/Dubai",
            is_active=True,
        )
        db.add(sched1)
        db.commit()
        print("  Inserted schedule 1 (is_active=True): OK")

        sched2 = UserShiftSchedule(
            user_id=uid,
            shift_name="Night Shift",
            start_time=time(19, 0),
            end_time=time(7, 0),
            timezone="Asia/Dubai",
            is_active=True,
        )
        db.add(sched2)
        try:
            db.commit()
            raise AssertionError("TEST 1 FAILED: Second active schedule was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "uq_user_active_schedule" in str(e)
            print("  TEST 1 PASSED: Second active schedule rejected by uq_user_active_schedule.")

        # Also verify that a non-active schedule CAN be added (is_active=False)
        sched_inactive = UserShiftSchedule(
            user_id=uid,
            shift_name="Past Shift",
            start_time=time(8, 0),
            end_time=time(20, 0),
            timezone="Asia/Dubai",
            is_active=False,
        )
        db.add(sched_inactive)
        db.commit()
        print("  Schedule with is_active=False allowed alongside active schedule: OK")

        # ------------------------------------------------------------
        # TEST 2: Two attendance records for same user + work_date rejected
        # ------------------------------------------------------------
        print("\n--- TEST 2: Two attendance records for same user + work_date ---")
        t_in_1 = datetime(2026, 9, 10, 2, 54, tzinfo=UTC)
        t_out_1 = datetime(2026, 9, 10, 15, 2, tzinfo=UTC)
        att1 = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 10),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=t_in_1,
            clock_out_at=t_out_1,
            total_worked_minutes=728,
        )
        db.add(att1)
        db.commit()
        print("  Inserted completed attendance for 2026-09-10: OK")

        att2_same_day = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 10),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 10, 16, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
        )
        db.add(att2_same_day)
        try:
            db.commit()
            raise AssertionError("TEST 2 FAILED: Duplicate work_date for same user was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "uq_attendance_user_work_date" in str(e)
            print("  TEST 2 PASSED: Duplicate user + work_date rejected by uq_attendance_user_work_date.")

        # ------------------------------------------------------------
        # TEST 3: Two open attendance records for same user rejected
        # ------------------------------------------------------------
        print("\n--- TEST 3: Two open attendance records for same user ---")
        open_att1 = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 11),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 11, 3, 0, tzinfo=UTC),
            clock_out_at=None,  # OPEN
        )
        db.add(open_att1)
        db.commit()
        print("  Inserted open attendance for 2026-09-11 (clock_out_at=None): OK")

        open_att2 = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 12),  # Different work date!
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 12, 3, 0, tzinfo=UTC),
            clock_out_at=None,  # ALSO OPEN
        )
        db.add(open_att2)
        try:
            db.commit()
            raise AssertionError("TEST 3 FAILED: Second open attendance record was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "uq_user_single_active_shift" in str(e)
            print("  TEST 3 PASSED: Second open attendance rejected by uq_user_single_active_shift.")

        # Clean up open_att1 so we can continue testing
        db.query(AttendanceRecord).filter(AttendanceRecord.id == open_att1.id).delete()
        db.commit()

        # ------------------------------------------------------------
        # TEST 4: Duplicate clock_in_request_id rejected
        # ------------------------------------------------------------
        print("\n--- TEST 4: Duplicate clock_in_request_id ---")
        req_id_1 = "req-in-uuid-1111"
        att_req1 = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 13),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 13, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 13, 15, 0, tzinfo=UTC),
            clock_in_request_id=req_id_1,
        )
        db.add(att_req1)
        db.commit()
        print(f"  Inserted record with clock_in_request_id='{req_id_1}': OK")

        # Create second test user to ensure request_id uniqueness is global, not just per user
        user2 = User(
            email="test_attendance_user_2@watchmen.ae",
            password_hash="test_hash_654321",
            full_name="Second Guard Bob",
            is_active=True,
            created_at=now,
            updated_at=now,
        )
        db.add(user2)
        db.commit()
        db.refresh(user2)
        test_user_ids.append(user2.id)

        att_req2_dup = AttendanceRecord(
            user_id=user2.id,
            work_date=date(2026, 9, 13),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 13, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 13, 15, 0, tzinfo=UTC),
            clock_in_request_id=req_id_1,  # DUPLICATE!
        )
        db.add(att_req2_dup)
        try:
            db.commit()
            raise AssertionError("TEST 4 FAILED: Duplicate clock_in_request_id was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "uq_attendance_clock_in_request_id" in str(e)
            print("  TEST 4 PASSED: Duplicate clock_in_request_id rejected by uq_attendance_clock_in_request_id.")

        # ------------------------------------------------------------
        # TEST 5: Duplicate clock_out_request_id rejected
        # ------------------------------------------------------------
        print("\n--- TEST 5: Duplicate clock_out_request_id ---")
        req_out_id = "req-out-uuid-2222"
        att_out1 = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 14),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 14, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 14, 15, 0, tzinfo=UTC),
            clock_out_request_id=req_out_id,
        )
        db.add(att_out1)
        db.commit()
        print(f"  Inserted record with clock_out_request_id='{req_out_id}': OK")

        att_out2_dup = AttendanceRecord(
            user_id=user2.id,
            work_date=date(2026, 9, 14),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 14, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 14, 15, 0, tzinfo=UTC),
            clock_out_request_id=req_out_id,  # DUPLICATE!
        )
        db.add(att_out2_dup)
        try:
            db.commit()
            raise AssertionError("TEST 5 FAILED: Duplicate clock_out_request_id was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "uq_attendance_clock_out_request_id" in str(e)
            print("  TEST 5 PASSED: Duplicate clock_out_request_id rejected by uq_attendance_clock_out_request_id.")

        # ------------------------------------------------------------
        # TEST 6: clock_out_at earlier than clock_in_at rejected
        # ------------------------------------------------------------
        print("\n--- TEST 6: clock_out_at earlier than clock_in_at ---")
        att_inverted = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 15),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 15, 12, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 15, 10, 0, tzinfo=UTC),  # 2 hours before clock in!
        )
        db.add(att_inverted)
        try:
            db.commit()
            raise AssertionError("TEST 6 FAILED: clock_out_at < clock_in_at was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "ck_attendance_clock_out_after_in" in str(e)
            print("  TEST 6 PASSED: Inverted clock times rejected by ck_attendance_clock_out_after_in.")

        # ------------------------------------------------------------
        # TEST 7: Negative total_worked_minutes rejected
        # ------------------------------------------------------------
        print("\n--- TEST 7: Negative total_worked_minutes ---")
        att_neg_mins = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 16),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 16, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 16, 15, 0, tzinfo=UTC),
            total_worked_minutes=-45,  # NEGATIVE!
        )
        db.add(att_neg_mins)
        try:
            db.commit()
            raise AssertionError("TEST 7 FAILED: Negative total_worked_minutes was allowed!")
        except IntegrityError as e:
            db.rollback()
            assert "ck_attendance_worked_minutes_positive" in str(e)
            print("  TEST 7 PASSED: Negative worked minutes rejected by ck_attendance_worked_minutes_positive.")

        # ------------------------------------------------------------
        # TEST 8: Completed attendance record allows NULL request IDs
        # ------------------------------------------------------------
        print("\n--- TEST 8: Multiple records with NULL request IDs permitted ---")
        att_null1 = AttendanceRecord(
            user_id=uid,
            work_date=date(2026, 9, 17),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 17, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 17, 15, 0, tzinfo=UTC),
            clock_in_request_id=None,
            clock_out_request_id=None,
        )
        att_null2 = AttendanceRecord(
            user_id=user2.id,
            work_date=date(2026, 9, 17),
            scheduled_start_time=time(7, 0),
            scheduled_end_time=time(19, 0),
            timezone="Asia/Dubai",
            clock_in_at=datetime(2026, 9, 17, 3, 0, tzinfo=UTC),
            clock_out_at=datetime(2026, 9, 17, 15, 0, tzinfo=UTC),
            clock_in_request_id=None,
            clock_out_request_id=None,
        )
        db.add(att_null1)
        db.add(att_null2)
        db.commit()
        print("  TEST 8 PASSED: Multiple NULL request IDs permitted (partial indexes do not collide on NULL).")

        # ------------------------------------------------------------
        # TEST 9: ON DELETE RESTRICT on attendance_records.user_id
        # ------------------------------------------------------------
        print("\n--- TEST 9: ON DELETE RESTRICT prevents deleting user with attendance history ---")
        try:
            db.execute(text("DELETE FROM users WHERE id = :uid"), {"uid": uid})
            db.commit()
            raise AssertionError("TEST 9 FAILED: User with attendance records was deleted!")
        except IntegrityError as e:
            db.rollback()
            assert "attendance_records_user_id_fkey" in str(e) or "foreign key constraint" in str(e).lower()
            print("  TEST 9 PASSED: User deletion prevented by ON DELETE RESTRICT.")

        # ------------------------------------------------------------
        # TEST 10: ON DELETE CASCADE on user_shift_schedules.user_id
        # ------------------------------------------------------------
        print("\n--- TEST 10: ON DELETE CASCADE on user_shift_schedules ---")
        # Create a user with ONLY schedules (no attendance)
        user3 = User(
            email="test_schedule_only_user@watchmen.ae",
            password_hash="test_hash_777777",
            full_name="Schedule Only Guard",
            is_active=True,
            created_at=now,
            updated_at=now,
        )
        db.add(user3)
        db.commit()
        db.refresh(user3)
        u3_id = user3.id
        print(f"  Created user 3 with ID: {u3_id}")

        sched_user3 = UserShiftSchedule(
            user_id=u3_id,
            shift_name="Temp Shift",
            start_time=time(6, 0),
            end_time=time(18, 0),
            timezone="Asia/Dubai",
            is_active=True,
        )
        db.add(sched_user3)
        db.commit()
        print("  Added schedule to user 3: OK")

        # Now delete user 3 - should cascade to user_shift_schedules
        db.execute(text("DELETE FROM users WHERE id = :uid"), {"uid": u3_id})
        db.commit()

        remaining_sched = db.query(UserShiftSchedule).filter(UserShiftSchedule.user_id == u3_id).all()
        assert len(remaining_sched) == 0, "TEST 10 FAILED: Schedules were not cascaded!"
        print("  TEST 10 PASSED: Deleting user cascaded and removed schedules cleanly.")

        print("\n============================================================")
        print("ALL 10 DATABASE CONSTRAINT TESTS PASSED!")
        print("============================================================")

    finally:
        # Clean up test records
        try:
            db.rollback()
            if test_user_ids:
                db.execute(text("DELETE FROM attendance_records WHERE user_id = ANY(:uids)"), {"uids": test_user_ids})
                db.execute(text("DELETE FROM user_shift_schedules WHERE user_id = ANY(:uids)"), {"uids": test_user_ids})
                db.execute(text("DELETE FROM users WHERE id = ANY(:uids)"), {"uids": test_user_ids})
                db.commit()
                print(f"[CLEANUP] Deleted test users {test_user_ids} and their related records.")
        except Exception as e:
            print(f"[CLEANUP ERROR] {e}")
        finally:
            db.close()

if __name__ == "__main__":
    run_tests()

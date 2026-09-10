from datetime import datetime, date, timezone
from typing import Optional, Tuple, List
import zoneinfo
from fastapi import HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from sqlalchemy import desc

try:
    from backend.db import (
        User,
        UserShiftSchedule,
        AttendanceRecord,
        utc_now,
    )
    from backend.attendance.schemas import (
        ScheduleCreateUpdateRequest,
        ScheduleResponse,
        ClockInRequest,
        ClockOutRequest,
        AttendanceResponse,
        TodayAttendanceResponse,
        ActiveAttendanceResponse,
        AttendanceHistoryResponse,
        TeamWatchmanAttendanceItem,
        TeamTodayAttendanceResponse,
        TeamAttendanceHistoryResponse,
    )
except ImportError:
    from db import (
        User,
        UserShiftSchedule,
        AttendanceRecord,
        utc_now,
    )
    from attendance.schemas import (
        ScheduleCreateUpdateRequest,
        ScheduleResponse,
        ClockInRequest,
        ClockOutRequest,
        AttendanceResponse,
        TodayAttendanceResponse,
        ActiveAttendanceResponse,
        AttendanceHistoryResponse,
        TeamWatchmanAttendanceItem,
        TeamTodayAttendanceResponse,
        TeamAttendanceHistoryResponse,
    )


UTC = timezone.utc


# ---------------------------------------------------------------------------
# SCHEDULE SERVICE
# ---------------------------------------------------------------------------

def get_active_schedule(db: Session, user_id: int) -> Optional[UserShiftSchedule]:
    """Retrieves the currently active shift schedule for the given user."""
    return (
        db.query(UserShiftSchedule)
        .filter(
            UserShiftSchedule.user_id == user_id,
            UserShiftSchedule.is_active == True,
        )
        .first()
    )


def save_or_replace_schedule(
    db: Session, user_id: int, payload: ScheduleCreateUpdateRequest
) -> UserShiftSchedule:
    """
    Creates or replaces the user's active schedule.
    Locked Rule #1: Exactly one active recurring schedule per user at any time.
    Deactivates any existing active schedule and inserts the new active schedule.
    """
    now = utc_now()

    try:
        # Deactivate existing active schedules for this user
        db.query(UserShiftSchedule).filter(
            UserShiftSchedule.user_id == user_id,
            UserShiftSchedule.is_active == True,
        ).update(
            {"is_active": False, "updated_at": now},
            synchronize_session="fetch",
        )

        new_schedule = UserShiftSchedule(
            user_id=user_id,
            shift_name=payload.shift_name,
            start_time=payload.start_time,
            end_time=payload.end_time,
            timezone=payload.timezone,
            days_of_week=payload.days_of_week,
            is_active=True,
            created_at=now,
            updated_at=now,
        )
        db.add(new_schedule)
        db.commit()
        db.refresh(new_schedule)
        return new_schedule

    except IntegrityError:
        db.rollback()
        # Fallback in case of concurrent active schedule insertion
        existing = (
            db.query(UserShiftSchedule)
            .filter(
                UserShiftSchedule.user_id == user_id,
                UserShiftSchedule.is_active == True,
            )
            .first()
        )
        if existing:
            return existing
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Schedule conflict occurred. Please retry.",
        )
    except Exception:
        db.rollback()
        raise


# ---------------------------------------------------------------------------
# ATTENDANCE: CLOCK IN
# ---------------------------------------------------------------------------

def clock_in(
    db: Session, user: User, payload: ClockInRequest
) -> Tuple[AttendanceRecord, bool]:
    """
    Starts today's duty session.
    Returns (AttendanceRecord, is_idempotent_replay: bool).

    Enforces:
    - Locked Rule #1: Snapshot active schedule (start, end, timezone).
    - Locked Rule #2: One continuous shift per working day (uq_attendance_user_work_date).
    - Locked Rule #3: Server is authority (utc_now), client request_id for idempotency.
    - Locked Rule #4: Unclosed previous shift blocks new shift (uq_user_single_active_shift).
    - Locked Rule #5: Site timezone defines work_date (local date at clock in).
    """
    now_utc = utc_now()

    # 1. Idempotency Check: Did we already process this exact request_id?
    existing_by_request = (
        db.query(AttendanceRecord)
        .filter(AttendanceRecord.clock_in_request_id == payload.request_id)
        .first()
    )
    if existing_by_request:
        print(f"[DEBUG CLOCK_IN] Found existing record {existing_by_request.id} with user_id={existing_by_request.user_id}, current user={user.id}, req={payload.request_id}")
        if existing_by_request.user_id == user.id:
            # Idempotent replay: return existing record safely
            return existing_by_request, True
        else:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Request ID has already been used by another operation.",
            )

    # 2. Schedule Resolution
    schedule = get_active_schedule(db, user.id)
    if not schedule:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No active shift schedule configured. Please set up a schedule before clocking in.",
        )

    # 3. Timezone & Site-local Date Resolution
    try:
        site_tz = zoneinfo.ZoneInfo(schedule.timezone)
    except Exception:
        site_tz = timezone.utc

    site_dt = now_utc.astimezone(site_tz)
    site_date = site_dt.date()

    # 4. Working Day Verification (ISO weekday: 1=Mon .. 7=Sun)
    iso_day_str = str(site_dt.isoweekday())
    scheduled_days = [d.strip() for d in schedule.days_of_week.split(",") if d.strip()]
    if iso_day_str not in scheduled_days:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Today ({site_dt.strftime('%A')}) is not a scheduled working day for this shift.",
        )

    # 5. Locked Rule #4: Forgotten Clock Out / Unclosed Shift Check
    open_shift = (
        db.query(AttendanceRecord)
        .filter(
            AttendanceRecord.user_id == user.id,
            AttendanceRecord.clock_out_at.is_(None),
        )
        .first()
    )
    if open_shift:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An open attendance shift already exists. You must clock out before clocking in again.",
        )

    # 6. Locked Rule #2: One Continuous Shift Per Day Check
    same_day_shift = (
        db.query(AttendanceRecord)
        .filter(
            AttendanceRecord.user_id == user.id,
            AttendanceRecord.work_date == site_date,
        )
        .first()
    )
    if same_day_shift:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Shift already completed for today. Only one continuous shift per working day is supported.",
        )

    # 7. Create Attendance Record
    new_record = AttendanceRecord(
        user_id=user.id,
        work_date=site_date,
        scheduled_start_time=schedule.start_time,
        scheduled_end_time=schedule.end_time,
        timezone=schedule.timezone,
        clock_in_at=now_utc,
        clock_out_at=None,
        clock_in_request_id=payload.request_id,
        clock_out_request_id=None,
        clock_in_device_id=payload.device_id,
        clock_out_device_id=None,
        clock_in_lat=payload.latitude,
        clock_in_lon=payload.longitude,
        clock_out_lat=None,
        clock_out_lon=None,
        total_worked_minutes=None,
        notes=None,
        created_at=now_utc,
        updated_at=now_utc,
    )

    try:
        db.add(new_record)
        db.commit()
        db.refresh(new_record)
        return new_record, False

    except IntegrityError:
        db.rollback()
        # Concurrency race handling
        # Case A: Concurrent request with SAME request_id (idempotent recovery)
        retry_record = (
            db.query(AttendanceRecord)
            .filter(AttendanceRecord.clock_in_request_id == payload.request_id)
            .first()
        )
        if retry_record:
            if retry_record.user_id == user.id:
                return retry_record, True
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Request ID has already been used by another operation.",
            )

        # Case B: Concurrent request with DIFFERENT request_id created an open shift
        check_open = (
            db.query(AttendanceRecord)
            .filter(
                AttendanceRecord.user_id == user.id,
                AttendanceRecord.clock_out_at.is_(None),
            )
            .first()
        )
        if check_open:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="An open attendance shift already exists. You must clock out before clocking in again.",
            )

        # Case C: Same work_date duplicate
        check_day = (
            db.query(AttendanceRecord)
            .filter(
                AttendanceRecord.user_id == user.id,
                AttendanceRecord.work_date == site_date,
            )
            .first()
        )
        if check_day:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Shift already completed for today. Only one continuous shift per working day is supported.",
            )

        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Could not clock in due to a concurrent conflict. Please retry.",
        )
    except Exception:
        db.rollback()
        raise


# ---------------------------------------------------------------------------
# ATTENDANCE: CLOCK OUT
# ---------------------------------------------------------------------------

def clock_out(
    db: Session, user: User, payload: ClockOutRequest
) -> Tuple[AttendanceRecord, bool]:
    """
    Ends the user's currently open attendance.
    Returns (AttendanceRecord, is_idempotent_replay: bool).

    Enforces:
    - Server-generated clock_out_at.
    - Idempotency via clock_out_request_id.
    - Calculation of total_worked_minutes strictly from actual timestamps.
    - Requires an active open shift; raises 409 if no active shift found.
    """
    now_utc = utc_now()

    # 1. Idempotency Check: Did we already process this exact request_id?
    existing_by_request = (
        db.query(AttendanceRecord)
        .filter(AttendanceRecord.clock_out_request_id == payload.request_id)
        .first()
    )
    if existing_by_request:
        if existing_by_request.user_id == user.id:
            # Idempotent replay: return existing completed record
            return existing_by_request, True
        else:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Request ID has already been used by another operation.",
            )

    # 2. Locate Currently Open Attendance Session
    open_record = (
        db.query(AttendanceRecord)
        .filter(
            AttendanceRecord.user_id == user.id,
            AttendanceRecord.clock_out_at.is_(None),
        )
        .first()
    )
    if not open_record:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No active attendance session found to clock out from.",
        )

    # 3. Calculate Worked Duration (Strictly Actual Timestamps)
    # Ensure clock_out_at >= clock_in_at
    if now_utc < open_record.clock_in_at:
        now_utc = open_record.clock_in_at

    duration_seconds = (now_utc - open_record.clock_in_at).total_seconds()
    total_minutes = max(0, int(duration_seconds // 60))

    # 4. Update Attendance Record
    open_record.clock_out_at = now_utc
    open_record.clock_out_request_id = payload.request_id
    open_record.clock_out_device_id = payload.device_id
    open_record.clock_out_lat = payload.latitude
    open_record.clock_out_lon = payload.longitude
    open_record.total_worked_minutes = total_minutes
    open_record.updated_at = now_utc

    try:
        db.commit()
        db.refresh(open_record)
        return open_record, False

    except IntegrityError:
        db.rollback()
        # Concurrency race handling
        # Case A: Concurrent request with SAME request_id (idempotent recovery)
        retry_record = (
            db.query(AttendanceRecord)
            .filter(AttendanceRecord.clock_out_request_id == payload.request_id)
            .first()
        )
        if retry_record:
            if retry_record.user_id == user.id:
                return retry_record, True
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Request ID has already been used by another operation.",
            )

        # Case B: Concurrent request already clocked out this shift
        already_closed = (
            db.query(AttendanceRecord)
            .filter(AttendanceRecord.id == open_record.id)
            .first()
        )
        if already_closed and already_closed.clock_out_at is not None:
            return already_closed, False

        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Clock out conflict occurred. Please retry.",
        )
    except Exception:
        db.rollback()
        raise


# ---------------------------------------------------------------------------
# ATTENDANCE: TODAY / ACTIVE / HISTORY
# ---------------------------------------------------------------------------

def get_today_attendance(db: Session, user: User) -> TodayAttendanceResponse:
    """
    Returns today's attendance state for the authenticated user.
    State is derived:
    - PENDING_RESOLUTION: User has an unclosed shift from a past work_date (Rule #4).
    - CLOCKED_IN: User has an active open shift started today.
    - CLOCKED_OUT: User completed their shift today.
    - NOT_STARTED: No attendance record for today.
    """
    now_utc = utc_now()
    schedule = get_active_schedule(db, user.id)

    # Determine site timezone and date
    tz_str = schedule.timezone if schedule else "Asia/Dubai"
    try:
        site_tz = zoneinfo.ZoneInfo(tz_str)
    except Exception:
        site_tz = timezone.utc

    site_date = now_utc.astimezone(site_tz).date()

    # 1. Check for ANY open shift for this user (Rule #4 Forgotten Clock Out)
    open_record = (
        db.query(AttendanceRecord)
        .filter(
            AttendanceRecord.user_id == user.id,
            AttendanceRecord.clock_out_at.is_(None),
        )
        .first()
    )

    if open_record:
        running_mins = max(0, int((now_utc - open_record.clock_in_at).total_seconds() // 60))
        derived_state = "CLOCKED_IN" if open_record.work_date == site_date else "PENDING_RESOLUTION"

        return TodayAttendanceResponse(
            state=derived_state,
            work_date=site_date,
            schedule=ScheduleResponse.model_validate(schedule) if schedule else None,
            attendance=AttendanceResponse.from_orm_record(open_record),
            running_minutes=running_mins,
            server_time=now_utc,
        )

    # 2. If no open shift, check if a shift was completed for today
    today_record = (
        db.query(AttendanceRecord)
        .filter(
            AttendanceRecord.user_id == user.id,
            AttendanceRecord.work_date == site_date,
        )
        .first()
    )

    if today_record:
        return TodayAttendanceResponse(
            state="CLOCKED_OUT",
            work_date=site_date,
            schedule=ScheduleResponse.model_validate(schedule) if schedule else None,
            attendance=AttendanceResponse.from_orm_record(today_record),
            running_minutes=None,
            server_time=now_utc,
        )

    # 3. Not started yet today
    return TodayAttendanceResponse(
        state="NOT_STARTED",
        work_date=site_date,
        schedule=ScheduleResponse.model_validate(schedule) if schedule else None,
        attendance=None,
        running_minutes=None,
        server_time=now_utc,
    )


def get_active_attendance(db: Session, user: User) -> ActiveAttendanceResponse:
    """
    Returns the user's currently open attendance, if any.
    Used for app restart recovery and live timer reconstruction.
    """
    now_utc = utc_now()
    open_record = (
        db.query(AttendanceRecord)
        .filter(
            AttendanceRecord.user_id == user.id,
            AttendanceRecord.clock_out_at.is_(None),
        )
        .first()
    )

    if not open_record:
        return ActiveAttendanceResponse(
            has_active=False,
            attendance=None,
            running_minutes=None,
            server_time=now_utc,
        )

    running_mins = max(0, int((now_utc - open_record.clock_in_at).total_seconds() // 60))
    return ActiveAttendanceResponse(
        has_active=True,
        attendance=AttendanceResponse.from_orm_record(open_record),
        running_minutes=running_mins,
        server_time=now_utc,
    )


def get_attendance_history(
    db: Session,
    user_id: int,
    limit: int = 20,
    offset: int = 0,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> AttendanceHistoryResponse:
    """
    Returns paginated attendance history for the authenticated user only.
    Ordered newest first (work_date DESC, clock_in_at DESC).
    """
    query = db.query(AttendanceRecord).filter(AttendanceRecord.user_id == user_id)

    if start_date:
        query = query.filter(AttendanceRecord.work_date >= start_date)
    if end_date:
        query = query.filter(AttendanceRecord.work_date <= end_date)

    total = query.count()

    records = (
        query.order_by(
            desc(AttendanceRecord.work_date),
            desc(AttendanceRecord.clock_in_at),
        )
        .offset(offset)
        .limit(limit)
        .all()
    )

    items = [AttendanceResponse.from_orm_record(r) for r in records]

    return AttendanceHistoryResponse(
        total=total,
        items=items,
        limit=limit,
        offset=offset,
    )


# ---------------------------------------------------------------------------
# SUPERVISOR / TEAM ATTENDANCE READ SERVICES
# ---------------------------------------------------------------------------

def get_team_today_attendance(db: Session) -> TeamTodayAttendanceResponse:
    """
    Returns today's attendance state across all active team members/watchmen
    for supervisor dashboard view.
    """
    now_utc = utc_now()
    users = db.query(User).filter(User.is_active == True).order_by(User.id.asc()).all()

    schedules = (
        db.query(UserShiftSchedule)
        .filter(UserShiftSchedule.is_active == True)
        .all()
    )
    schedule_map = {s.user_id: s for s in schedules}

    items: List[TeamWatchmanAttendanceItem] = []
    active_count = 0

    for u in users:
        schedule = schedule_map.get(u.id)
        tz_str = schedule.timezone if schedule else "Asia/Dubai"
        try:
            site_tz = zoneinfo.ZoneInfo(tz_str)
        except Exception:
            site_tz = timezone.utc

        site_date = now_utc.astimezone(site_tz).date()

        open_record = (
            db.query(AttendanceRecord)
            .filter(
                AttendanceRecord.user_id == u.id,
                AttendanceRecord.clock_out_at.is_(None),
            )
            .first()
        )

        if open_record:
            active_count += 1
            derived_status = "CLOCKED_IN" if open_record.work_date == site_date else "PENDING_RESOLUTION"
            record_to_use = open_record
        else:
            today_record = (
                db.query(AttendanceRecord)
                .filter(
                    AttendanceRecord.user_id == u.id,
                    AttendanceRecord.work_date == site_date,
                )
                .first()
            )
            if today_record:
                derived_status = "CLOCKED_OUT"
                record_to_use = today_record
            else:
                derived_status = "NOT_STARTED"
                record_to_use = None

        item = TeamWatchmanAttendanceItem(
            id=record_to_use.id if record_to_use else None,
            user_id=u.id,
            user_name=u.full_name or u.email,
            work_date=record_to_use.work_date if record_to_use else site_date,
            scheduled_start_time=record_to_use.scheduled_start_time if record_to_use else (schedule.start_time if schedule else None),
            scheduled_end_time=record_to_use.scheduled_end_time if record_to_use else (schedule.end_time if schedule else None),
            timezone=record_to_use.timezone if record_to_use else tz_str,
            clock_in_at=record_to_use.clock_in_at if record_to_use else None,
            clock_out_at=record_to_use.clock_out_at if record_to_use else None,
            clock_in_device_id=record_to_use.clock_in_device_id if record_to_use else None,
            clock_out_device_id=record_to_use.clock_out_device_id if record_to_use else None,
            clock_in_lat=record_to_use.clock_in_lat if record_to_use else None,
            clock_in_lon=record_to_use.clock_in_lon if record_to_use else None,
            clock_out_lat=record_to_use.clock_out_lat if record_to_use else None,
            clock_out_lon=record_to_use.clock_out_lon if record_to_use else None,
            total_worked_minutes=record_to_use.total_worked_minutes if record_to_use else None,
            status=derived_status,
            shift_name=schedule.shift_name if schedule else "Default Shift",
            notes=record_to_use.notes if record_to_use else None,
            created_at=record_to_use.created_at if record_to_use else None,
        )
        items.append(item)

    return TeamTodayAttendanceResponse(
        server_time=now_utc,
        active_count=active_count,
        total_count=len(items),
        items=items,
    )


def get_team_attendance_history(
    db: Session,
    limit: int = 20,
    offset: int = 0,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
    user_id: Optional[int] = None,
    status: Optional[str] = None,
) -> TeamAttendanceHistoryResponse:
    """
    Returns paginated attendance history across all team members/watchmen.
    Ordered newest first (work_date DESC, clock_in_at DESC).
    """
    query = db.query(AttendanceRecord, User).join(User, AttendanceRecord.user_id == User.id)

    if user_id:
        query = query.filter(AttendanceRecord.user_id == user_id)
    if start_date:
        query = query.filter(AttendanceRecord.work_date >= start_date)
    if end_date:
        query = query.filter(AttendanceRecord.work_date <= end_date)

    if status:
        st = status.strip().upper()
        if st == "CLOCKED_IN":
            query = query.filter(AttendanceRecord.clock_out_at.is_(None))
        elif st == "CLOCKED_OUT":
            query = query.filter(AttendanceRecord.clock_out_at.isnot(None))

    total = query.count()

    rows = (
        query.order_by(
            desc(AttendanceRecord.work_date),
            desc(AttendanceRecord.clock_in_at),
        )
        .offset(offset)
        .limit(limit)
        .all()
    )

    items: List[TeamWatchmanAttendanceItem] = []
    for rec, user_obj in rows:
        derived_status = "CLOCKED_OUT" if rec.clock_out_at is not None else "CLOCKED_IN"
        items.append(
            TeamWatchmanAttendanceItem(
                id=rec.id,
                user_id=rec.user_id,
                user_name=user_obj.full_name or user_obj.email,
                work_date=rec.work_date,
                scheduled_start_time=rec.scheduled_start_time,
                scheduled_end_time=rec.scheduled_end_time,
                timezone=rec.timezone,
                clock_in_at=rec.clock_in_at,
                clock_out_at=rec.clock_out_at,
                clock_in_device_id=rec.clock_in_device_id,
                clock_out_device_id=rec.clock_out_device_id,
                clock_in_lat=rec.clock_in_lat,
                clock_in_lon=rec.clock_in_lon,
                clock_out_lat=rec.clock_out_lat,
                clock_out_lon=rec.clock_out_lon,
                total_worked_minutes=rec.total_worked_minutes,
                status=derived_status,
                shift_name="Default Shift",
                notes=rec.notes,
                created_at=rec.created_at,
            )
        )

    return TeamAttendanceHistoryResponse(
        total=total,
        items=items,
        limit=limit,
        offset=offset,
    )


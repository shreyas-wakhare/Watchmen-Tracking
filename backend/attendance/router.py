from datetime import date
from typing import Optional, Callable
import logging
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

try:
    from backend.db import get_db, User
    from backend.auth import get_current_user
    from backend.attendance.schemas import (
        ScheduleCreateUpdateRequest,
        ScheduleResponse,
        ClockInRequest,
        ClockOutRequest,
        AttendanceResponse,
        TodayAttendanceResponse,
        ActiveAttendanceResponse,
        AttendanceHistoryResponse,
        TeamTodayAttendanceResponse,
        TeamAttendanceHistoryResponse,
    )
    from backend.attendance import service
except ImportError:
    from db import get_db, User
    from auth import get_current_user
    from attendance.schemas import (
        ScheduleCreateUpdateRequest,
        ScheduleResponse,
        ClockInRequest,
        ClockOutRequest,
        AttendanceResponse,
        TodayAttendanceResponse,
        ActiveAttendanceResponse,
        AttendanceHistoryResponse,
        TeamTodayAttendanceResponse,
        TeamAttendanceHistoryResponse,
    )
    import attendance.service as service


logger = logging.getLogger("watchmen.attendance")

router = APIRouter(prefix="/attendance", tags=["Attendance"])

# Optional custom event broadcaster hook (for tests / integration)
_event_broadcaster: Optional[Callable] = None


def set_event_broadcaster(broadcaster: Optional[Callable]):
    global _event_broadcaster
    _event_broadcaster = broadcaster


async def emit_attendance_event(event_payload: dict):
    """
    Broadcasts attendance lifecycle events to connected dashboards
    using the existing WebSocket ConnectionManager.
    """
    if _event_broadcaster:
        try:
            res = _event_broadcaster(event_payload)
            if hasattr(res, "__await__"):
                await res
        except Exception as e:
            logger.warning(f"Error calling custom event broadcaster: {e}")
        return

    try:
        import sys
        manager = None
        if "main" in sys.modules and hasattr(sys.modules["main"], "manager"):
            manager = sys.modules["main"].manager
        elif "backend.main" in sys.modules and hasattr(sys.modules["backend.main"], "manager"):
            manager = sys.modules["backend.main"].manager
        else:
            try:
                from main import manager
            except ImportError:
                from backend.main import manager

        if manager:
            await manager.broadcast(event_payload)
            logger.info(f"📢 Broadcasted attendance event: {event_payload.get('event')} for user {event_payload.get('user_id')}")
    except Exception as e:
        logger.warning(f"Could not broadcast attendance event via WebSocket: {e}")


# ---------------------------------------------------------------------------
# SCHEDULE ENDPOINTS
# ---------------------------------------------------------------------------

@router.get("/schedule", response_model=ScheduleResponse)
def get_schedule(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return the authenticated watchman's current active schedule."""
    schedule = service.get_active_schedule(db, current_user.id)
    if not schedule:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No active shift schedule configured for this user.",
        )
    return schedule


@router.put("/schedule", response_model=ScheduleResponse)
def set_schedule(
    payload: ScheduleCreateUpdateRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Create or replace the authenticated user's active recurring schedule."""
    schedule = service.save_or_replace_schedule(db, current_user.id, payload)
    return schedule


# ---------------------------------------------------------------------------
# ATTENDANCE ACTIONS
# ---------------------------------------------------------------------------

@router.post("/clock-in", response_model=AttendanceResponse)
async def clock_in(
    payload: ClockInRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """
    Start today's duty session.
    Server generates authoritative clock_in_at timestamp.
    Idempotent on repeated submission of the same request_id.
    """
    record, is_replay = service.clock_in(db, current_user, payload)

    if not is_replay:
        await emit_attendance_event({
            "type": "ATTENDANCE_EVENT",
            "event": "CLOCK_IN",
            "user_id": current_user.id,
            "user_name": current_user.full_name,
            "work_date": str(record.work_date),
            "clock_in_at": record.clock_in_at.isoformat(),
            "scheduled_start": record.scheduled_start_time.strftime("%H:%M:%S"),
            "scheduled_end": record.scheduled_end_time.strftime("%H:%M:%S"),
            "timezone": record.timezone,
            "device_id": record.clock_in_device_id,
            "attendance_id": record.id,
        })

    return AttendanceResponse.from_orm_record(record)


@router.post("/clock-out", response_model=AttendanceResponse)
async def clock_out(
    payload: ClockOutRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """
    End the authenticated user's currently open attendance session.
    Server generates authoritative clock_out_at and calculates duration.
    Idempotent on repeated submission of the same request_id.
    """
    record, is_replay = service.clock_out(db, current_user, payload)

    if not is_replay:
        await emit_attendance_event({
            "type": "ATTENDANCE_EVENT",
            "event": "CLOCK_OUT",
            "user_id": current_user.id,
            "user_name": current_user.full_name,
            "work_date": str(record.work_date),
            "clock_out_at": record.clock_out_at.isoformat() if record.clock_out_at else None,
            "total_worked_minutes": record.total_worked_minutes,
            "device_id": record.clock_out_device_id,
            "attendance_id": record.id,
        })

    return AttendanceResponse.from_orm_record(record)


# ---------------------------------------------------------------------------
# ATTENDANCE STATE QUERIES
# ---------------------------------------------------------------------------

@router.get("/today", response_model=TodayAttendanceResponse)
def get_today(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return attendance state relevant to the current site-local day/shift."""
    return service.get_today_attendance(db, current_user)


@router.get("/active", response_model=ActiveAttendanceResponse)
def get_active(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return the authenticated user's currently open attendance, if any."""
    return service.get_active_attendance(db, current_user)


@router.get("/history", response_model=AttendanceHistoryResponse)
def get_history(
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return paginated attendance history for the authenticated user, newest first."""
    if start_date and end_date and start_date > end_date:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="start_date cannot be after end_date",
        )
    return service.get_attendance_history(
        db=db,
        user_id=current_user.id,
        limit=limit,
        offset=offset,
        start_date=start_date,
        end_date=end_date,
    )


# ---------------------------------------------------------------------------
# SUPERVISOR / TEAM ATTENDANCE READ ENDPOINTS
# ---------------------------------------------------------------------------

@router.get("/team/today", response_model=TeamTodayAttendanceResponse)
def get_team_today(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return attendance state across all watchmen/team members for supervisor dashboard view."""
    return service.get_team_today_attendance(db)


@router.get("/team/history", response_model=TeamAttendanceHistoryResponse)
def get_team_history(
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
    user_id: Optional[int] = None,
    status_filter: Optional[str] = Query(None, alias="status"),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return paginated team attendance history across watchmen for supervisor view."""
    if start_date and end_date and start_date > end_date:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="start_date cannot be after end_date",
        )
    return service.get_team_attendance_history(
        db=db,
        limit=limit,
        offset=offset,
        start_date=start_date,
        end_date=end_date,
        user_id=user_id,
        status=status_filter,
    )


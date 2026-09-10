from datetime import date, time, datetime
from typing import Optional, List
import zoneinfo
from pydantic import BaseModel, Field, field_validator, model_validator


class ScheduleCreateUpdateRequest(BaseModel):
    shift_name: str = Field("Default Shift", max_length=50)
    start_time: time
    end_time: time
    timezone: str = Field("Asia/Dubai", max_length=50)
    days_of_week: str = Field("1,2,3,4,5,6,7", max_length=30)

    @field_validator("shift_name")
    @classmethod
    def validate_shift_name(cls, v: str) -> str:
        s = v.strip()
        if not s:
            raise ValueError("Shift name cannot be empty or blank")
        return s

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, v: str) -> str:
        s = v.strip()
        try:
            zoneinfo.ZoneInfo(s)
        except Exception:
            raise ValueError(f"Invalid IANA timezone: '{s}'")
        return s

    @field_validator("days_of_week")
    @classmethod
    def validate_days_of_week(cls, v: str) -> str:
        parts = [p.strip() for p in v.split(",") if p.strip()]
        if not parts:
            raise ValueError("days_of_week cannot be empty")
        seen = set()
        for p in parts:
            if not p.isdigit() or int(p) < 1 or int(p) > 7:
                raise ValueError(f"Invalid day in days_of_week: '{p}'. Must be 1 to 7 (1=Mon, 7=Sun)")
            if p in seen:
                raise ValueError(f"Duplicate day in days_of_week: '{p}'")
            seen.add(p)
        return ",".join(sorted(parts, key=int))

    @model_validator(mode="after")
    def validate_times(self) -> "ScheduleCreateUpdateRequest":
        if self.start_time == self.end_time:
            raise ValueError("start_time and end_time cannot be identical (0-minute shift)")
        return self


class ScheduleResponse(BaseModel):
    id: int
    user_id: int
    shift_name: str
    start_time: time
    end_time: time
    timezone: str
    days_of_week: str
    is_active: bool
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class ClockInRequest(BaseModel):
    request_id: str = Field(..., min_length=1, max_length=64)
    device_id: Optional[str] = Field(None, max_length=100)
    latitude: Optional[float] = Field(None, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(None, ge=-180.0, le=180.0)

    @field_validator("request_id")
    @classmethod
    def validate_request_id(cls, v: str) -> str:
        s = v.strip()
        if not s:
            raise ValueError("request_id cannot be empty or blank")
        return s

    @model_validator(mode="after")
    def validate_coordinates(self) -> "ClockInRequest":
        has_lat = self.latitude is not None
        has_lon = self.longitude is not None
        if has_lat != has_lon:
            raise ValueError("Both latitude and longitude must be provided together")
        return self


class ClockOutRequest(BaseModel):
    request_id: str = Field(..., min_length=1, max_length=64)
    device_id: Optional[str] = Field(None, max_length=100)
    latitude: Optional[float] = Field(None, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(None, ge=-180.0, le=180.0)

    @field_validator("request_id")
    @classmethod
    def validate_request_id(cls, v: str) -> str:
        s = v.strip()
        if not s:
            raise ValueError("request_id cannot be empty or blank")
        return s

    @model_validator(mode="after")
    def validate_coordinates(self) -> "ClockOutRequest":
        has_lat = self.latitude is not None
        has_lon = self.longitude is not None
        if has_lat != has_lon:
            raise ValueError("Both latitude and longitude must be provided together")
        return self


class AttendanceResponse(BaseModel):
    id: int
    user_id: int
    work_date: date
    scheduled_start_time: time
    scheduled_end_time: time
    timezone: str
    clock_in_at: datetime
    clock_out_at: Optional[datetime] = None
    clock_in_request_id: Optional[str] = None
    clock_out_request_id: Optional[str] = None
    clock_in_device_id: Optional[str] = None
    clock_out_device_id: Optional[str] = None
    clock_in_lat: Optional[float] = None
    clock_in_lon: Optional[float] = None
    clock_out_lat: Optional[float] = None
    clock_out_lon: Optional[float] = None
    total_worked_minutes: Optional[int] = None
    status: str
    notes: Optional[str] = None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}

    @classmethod
    def from_orm_record(cls, record) -> "AttendanceResponse":
        derived_status = "CLOCKED_OUT" if record.clock_out_at is not None else "CLOCKED_IN"
        return cls(
            id=record.id,
            user_id=record.user_id,
            work_date=record.work_date,
            scheduled_start_time=record.scheduled_start_time,
            scheduled_end_time=record.scheduled_end_time,
            timezone=record.timezone,
            clock_in_at=record.clock_in_at,
            clock_out_at=record.clock_out_at,
            clock_in_request_id=record.clock_in_request_id,
            clock_out_request_id=record.clock_out_request_id,
            clock_in_device_id=record.clock_in_device_id,
            clock_out_device_id=record.clock_out_device_id,
            clock_in_lat=record.clock_in_lat,
            clock_in_lon=record.clock_in_lon,
            clock_out_lat=record.clock_out_lat,
            clock_out_lon=record.clock_out_lon,
            total_worked_minutes=record.total_worked_minutes,
            status=derived_status,
            notes=record.notes,
            created_at=record.created_at,
            updated_at=record.updated_at,
        )


class ActiveAttendanceResponse(BaseModel):
    has_active: bool
    attendance: Optional[AttendanceResponse] = None
    running_minutes: Optional[int] = None
    server_time: datetime


class TodayAttendanceResponse(BaseModel):
    state: str  # NOT_STARTED | CLOCKED_IN | CLOCKED_OUT | PENDING_RESOLUTION
    work_date: date
    schedule: Optional[ScheduleResponse] = None
    attendance: Optional[AttendanceResponse] = None
    running_minutes: Optional[int] = None
    server_time: datetime


class AttendanceHistoryResponse(BaseModel):
    total: int
    items: List[AttendanceResponse]
    limit: int
    offset: int


class TeamWatchmanAttendanceItem(BaseModel):
    id: Optional[int] = None
    user_id: int
    user_name: str
    work_date: Optional[date] = None
    scheduled_start_time: Optional[time] = None
    scheduled_end_time: Optional[time] = None
    timezone: str = "Asia/Dubai"
    clock_in_at: Optional[datetime] = None
    clock_out_at: Optional[datetime] = None
    clock_in_device_id: Optional[str] = None
    clock_out_device_id: Optional[str] = None
    clock_in_lat: Optional[float] = None
    clock_in_lon: Optional[float] = None
    clock_out_lat: Optional[float] = None
    clock_out_lon: Optional[float] = None
    total_worked_minutes: Optional[int] = None
    status: str  # NOT_STARTED | CLOCKED_IN | CLOCKED_OUT | PENDING_RESOLUTION
    shift_name: Optional[str] = "Default Shift"
    notes: Optional[str] = None
    created_at: Optional[datetime] = None


class TeamTodayAttendanceResponse(BaseModel):
    server_time: datetime
    active_count: int
    total_count: int
    items: List[TeamWatchmanAttendanceItem]


class TeamAttendanceHistoryResponse(BaseModel):
    total: int
    items: List[TeamWatchmanAttendanceItem]
    limit: int
    offset: int


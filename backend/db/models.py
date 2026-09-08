from datetime import datetime, timezone
from sqlalchemy import (
    Column,
    Integer,
    String,
    Float,
    DateTime,
    Boolean,
    ForeignKey,
    JSON,
    Text,
)
from sqlalchemy.orm import relationship



try:
    from backend.db.base import Base
except ImportError:
    from db.base import Base

UTC = timezone.utc


def utc_now() -> datetime:
    return datetime.now(UTC)


# -------------------- DATABASE MODELS --------------------

class TelemetryDB(Base):
    __tablename__ = "telemetry"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    device_name = Column(String, nullable=True)
    project_number = Column(String, nullable=True, index=True)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    speed = Column(Float, default=0.0)
    bearing = Column(Float, default=0.0)
    altitude = Column(Float, default=0.0)
    accuracy = Column(Float, default=0.0)
    steps = Column(Integer, default=0)
    battery = Column(Float)
    offline = Column(Integer, default=0)
    tracking_state = Column(String, default="MOVING", index=True)
    movement_context = Column(JSON, nullable=True)
    device_health = Column(JSON, nullable=True)
    timestamp = Column(DateTime(timezone=True), default=utc_now, index=True)
    created_at = Column(DateTime(timezone=True), default=utc_now)


class AlertDB(Base):
    __tablename__ = "alerts"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    alert_type = Column(String, index=True)
    violation_type = Column(String, nullable=True)
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    accuracy = Column(Float, nullable=True)
    battery = Column(Float, nullable=True)
    reason = Column(Text, nullable=True)
    details = Column(Text, nullable=True)
    priority = Column(String, default="NORMAL")
    timestamp = Column(DateTime(timezone=True), default=utc_now, index=True)
    resolved = Column(Boolean, default=False)

    image_path = Column(String, nullable=True)
    liveness_verified = Column(Boolean, default=False)
    liveness_confidence = Column(Float, default=0.0)
    spoof_type = Column(String, default="none")
    liveness_reasons = Column(Text, nullable=True)
    override_used = Column(Boolean, default=False)


class AnnouncementDB(Base):
    __tablename__ = "announcements"

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String, nullable=False)
    message = Column(Text, nullable=False)
    priority = Column(String, default="NORMAL")
    language = Column(String, default="en")
    tts = Column(Boolean, default=True)
    vibrate = Column(Boolean, default=False)
    raise_alert = Column(Boolean, default=False)

    device_id = Column(String, nullable=True)  # NULL = broadcast

    created_at = Column(DateTime(timezone=True), default=utc_now)
    expires_at = Column(DateTime(timezone=True), nullable=True)


class TrialFailureDB(Base):
    __tablename__ = "trial_failures"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    attempt_number = Column(Integer)
    confidence = Column(Float)
    reasons = Column(Text)
    timestamp = Column(DateTime(timezone=True), default=utc_now)


class GeofenceDB(Base):
    __tablename__ = "geofences"
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True)
    type = Column(String, default="circle")
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    radius = Column(Float, nullable=True)
    coordinates = Column(JSON, nullable=True)
    color = Column(String, default="#00f5ff")
    enabled = Column(Boolean, default=True)
    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)


class GeofenceEventDB(Base):
    __tablename__ = "geofence_events"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    geofence_id = Column(Integer, ForeignKey("geofences.id"))
    geofence_name = Column(String)
    event_type = Column(String, index=True)
    latitude = Column(Float)
    longitude = Column(Float)
    timestamp = Column(DateTime(timezone=True), default=utc_now, index=True)


class IncidentDB(Base):
    __tablename__ = "incidents"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    incident_type = Column(String, index=True)
    description = Column(Text)
    latitude = Column(Float)
    longitude = Column(Float)
    accuracy = Column(Float, nullable=True)
    has_photo = Column(Boolean, default=False)
    photo_path = Column(String, nullable=True)
    timestamp = Column(DateTime(timezone=True), default=utc_now, index=True)
    resolved = Column(Boolean, default=False)


class CheckpointDB(Base):
    __tablename__ = "checkpoints"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    checkpoint_id = Column(String, index=True)
    checkpoint_name = Column(String)
    latitude = Column(Float)
    longitude = Column(Float)
    timestamp = Column(DateTime(timezone=True), default=utc_now, index=True)


class CrashReportDB(Base):
    __tablename__ = "crash_reports"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    crash_time = Column(DateTime(timezone=True))
    error_message = Column(Text)
    stacktrace = Column(Text)
    created_at = Column(DateTime(timezone=True), default=utc_now)


class BugReportDB(Base):
    __tablename__ = "bug_reports"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True, nullable=False)
    app_version = Column(String, nullable=True)
    os_version = Column(String, nullable=True)
    device_model = Column(String, nullable=True)

    title = Column(String, nullable=False)
    description = Column(Text, nullable=False)
    severity = Column(String, default="MEDIUM")  # LOW | MEDIUM | HIGH | CRITICAL

    screenshot_path = Column(String, nullable=True)
    logs = Column(Text, nullable=True)

    created_at = Column(DateTime(timezone=True), default=utc_now)
    resolved = Column(Boolean, default=False)


class SecurityAlertDB(Base):
    __tablename__ = "security_alerts"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, index=True)
    alert_type = Column(String, index=True)
    details = Column(Text)
    timestamp = Column(DateTime(timezone=True), default=utc_now, index=True)


class AnnouncementReceiptDB(Base):
    __tablename__ = "announcement_receipts"

    id = Column(Integer, primary_key=True)
    announcement_id = Column(
        Integer, ForeignKey("announcements.id", ondelete="CASCADE"),
        index=True
    )
    device_id = Column(String, index=True)

    delivered_at = Column(DateTime(timezone=True), nullable=True)
    acked_at = Column(DateTime(timezone=True), nullable=True)

    ack_status = Column(String, nullable=True)  # PLAYED | DISMISSED | FAILED
    retry_count = Column(Integer, default=0)

    __table_args__ = (
        {'sqlite_autoincrement': True},
    )


# -------------------- AUTHENTICATION MODELS (PHASE 2A) --------------------

class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, nullable=False, unique=True, index=True)
    password_hash = Column(String, nullable=False)
    full_name = Column(String, nullable=False)
    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utc_now, onupdate=utc_now)
    last_login_at = Column(DateTime(timezone=True), nullable=True)

    refresh_tokens = relationship("RefreshToken", back_populates="user", cascade="all, delete-orphan")


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    token_hash = Column(String, nullable=False, index=True)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)

    user = relationship("User", back_populates="refresh_tokens")


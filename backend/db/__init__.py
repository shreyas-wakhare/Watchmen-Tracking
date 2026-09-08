try:
    from backend.db.base import Base, engine, SessionLocal, get_db, DATABASE_URL
    from backend.db.models import (
        TelemetryDB,
        AlertDB,
        AnnouncementDB,
        TrialFailureDB,
        GeofenceDB,
        GeofenceEventDB,
        IncidentDB,
        CheckpointDB,
        CrashReportDB,
        BugReportDB,
        SecurityAlertDB,
        AnnouncementReceiptDB,
        User,
        RefreshToken,
        utc_now,
    )
except ImportError:
    from db.base import Base, engine, SessionLocal, get_db, DATABASE_URL
    from db.models import (
        TelemetryDB,
        AlertDB,
        AnnouncementDB,
        TrialFailureDB,
        GeofenceDB,
        GeofenceEventDB,
        IncidentDB,
        CheckpointDB,
        CrashReportDB,
        BugReportDB,
        SecurityAlertDB,
        AnnouncementReceiptDB,
        User,
        RefreshToken,
        utc_now,
    )

__all__ = [
    "Base",
    "engine",
    "SessionLocal",
    "get_db",
    "DATABASE_URL",
    "utc_now",
    "TelemetryDB",
    "AlertDB",
    "AnnouncementDB",
    "TrialFailureDB",
    "GeofenceDB",
    "GeofenceEventDB",
    "IncidentDB",
    "CheckpointDB",
    "CrashReportDB",
    "BugReportDB",
    "SecurityAlertDB",
    "AnnouncementReceiptDB",
    "User",
    "RefreshToken",
]


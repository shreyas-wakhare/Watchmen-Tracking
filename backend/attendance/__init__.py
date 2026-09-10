try:
    from backend.attendance.router import router as attendance_router
except ImportError:
    from attendance.router import router as attendance_router

__all__ = ["attendance_router"]

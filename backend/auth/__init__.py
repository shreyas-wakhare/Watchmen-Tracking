try:
    from backend.auth.router import router as auth_router
    from backend.auth.dependencies import get_current_user
except ImportError:
    from auth.router import router as auth_router
    from auth.dependencies import get_current_user

__all__ = ["auth_router", "get_current_user"]

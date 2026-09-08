try:
    from backend.auth.router import router as auth_router
except ImportError:
    from auth.router import router as auth_router

__all__ = ["auth_router"]

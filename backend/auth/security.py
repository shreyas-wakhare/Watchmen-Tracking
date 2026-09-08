import os
import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from jose import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError, InvalidHashError

UTC = timezone.utc

_ph = PasswordHasher()


try:
    from backend.config import (
        get_jwt_secret_key,
        get_jwt_algorithm,
        get_access_token_expire_minutes,
        get_refresh_token_expire_days,
    )
except ImportError:
    from config import (
        get_jwt_secret_key,
        get_jwt_algorithm,
        get_access_token_expire_minutes,
        get_refresh_token_expire_days,
    )



def hash_password(password: str) -> str:
    return _ph.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return _ph.verify(password_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False



def create_access_token(user_id: int, expires_delta: timedelta | None = None) -> str:
    secret_key = get_jwt_secret_key()
    algorithm = get_jwt_algorithm()
    now = datetime.now(UTC)
    
    if expires_delta is not None:
        expire = now + expires_delta
    else:
        expire = now + timedelta(minutes=get_access_token_expire_minutes())

    
    payload = {
        "sub": str(user_id),
        "iat": int(now.timestamp()),
        "exp": int(expire.timestamp()),
        "type": "access",
    }
    return jwt.encode(payload, secret_key, algorithm=algorithm)


def generate_refresh_token() -> str:
    return secrets.token_urlsafe(32)


def hash_refresh_token(raw_token: str) -> str:
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

from datetime import timedelta
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from sqlalchemy.exc import IntegrityError

try:
    from backend.db import get_db, User, RefreshToken, utc_now
    from backend.auth.schemas import (
        SignupRequest,
        SignupResponse,
        UserResponse,
        LoginRequest,
        LoginResponse,
    )
    from backend.auth.security import (
        hash_password,
        verify_password,
        create_access_token,
        generate_refresh_token,
        hash_refresh_token,
        get_access_token_expire_minutes,
        get_refresh_token_expire_days,
    )
except ImportError:
    from db import get_db, User, RefreshToken, utc_now
    from auth.schemas import (
        SignupRequest,
        SignupResponse,
        UserResponse,
        LoginRequest,
        LoginResponse,
    )
    from auth.security import (
        hash_password,
        verify_password,
        create_access_token,
        generate_refresh_token,
        hash_refresh_token,
        get_access_token_expire_minutes,
        get_refresh_token_expire_days,
    )

router = APIRouter(prefix="/auth", tags=["Authentication"])


@router.post("/signup", response_model=SignupResponse, status_code=status.HTTP_201_CREATED)
def signup(payload: SignupRequest, db: Session = Depends(get_db)):
    email = payload.email.strip().lower()
    full_name = payload.full_name.strip()

    if not full_name:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Full name cannot be empty or blank",
        )

    existing_user = db.query(User).filter(User.email == email).first()
    if existing_user:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email address already exists",
        )

    pwd_hash = hash_password(payload.password)
    now = utc_now()

    new_user = User(
        email=email,
        password_hash=pwd_hash,
        full_name=full_name,
        is_active=True,
        created_at=now,
        updated_at=now,
    )

    try:
        db.add(new_user)
        db.commit()
        db.refresh(new_user)
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email address already exists",
        )
    except Exception:
        db.rollback()
        raise

    return SignupResponse(
        message="User registered successfully",
        user=UserResponse.model_validate(new_user),
    )


@router.post("/login", response_model=LoginResponse)
def login(payload: LoginRequest, db: Session = Depends(get_db)):
    email = payload.email.strip().lower()

    user = db.query(User).filter(User.email == email).first()
    if not user or not verify_password(user.password_hash, payload.password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password",
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is inactive",
        )

    now = utc_now()
    user.last_login_at = now
    user.updated_at = now

    access_token = create_access_token(user.id)
    raw_refresh_token = generate_refresh_token()
    hashed_token = hash_refresh_token(raw_refresh_token)
    expires_at = now + timedelta(days=get_refresh_token_expire_days())

    token_record = RefreshToken(
        user_id=user.id,
        token_hash=hashed_token,
        expires_at=expires_at,
        created_at=now,
        revoked_at=None,
    )

    try:
        db.add(token_record)
        db.commit()
    except Exception:
        db.rollback()
        raise

    return LoginResponse(
        access_token=access_token,
        refresh_token=raw_refresh_token,
        token_type="bearer",
        expires_in=get_access_token_expire_minutes() * 60,
        user=UserResponse.model_validate(user),
    )


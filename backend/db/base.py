import os
import logging
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

logger = logging.getLogger("watchmen.db")

try:
    from backend.config import get_database_url
except ImportError:
    from config import get_database_url

DATABASE_URL = get_database_url()

connect_args = {}
if "postgresql" in DATABASE_URL or "postgres" in DATABASE_URL:
    connect_args = {"options": "-c statement_timeout=60000"}

engine = create_engine(
    DATABASE_URL,
    pool_pre_ping=True,
    connect_args=connect_args
)

SessionLocal = sessionmaker(bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

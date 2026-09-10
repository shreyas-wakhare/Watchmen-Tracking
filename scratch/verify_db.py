import sys
import os
sys.path.insert(0, os.path.abspath("."))
import json
from sqlalchemy import text
from backend.db import engine

with engine.connect() as conn:
    # 1. Check tables
    res = conn.execute(text("""
        SELECT table_name 
        FROM information_schema.tables 
        WHERE table_schema = 'public' 
        ORDER BY table_name;
    """))
    tables = [r[0] for r in res.fetchall()]
    print("=== TABLES IN PUBLIC SCHEMA ===")
    print(tables)
    assert "user_shift_schedules" in tables, "user_shift_schedules missing!"
    assert "attendance_records" in tables, "attendance_records missing!"

    # 2. Check columns for user_shift_schedules
    res = conn.execute(text("""
        SELECT column_name, data_type, is_nullable, column_default
        FROM information_schema.columns
        WHERE table_name = 'user_shift_schedules'
        ORDER BY ordinal_position;
    """))
    print("\n=== COLUMNS: user_shift_schedules ===")
    for r in res.fetchall():
        print(f"  {r[0]}: type={r[1]}, nullable={r[2]}, default={r[3]}")

    # 3. Check columns for attendance_records
    res = conn.execute(text("""
        SELECT column_name, data_type, is_nullable, column_default
        FROM information_schema.columns
        WHERE table_name = 'attendance_records'
        ORDER BY ordinal_position;
    """))
    print("\n=== COLUMNS: attendance_records ===")
    for r in res.fetchall():
        print(f"  {r[0]}: type={r[1]}, nullable={r[2]}, default={r[3]}")

    # 4. Check constraints
    res = conn.execute(text("""
        SELECT 
            conname, 
            contype, 
            conrelid::regclass AS table_name,
            pg_get_constraintdef(c.oid) AS definition
        FROM pg_constraint c
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE n.nspname = 'public'
          AND conrelid::regclass::text IN ('user_shift_schedules', 'attendance_records')
        ORDER BY conrelid::regclass::text, conname;
    """))
    print("\n=== CONSTRAINTS ===")
    for r in res.fetchall():
        print(f"  [{r[2]}] {r[0]} ({r[1]}): {r[3]}")

    # 5. Check indexes
    res = conn.execute(text("""
        SELECT 
            tablename, 
            indexname, 
            indexdef
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename IN ('user_shift_schedules', 'attendance_records')
        ORDER BY tablename, indexname;
    """))
    print("\n=== INDEXES ===")
    for r in res.fetchall():
        print(f"  [{r[0]}] {r[1]}: {r[2]}")

print("\nDatabase verification completed successfully.")

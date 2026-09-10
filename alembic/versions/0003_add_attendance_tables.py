"""add attendance tables

Revision ID: 0003_add_attendance_tables
Revises: d2c79d4e86ed
Create Date: 2026-09-10 10:55:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0003_add_attendance_tables'
down_revision: Union[str, None] = 'd2c79d4e86ed'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # ------------------------------------------------------------------
    # 1. user_shift_schedules
    # ------------------------------------------------------------------
    op.create_table(
        'user_shift_schedules',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('user_id', sa.Integer(), nullable=False),
        sa.Column('shift_name', sa.String(length=50), nullable=False, server_default='Default Shift'),
        sa.Column('start_time', sa.Time(), nullable=False),
        sa.Column('end_time', sa.Time(), nullable=False),
        sa.Column('timezone', sa.String(length=50), nullable=False, server_default='Asia/Dubai'),
        sa.Column('days_of_week', sa.String(length=30), nullable=False, server_default='1,2,3,4,5,6,7'),
        sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.text('CURRENT_TIMESTAMP')),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.text('CURRENT_TIMESTAMP')),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_user_shift_schedules_id'), 'user_shift_schedules', ['id'], unique=False)
    op.create_index(op.f('ix_user_shift_schedules_user_id'), 'user_shift_schedules', ['user_id'], unique=False)
    op.create_index(op.f('ix_user_shift_schedules_is_active'), 'user_shift_schedules', ['is_active'], unique=False)
    op.create_index(
        'uq_user_active_schedule',
        'user_shift_schedules',
        ['user_id'],
        unique=True,
        postgresql_where=sa.text('is_active = true')
    )

    # ------------------------------------------------------------------
    # 2. attendance_records
    # ------------------------------------------------------------------
    op.create_table(
        'attendance_records',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('user_id', sa.Integer(), nullable=False),
        sa.Column('work_date', sa.Date(), nullable=False),
        sa.Column('scheduled_start_time', sa.Time(), nullable=False),
        sa.Column('scheduled_end_time', sa.Time(), nullable=False),
        sa.Column('timezone', sa.String(length=50), nullable=False, server_default='Asia/Dubai'),
        sa.Column('clock_in_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('clock_out_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('clock_in_request_id', sa.String(length=64), nullable=True),
        sa.Column('clock_out_request_id', sa.String(length=64), nullable=True),
        sa.Column('clock_in_device_id', sa.String(length=100), nullable=True),
        sa.Column('clock_out_device_id', sa.String(length=100), nullable=True),
        sa.Column('clock_in_lat', sa.Float(), nullable=True),
        sa.Column('clock_in_lon', sa.Float(), nullable=True),
        sa.Column('clock_out_lat', sa.Float(), nullable=True),
        sa.Column('clock_out_lon', sa.Float(), nullable=True),
        sa.Column('total_worked_minutes', sa.Integer(), nullable=True),
        sa.Column('notes', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.text('CURRENT_TIMESTAMP')),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.text('CURRENT_TIMESTAMP')),
        sa.CheckConstraint('clock_out_at IS NULL OR clock_out_at >= clock_in_at', name='ck_attendance_clock_out_after_in'),
        sa.CheckConstraint('total_worked_minutes IS NULL OR total_worked_minutes >= 0', name='ck_attendance_worked_minutes_positive'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='RESTRICT'),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('user_id', 'work_date', name='uq_attendance_user_work_date')
    )
    op.create_index(op.f('ix_attendance_records_id'), 'attendance_records', ['id'], unique=False)
    op.create_index(op.f('ix_attendance_records_user_id'), 'attendance_records', ['user_id'], unique=False)
    op.create_index(op.f('ix_attendance_records_work_date'), 'attendance_records', ['work_date'], unique=False)
    op.create_index(op.f('ix_attendance_records_clock_in_at'), 'attendance_records', ['clock_in_at'], unique=False)
    op.create_index(op.f('ix_attendance_records_clock_out_at'), 'attendance_records', ['clock_out_at'], unique=False)
    op.create_index(op.f('ix_attendance_records_clock_in_device_id'), 'attendance_records', ['clock_in_device_id'], unique=False)
    
    # Partial unique indexes
    op.create_index(
        'uq_user_single_active_shift',
        'attendance_records',
        ['user_id'],
        unique=True,
        postgresql_where=sa.text('clock_out_at IS NULL')
    )
    op.create_index(
        'uq_attendance_clock_in_request_id',
        'attendance_records',
        ['clock_in_request_id'],
        unique=True,
        postgresql_where=sa.text('clock_in_request_id IS NOT NULL')
    )
    op.create_index(
        'uq_attendance_clock_out_request_id',
        'attendance_records',
        ['clock_out_request_id'],
        unique=True,
        postgresql_where=sa.text('clock_out_request_id IS NOT NULL')
    )

    # Composite query index
    op.create_index(
        'ix_attendance_recent_activity',
        'attendance_records',
        [sa.text('work_date DESC'), sa.text('clock_in_at DESC')],
        unique=False
    )


def downgrade() -> None:
    # ------------------------------------------------------------------
    # 1. Drop attendance_records indexes, constraints, and table
    # ------------------------------------------------------------------
    op.drop_index('ix_attendance_recent_activity', table_name='attendance_records')
    op.drop_index('uq_attendance_clock_out_request_id', table_name='attendance_records')
    op.drop_index('uq_attendance_clock_in_request_id', table_name='attendance_records')
    op.drop_index('uq_user_single_active_shift', table_name='attendance_records')
    op.drop_index(op.f('ix_attendance_records_clock_in_device_id'), table_name='attendance_records')
    op.drop_index(op.f('ix_attendance_records_clock_out_at'), table_name='attendance_records')
    op.drop_index(op.f('ix_attendance_records_clock_in_at'), table_name='attendance_records')
    op.drop_index(op.f('ix_attendance_records_work_date'), table_name='attendance_records')
    op.drop_index(op.f('ix_attendance_records_user_id'), table_name='attendance_records')
    op.drop_index(op.f('ix_attendance_records_id'), table_name='attendance_records')
    op.drop_constraint('uq_attendance_user_work_date', 'attendance_records', type_='unique')
    op.drop_table('attendance_records')

    # ------------------------------------------------------------------
    # 2. Drop user_shift_schedules indexes and table
    # ------------------------------------------------------------------
    op.drop_index('uq_user_active_schedule', table_name='user_shift_schedules')
    op.drop_index(op.f('ix_user_shift_schedules_is_active'), table_name='user_shift_schedules')
    op.drop_index(op.f('ix_user_shift_schedules_user_id'), table_name='user_shift_schedules')
    op.drop_index(op.f('ix_user_shift_schedules_id'), table_name='user_shift_schedules')
    op.drop_table('user_shift_schedules')

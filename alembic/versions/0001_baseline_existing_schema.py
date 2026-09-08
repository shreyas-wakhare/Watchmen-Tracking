"""baseline existing schema

Revision ID: 0001_baseline_existing_schema
Revises: 
Create Date: 2026-09-08 12:40:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0001_baseline_existing_schema'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. alerts
    op.create_table(
        'alerts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('alert_type', sa.String(), nullable=True),
        sa.Column('violation_type', sa.String(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('accuracy', sa.Float(), nullable=True),
        sa.Column('battery', sa.Float(), nullable=True),
        sa.Column('reason', sa.Text(), nullable=True),
        sa.Column('details', sa.Text(), nullable=True),
        sa.Column('priority', sa.String(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.Column('resolved', sa.Boolean(), nullable=True),
        sa.Column('image_path', sa.String(), nullable=True),
        sa.Column('liveness_verified', sa.Boolean(), nullable=True),
        sa.Column('liveness_confidence', sa.Float(), nullable=True),
        sa.Column('spoof_type', sa.String(), nullable=True),
        sa.Column('liveness_reasons', sa.Text(), nullable=True),
        sa.Column('override_used', sa.Boolean(), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_alerts_alert_type'), 'alerts', ['alert_type'], unique=False)
    op.create_index(op.f('ix_alerts_device_id'), 'alerts', ['device_id'], unique=False)
    op.create_index(op.f('ix_alerts_id'), 'alerts', ['id'], unique=False)
    op.create_index(op.f('ix_alerts_timestamp'), 'alerts', ['timestamp'], unique=False)

    # 2. announcements
    op.create_table(
        'announcements',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('title', sa.String(), nullable=False),
        sa.Column('message', sa.Text(), nullable=False),
        sa.Column('priority', sa.String(), nullable=True),
        sa.Column('language', sa.String(), nullable=True),
        sa.Column('tts', sa.Boolean(), nullable=True),
        sa.Column('vibrate', sa.Boolean(), nullable=True),
        sa.Column('raise_alert', sa.Boolean(), nullable=True),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_announcements_id'), 'announcements', ['id'], unique=False)

    # 3. bug_reports
    op.create_table(
        'bug_reports',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=False),
        sa.Column('app_version', sa.String(), nullable=True),
        sa.Column('os_version', sa.String(), nullable=True),
        sa.Column('device_model', sa.String(), nullable=True),
        sa.Column('title', sa.String(), nullable=False),
        sa.Column('description', sa.Text(), nullable=False),
        sa.Column('severity', sa.String(), nullable=True),
        sa.Column('screenshot_path', sa.String(), nullable=True),
        sa.Column('logs', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('resolved', sa.Boolean(), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_bug_reports_device_id'), 'bug_reports', ['device_id'], unique=False)
    op.create_index(op.f('ix_bug_reports_id'), 'bug_reports', ['id'], unique=False)

    # 4. checkpoints
    op.create_table(
        'checkpoints',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('checkpoint_id', sa.String(), nullable=True),
        sa.Column('checkpoint_name', sa.String(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_checkpoints_checkpoint_id'), 'checkpoints', ['checkpoint_id'], unique=False)
    op.create_index(op.f('ix_checkpoints_device_id'), 'checkpoints', ['device_id'], unique=False)
    op.create_index(op.f('ix_checkpoints_id'), 'checkpoints', ['id'], unique=False)
    op.create_index(op.f('ix_checkpoints_timestamp'), 'checkpoints', ['timestamp'], unique=False)

    # 5. crash_reports
    op.create_table(
        'crash_reports',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('crash_time', sa.DateTime(timezone=True), nullable=True),
        sa.Column('error_message', sa.Text(), nullable=True),
        sa.Column('stacktrace', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_crash_reports_device_id'), 'crash_reports', ['device_id'], unique=False)
    op.create_index(op.f('ix_crash_reports_id'), 'crash_reports', ['id'], unique=False)

    # 6. geofences
    op.create_table(
        'geofences',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('name', sa.String(), nullable=True),
        sa.Column('type', sa.String(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('radius', sa.Float(), nullable=True),
        sa.Column('coordinates', sa.JSON(), nullable=True),
        sa.Column('color', sa.String(), nullable=True),
        sa.Column('enabled', sa.Boolean(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_geofences_id'), 'geofences', ['id'], unique=False)
    op.create_index(op.f('ix_geofences_name'), 'geofences', ['name'], unique=False)

    # 7. incidents
    op.create_table(
        'incidents',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('incident_type', sa.String(), nullable=True),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('accuracy', sa.Float(), nullable=True),
        sa.Column('has_photo', sa.Boolean(), nullable=True),
        sa.Column('photo_path', sa.String(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.Column('resolved', sa.Boolean(), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_incidents_device_id'), 'incidents', ['device_id'], unique=False)
    op.create_index(op.f('ix_incidents_id'), 'incidents', ['id'], unique=False)
    op.create_index(op.f('ix_incidents_incident_type'), 'incidents', ['incident_type'], unique=False)
    op.create_index(op.f('ix_incidents_timestamp'), 'incidents', ['timestamp'], unique=False)

    # 8. security_alerts
    op.create_table(
        'security_alerts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('alert_type', sa.String(), nullable=True),
        sa.Column('details', sa.Text(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_security_alerts_alert_type'), 'security_alerts', ['alert_type'], unique=False)
    op.create_index(op.f('ix_security_alerts_device_id'), 'security_alerts', ['device_id'], unique=False)
    op.create_index(op.f('ix_security_alerts_id'), 'security_alerts', ['id'], unique=False)
    op.create_index(op.f('ix_security_alerts_timestamp'), 'security_alerts', ['timestamp'], unique=False)

    # 9. telemetry
    op.create_table(
        'telemetry',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('device_name', sa.String(), nullable=True),
        sa.Column('project_number', sa.String(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=False),
        sa.Column('longitude', sa.Float(), nullable=False),
        sa.Column('speed', sa.Float(), nullable=True),
        sa.Column('bearing', sa.Float(), nullable=True),
        sa.Column('altitude', sa.Float(), nullable=True),
        sa.Column('accuracy', sa.Float(), nullable=True),
        sa.Column('steps', sa.Integer(), nullable=True),
        sa.Column('battery', sa.Float(), nullable=True),
        sa.Column('offline', sa.Integer(), nullable=True),
        sa.Column('tracking_state', sa.String(), nullable=True),
        sa.Column('movement_context', sa.JSON(), nullable=True),
        sa.Column('device_health', sa.JSON(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_telemetry_device_id'), 'telemetry', ['device_id'], unique=False)
    op.create_index(op.f('ix_telemetry_id'), 'telemetry', ['id'], unique=False)
    op.create_index(op.f('ix_telemetry_project_number'), 'telemetry', ['project_number'], unique=False)
    op.create_index(op.f('ix_telemetry_timestamp'), 'telemetry', ['timestamp'], unique=False)
    op.create_index(op.f('ix_telemetry_tracking_state'), 'telemetry', ['tracking_state'], unique=False)

    # 10. trial_failures
    op.create_table(
        'trial_failures',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('attempt_number', sa.Integer(), nullable=True),
        sa.Column('confidence', sa.Float(), nullable=True),
        sa.Column('reasons', sa.Text(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_trial_failures_device_id'), 'trial_failures', ['device_id'], unique=False)
    op.create_index(op.f('ix_trial_failures_id'), 'trial_failures', ['id'], unique=False)

    # 11. announcement_receipts
    op.create_table(
        'announcement_receipts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('announcement_id', sa.Integer(), nullable=True),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('delivered_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('acked_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('ack_status', sa.String(), nullable=True),
        sa.Column('retry_count', sa.Integer(), nullable=True),
        sa.ForeignKeyConstraint(['announcement_id'], ['announcements.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id'),
        sqlite_autoincrement=True
    )
    op.create_index(op.f('ix_announcement_receipts_announcement_id'), 'announcement_receipts', ['announcement_id'], unique=False)
    op.create_index(op.f('ix_announcement_receipts_device_id'), 'announcement_receipts', ['device_id'], unique=False)

    # 12. geofence_events
    op.create_table(
        'geofence_events',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('geofence_id', sa.Integer(), nullable=True),
        sa.Column('geofence_name', sa.String(), nullable=True),
        sa.Column('event_type', sa.String(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(['geofence_id'], ['geofences.id'], ),
        sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_geofence_events_device_id'), 'geofence_events', ['device_id'], unique=False)
    op.create_index(op.f('ix_geofence_events_event_type'), 'geofence_events', ['event_type'], unique=False)
    op.create_index(op.f('ix_geofence_events_id'), 'geofence_events', ['id'], unique=False)
    op.create_index(op.f('ix_geofence_events_timestamp'), 'geofence_events', ['timestamp'], unique=False)


def downgrade() -> None:
    # Drop in reverse dependency order
    op.drop_index(op.f('ix_geofence_events_timestamp'), table_name='geofence_events')
    op.drop_index(op.f('ix_geofence_events_id'), table_name='geofence_events')
    op.drop_index(op.f('ix_geofence_events_event_type'), table_name='geofence_events')
    op.drop_index(op.f('ix_geofence_events_device_id'), table_name='geofence_events')
    op.drop_table('geofence_events')

    op.drop_index(op.f('ix_announcement_receipts_device_id'), table_name='announcement_receipts')
    op.drop_index(op.f('ix_announcement_receipts_announcement_id'), table_name='announcement_receipts')
    op.drop_table('announcement_receipts')

    op.drop_index(op.f('ix_trial_failures_id'), table_name='trial_failures')
    op.drop_index(op.f('ix_trial_failures_device_id'), table_name='trial_failures')
    op.drop_table('trial_failures')

    op.drop_index(op.f('ix_telemetry_tracking_state'), table_name='telemetry')
    op.drop_index(op.f('ix_telemetry_timestamp'), table_name='telemetry')
    op.drop_index(op.f('ix_telemetry_project_number'), table_name='telemetry')
    op.drop_index(op.f('ix_telemetry_id'), table_name='telemetry')
    op.drop_index(op.f('ix_telemetry_device_id'), table_name='telemetry')
    op.drop_table('telemetry')

    op.drop_index(op.f('ix_security_alerts_timestamp'), table_name='security_alerts')
    op.drop_index(op.f('ix_security_alerts_id'), table_name='security_alerts')
    op.drop_index(op.f('ix_security_alerts_device_id'), table_name='security_alerts')
    op.drop_index(op.f('ix_security_alerts_alert_type'), table_name='security_alerts')
    op.drop_table('security_alerts')

    op.drop_index(op.f('ix_incidents_timestamp'), table_name='incidents')
    op.drop_index(op.f('ix_incidents_incident_type'), table_name='incidents')
    op.drop_index(op.f('ix_incidents_id'), table_name='incidents')
    op.drop_index(op.f('ix_incidents_device_id'), table_name='incidents')
    op.drop_table('incidents')

    op.drop_index(op.f('ix_geofences_name'), table_name='geofences')
    op.drop_index(op.f('ix_geofences_id'), table_name='geofences')
    op.drop_table('geofences')

    op.drop_index(op.f('ix_crash_reports_id'), table_name='crash_reports')
    op.drop_index(op.f('ix_crash_reports_device_id'), table_name='crash_reports')
    op.drop_table('crash_reports')

    op.drop_index(op.f('ix_checkpoints_timestamp'), table_name='checkpoints')
    op.drop_index(op.f('ix_checkpoints_id'), table_name='checkpoints')
    op.drop_index(op.f('ix_checkpoints_device_id'), table_name='checkpoints')
    op.drop_index(op.f('ix_checkpoints_checkpoint_id'), table_name='checkpoints')
    op.drop_table('checkpoints')

    op.drop_index(op.f('ix_bug_reports_id'), table_name='bug_reports')
    op.drop_index(op.f('ix_bug_reports_device_id'), table_name='bug_reports')
    op.drop_table('bug_reports')

    op.drop_index(op.f('ix_announcements_id'), table_name='announcements')
    op.drop_table('announcements')

    op.drop_index(op.f('ix_alerts_timestamp'), table_name='alerts')
    op.drop_index(op.f('ix_alerts_id'), table_name='alerts')
    op.drop_index(op.f('ix_alerts_device_id'), table_name='alerts')
    op.drop_index(op.f('ix_alerts_alert_type'), table_name='alerts')
    op.drop_table('alerts')

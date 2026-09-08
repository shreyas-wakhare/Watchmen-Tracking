from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, UploadFile, File, Form, Query, HTTPException, Depends, status
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from pydantic import BaseModel, Field, model_validator
from websockets.exceptions import ConnectionClosedOK
from datetime import datetime, timedelta, timezone
import asyncio
import logging
from sqlalchemy import create_engine, Column, Float, Integer, String, DateTime, Boolean, ForeignKey, JSON, Text, desc
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, Session
import os
from io import StringIO
from math import radians, sin, cos, sqrt, atan2
from typing import Optional, Dict, Any, Union, List
import json
from collections import defaultdict
from sqlalchemy import or_
from typing import Literal
import time
import logging


# -------------------- CONFIGURATION BOOTSTRAP --------------------
try:
    import backend.config
except ImportError:
    try:
        import config
    except ImportError:
        pass

# -------------------- SETUP --------------------
app = FastAPI(title="Watchmen Tracker", version="4.2") # Bumped version

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(GZipMiddleware, minimum_size=1000)
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
INCIDENTS_DIR = os.path.join(BASE_DIR, "incidents")
VIDEO_DIR = os.path.join(BASE_DIR, "videos")
os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(INCIDENTS_DIR, exist_ok=True)
os.makedirs(VIDEO_DIR, exist_ok=True)
BUG_DIR = os.path.join(BASE_DIR, "bugs")
os.makedirs(BUG_DIR, exist_ok=True)
app.mount("/bugs", StaticFiles(directory=BUG_DIR), name="bugs")

app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")
app.mount("/incidents", StaticFiles(directory=INCIDENTS_DIR), name="incidents")
app.mount("/videos", StaticFiles(directory=VIDEO_DIR), name="videos")
STATIC_DIR = os.path.join(BASE_DIR, "static")
os.makedirs(STATIC_DIR, exist_ok=True)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("watchmen")

try:
    from backend.db import (
        Base,
        engine,
        SessionLocal,
        get_db,
        DATABASE_URL,
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
    )
except ImportError:
    from db import (
        Base,
        engine,
        SessionLocal,
        get_db,
        DATABASE_URL,
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
    )

try:
    from backend.auth import auth_router
except ImportError:
    from auth import auth_router

app.include_router(auth_router)



# -------------------- TIME UTILITIES --------------------
UTC = timezone.utc
UAE_TZ = timezone(timedelta(hours=4))  # UTC+4
pending_location_requests = {}
EXIT_HYSTERESIS_METERS = 15.0      # buffer to avoid GPS jitter
EVENT_COOLDOWN_SECONDS = 10        # per geofence per device
def utc_now() -> datetime:
    return datetime.now(UTC)

def to_uae(dt: Optional[datetime]) -> Optional[datetime]:
    """Convert UTC datetime to UAE timezone"""
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UAE_TZ)

def parse_timestamp(ts: Optional[Union[str, int]]) -> datetime:
    """
    Universal timestamp parser:
    - None → current time
    - int → milliseconds since epoch
    - str → ISO format
    """
    if ts is None:
        return utc_now()
    
    # Handle integer (milliseconds)
    if isinstance(ts, int):
        try:
            return datetime.fromtimestamp(ts / 1000.0, tz=UTC)
        except Exception:
            return utc_now()
    
    # Handle string (ISO format)
    if isinstance(ts, str):
        try:
            if ts.endswith("Z"):
                ts = ts.replace("Z", "+00:00")
            dt = datetime.fromisoformat(ts)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=UTC)
            return dt.astimezone(UTC)
        except Exception:
            return utc_now()
    
    return utc_now()

# -------------------- PYDANTIC SCHEMAS --------------------
class OfflineTelemetryPoint(BaseModel):
    lat: float
    lon: float
    ts: int
    acc: float
    spd: float
    brg: float
    st: str

class AnnouncementAck(BaseModel):
    device_id: str
    status: Literal["PLAYED", "DISMISSED", "FAILED"]
class Telemetry(BaseModel):
    device_id: str = Field(..., alias="deviceid")
    device_name: Optional[str] = Field(None, alias="devicename")
    project_number: Optional[str] = Field(None, alias="projectnumber")
    latitude: float
    longitude: float
    speed: Optional[float] = 0.0
    bearing: Optional[float] = 0.0
    altitude: Optional[float] = 0.0
    accuracy: Optional[float] = 0.0
    steps: Optional[int] = 0
    battery: float
    offline: Optional[int] = 0
    tracking_state: Optional[str] = Field("MOVING", alias="trackingstate")
    movement_context: Optional[Dict[str, Any]] = Field(None, alias="movementcontext")
    device_health: Optional[Dict[str, Any]] = None
    timestamp: Optional[Union[str, int]] = None

    @model_validator(mode="before")
    @classmethod
    def handle_field_aliases(cls, data: Any) -> Any:
        if isinstance(data, dict):
            if "trackingstate" not in data and "tracking_state" not in data and "state" in data:
                data["trackingstate"] = data["state"]
            if "deviceid" not in data and "device_id" in data:
                data["deviceid"] = data["device_id"]
        return data

    class Config:
        populate_by_name = True
class BugReportRequest(BaseModel):
    device_id: str = Field(..., alias="device_id")
    description: str

    title: Optional[str] = "Bug Report"
    severity: Optional[str] = "MEDIUM"

    app_version: Optional[str] = None
    os_version: Optional[str] = Field(None, alias="android_version")
    device_model: Optional[str] = None

    logs: Optional[str] = None
    state: Optional[str] = None
    battery: Optional[float] = None
    language: Optional[str] = None
    timestamp: Optional[Union[int, str]] = None

    class Config:
        populate_by_name = True


class CommandRequest(BaseModel):
    """Body for POST /device/{device_id}/command. Example: {"command": "START_SIREN"}"""
    command: str

class PanicAlert(BaseModel):
    device_id: str = Field(..., alias="deviceid")
    alert_type: str = Field(..., alias="alerttype")
    violation_type: Optional[str] = Field(None, alias="violationtype")
    latitude: Optional[float] = 0.0
    longitude: Optional[float] = 0.0
    accuracy: Optional[float] = 0.0
    battery: Optional[float] = 0.0
    priority: Optional[str] = "NORMAL"
    timestamp: Optional[Union[str, int]] = None

    class Config:
        populate_by_name = True

class IncidentReport(BaseModel):
    device_id: str = Field(..., alias="deviceid")
    incident_type: str = Field(..., alias="incidenttype")
    description: str
    latitude: float
    longitude: float
    accuracy: Optional[float] = 0.0
    has_photo: Optional[bool] = Field(False, alias="hasphoto")
    photo_path: Optional[str] = Field(None, alias="photopath")
    timestamp: Optional[Union[str, int]] = None

    class Config:
        populate_by_name = True

class CheckpointVisit(BaseModel):
    device_id: str = Field(..., alias="deviceid")
    checkpoint_id: str = Field(..., alias="checkpointid")
    checkpoint_name: str = Field(..., alias="checkpointname")
    latitude: float
    longitude: float
    timestamp: Optional[Union[str, int]] = None

    class Config:
        populate_by_name = True

class SecurityAlert(BaseModel):
    device_id: str = Field(..., alias="deviceid")
    alert_type: str = Field(..., alias="alerttype")
    details: str
    timestamp: Optional[Union[str, int]] = None

    class Config:
        populate_by_name = True

class FailureReportRequest(BaseModel):
    """ ✅ NEW: For reporting liveness trial failures """
    deviceId: str
    attemptNumber: int
    confidence: float
    reasons: str
    timestamp: int

# -------------------- WEBSOCKET POOL --------------------
# ConnectionManager is defined later and instance 'manager' is created.
# We will use 'manager' for all broadcasting.


# -------------------- GEO UTILS --------------------
def haversine(lat1, lon1, lat2, lon2):
    R = 6371000.0
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c


EXIT_HYSTERESIS_METERS = 15.0
EVENT_COOLDOWN_SECONDS = 10


def check_geofences(device_id: str, lat: float, lon: float, db: Session):
    events = []
    now = utc_now()

    geofences = db.query(GeofenceDB).filter(GeofenceDB.enabled == True).all()

    for geofence in geofences:
        if geofence.type != "circle":
            continue

        dist = haversine(lat, lon, geofence.latitude, geofence.longitude)

        inside_radius = geofence.radius
        outside_radius = geofence.radius + EXIT_HYSTERESIS_METERS

        is_inside = dist <= inside_radius
        is_outside = dist >= outside_radius

        last_event = (
            db.query(GeofenceEventDB)
            .filter(
                GeofenceEventDB.device_id == device_id,
                GeofenceEventDB.geofence_id == geofence.id,
            )
            .order_by(GeofenceEventDB.timestamp.desc())
            .first()
        )

        last_state = last_event.event_type if last_event else None
        last_time = last_event.timestamp if last_event else None

        if last_time and last_time.tzinfo is None:
            last_time = last_time.replace(tzinfo=UTC)

        if last_time and (now - last_time) < timedelta(seconds=EVENT_COOLDOWN_SECONDS):
            continue

        if is_inside and last_state != "ENTER":
            event = GeofenceEventDB(
                device_id=device_id,
                geofence_id=geofence.id,
                geofence_name=geofence.name,
                event_type="ENTER",
                latitude=lat,
                longitude=lon,
                timestamp=now,
            )
            db.add(event)
            db.flush()

            events.append({
                "type": "geofence_enter",
                "geofence_id": geofence.id,
                "geofence_name": geofence.name,
                "deviceid": device_id,
            })

        elif is_outside and last_state == "ENTER":
            event = GeofenceEventDB(
                device_id=device_id,
                geofence_id=geofence.id,
                geofence_name=geofence.name,
                event_type="EXIT",
                latitude=lat,
                longitude=lon,
                timestamp=now,
            )
            db.add(event)
            db.flush()

            events.append({
                "type": "geofence_exit",
                "geofence_id": geofence.id,
                "geofence_name": geofence.name,
                "deviceid": device_id,
            })

    return events if events else None


async def process_telemetry_payload(body: dict, db: Session, broadcast: bool = True):
    """
    Internal telemetry processor shared by single + batch ingestion.
    """
    data = Telemetry(**body)
    ts_utc = parse_timestamp(data.timestamp)

    # 🔁 duplicate detection
    cutoff = ts_utc - timedelta(seconds=2)
    duplicate = db.query(TelemetryDB).filter(
        TelemetryDB.device_id == data.device_id,
        TelemetryDB.timestamp >= cutoff,
        TelemetryDB.timestamp <= ts_utc + timedelta(seconds=2),
        TelemetryDB.latitude == data.latitude,
        TelemetryDB.longitude == data.longitude
    ).first()

    if duplicate:
        if broadcast:
            await broadcast_update({
                "lat": data.latitude,
                "lon": data.longitude,
                "speed": data.speed or 0.0,
                "battery": data.battery or 0,
                "deviceid": data.device_id,
                "trackingstate": data.tracking_state,
                "timestampuae": to_uae(ts_utc).strftime("%H:%M:%S"),
                "duplicate": True
            })
        return "duplicate"




    entry = TelemetryDB(
        device_id=data.device_id,
        device_name=data.device_name,
        project_number=data.project_number,
        latitude=data.latitude,
        longitude=data.longitude,
        speed=data.speed or 0.0,
        bearing=data.bearing or 0.0,
        altitude=data.altitude or 0.0,
        accuracy=data.accuracy or 0.0,
        steps=data.steps or 0,
        battery=data.battery,
        offline=data.offline or 0,
        tracking_state=data.tracking_state or "MOVING",
        movement_context=data.movement_context,
        device_health=data.device_health,
        timestamp=ts_utc,
        created_at=utc_now()
    )

    db.add(entry)

    geofence_event = check_geofences(
        data.device_id,
        data.latitude,
        data.longitude,
        db
    )

    payload = {
        "lat": data.latitude,
        "lon": data.longitude,
        "speed": data.speed or 0.0,
        "steps": data.steps or 0,
        "battery": data.battery,
        "deviceid": data.device_id,
        "trackingstate": data.tracking_state,
        "timestampuae": to_uae(ts_utc).strftime("%H:%M:%S"),
        "offline": data.offline or 0,
        "accuracy": data.accuracy or 0.0,
        "altitude": data.altitude or 0.0,
        "bearing": data.bearing or 0.0
    }

    if geofence_event:
        payload["geofenceevent"] = geofence_event

    if broadcast:
        await manager.broadcast(payload)



    return "stored"

# ============================================================
# CORE ENDPOINTS
# ============================================================
@app.get("/")
async def index():
    return FileResponse(os.path.join(STATIC_DIR, "dashboard.html"))


@app.get("/login")
async def login_page():
    return FileResponse(os.path.join(STATIC_DIR, "login.html"))


@app.get("/signup")
async def signup_page():
    return FileResponse(os.path.join(STATIC_DIR, "signup.html"))




@app.get("/health")
async def health_check():
    db = SessionLocal()
    try:
        count = db.query(TelemetryDB).count()
        alerts = db.query(AlertDB).count()
        security = db.query(SecurityAlertDB).count()

        return {
            "status": "healthy",
            "timestamp_utc": utc_now().isoformat(),
            "timestamp_uae": to_uae(utc_now()).isoformat(),
            "telemetry_count": count,
            "alerts_count": alerts,
            "security_alerts_count": security,
            "active_connections": manager.count(),
        }
    except Exception as e:
        return JSONResponse({"status": "unhealthy", "error": str(e)}, status_code=500)
    finally:
        db.close()


@app.get("/api/v1/config")
async def get_discovery_config():
    """Metadata discovery endpoint for Android client endpoint resolution"""
    return {
        "status": "online",
        "service": "Watchmen Tracker Backend",
        "version": "4.2",
        "ws_path": "/ws",
        "timestamp_uae": to_uae(utc_now()).isoformat()
    }


def get_lan_ip() -> str:
    """Determine the host's actual outbound LAN IP address for mDNS advertisement"""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        try:
            ip = socket.gethostbyname(socket.gethostname())
        except Exception:
            ip = "127.0.0.1"
    finally:
        s.close()
    return ip


_zeroconf_instance = None
_zeroconf_info = None

@app.on_event("startup")
async def startup_event():
    global _zeroconf_instance, _zeroconf_info
    try:
        from zeroconf import ServiceInfo
        from zeroconf.asyncio import AsyncZeroconf
        import socket

        port = int(os.environ.get("PORT", 8000))
        local_ip = get_lan_ip()
        hostname = socket.gethostname()

        if local_ip != "0.0.0.0" and local_ip != "127.0.0.1":
            _zeroconf_instance = AsyncZeroconf()
            _zeroconf_info = ServiceInfo(
                "_watchmen._tcp.local.",
                "WatchmenTrackerBackend._watchmen._tcp.local.",
                addresses=[socket.inet_aton(local_ip)],
                port=port,
                properties={"version": "4.2", "path": "/ws", "host": local_ip},
                server=f"{hostname}.local.",
            )
            await _zeroconf_instance.async_register_service(_zeroconf_info, allow_name_change=True)
            logger.info(f"📡 mDNS zeroconf service registered: WatchmenTrackerBackend on {local_ip}:{port}")
        else:
            logger.info(f"ℹ️ Skipping mDNS advertisement for loopback/bind-only IP {local_ip}")
    except Exception as e:
        logger.warning(f"⚠️ mDNS registration skipped or unavailable: {repr(e)}")

@app.on_event("shutdown")
async def shutdown_event():
    global _zeroconf_instance, _zeroconf_info
    if _zeroconf_instance and _zeroconf_info:
        try:
            await _zeroconf_instance.async_unregister_service(_zeroconf_info)
            await _zeroconf_instance.async_close()
            logger.info("🔌 mDNS zeroconf service unregistered")
        except Exception as e:
            logger.warning(f"Error unregistering mDNS: {e}")



MAX_HARD_POINTS = 200000

@app.get("/data")
async def get_data(
    limit: int = Query(200, ge=0, le=MAX_HARD_POINTS),
    sinceminutes: Optional[int] = Query(None, ge=0, le=7 * 24 * 60),
    deviceid: Optional[str] = None,
    stride: int = Query(1, ge=1, le=50),  # ✅ NEW: downsampling
):
    db = SessionLocal()
    try:
        q = db.query(TelemetryDB)

        if sinceminutes:
            cutoff = utc_now() - timedelta(minutes=sinceminutes)
            q = q.filter(TelemetryDB.timestamp >= cutoff)

        if deviceid:
            q = q.filter(TelemetryDB.device_id == deviceid)

        # ✅ HARD SAFETY LIMIT (NO UNBOUNDED .all())
        effective_limit = limit if limit > 0 else MAX_HARD_POINTS

        rows = (
            q.order_by(desc(TelemetryDB.timestamp))
             .limit(effective_limit)
             .all()
        )

        # chronological order
        rows.reverse()

        # ✅ STRIDE / DOWNSAMPLE
        if stride > 1:
            rows = rows[::stride]

        data = [{
            "id": r.id,
            "deviceid": r.device_id,
            "devicename": r.device_name,
            "projectnumber": r.project_number,
            "lat": r.latitude,
            "lon": r.longitude,
            "speed": r.speed,
            "bearing": r.bearing,
            "altitude": r.altitude,
            "accuracy": r.accuracy,
            "steps": r.steps,
            "battery": r.battery,
            "offline": r.offline,
            "trackingstate": r.tracking_state,
            "timestamputc": r.timestamp.isoformat() if r.timestamp else None,
            "timestampuae": to_uae(r.timestamp).isoformat() if r.timestamp else None,
            "timestamp": r.timestamp.isoformat() if r.timestamp else None,
            "movementcontext": r.movement_context,
            "devicehealth": r.device_health
        } for r in rows]

        return JSONResponse(data)

    finally:
        db.close()


# ============================================================
# TELEMETRY + AUTO SECURITY-ALERT ROUTING
# ============================================================
@app.post("/telemetry")
async def receive_telemetry(request: Request):
    try:
        body = await request.json()
    except Exception as e:
        logger.error(f"Invalid JSON in /telemetry: {e}")
        return JSONResponse({"status": "error", "message": "Invalid JSON"}, status_code=400)

    db = SessionLocal()
    try:
        result = await process_telemetry_payload(body, db)
        db.commit()
    except Exception as e:
        db.rollback()
        logger.error(f"Telemetry processing failed: {e}")
        return JSONResponse({"status": "error", "message": str(e)}, status_code=500)
    finally:
        db.close()

    return {
        "status": result,
        "active_dashboard_connections": len(manager.dashboard_connections),
        "active_devices": len(manager.active_connections),
    }



# ============================================================
# ALERT & PHOTO UPLOAD ENDPOINTS
# ============================================================
@app.post("/alert")
async def receive_alert(request: Request):
    """✅ Panic/geofence violation alerts (JSON only)"""
    try:
        body = await request.json()
        logger.debug(f"🚨 Alert: {body}")
        data = PanicAlert(**body)
    except Exception as e:
        logger.error(f"Alert validation error: {e}")
        return JSONResponse({"status": "error", "message": str(e)}, status_code=400)
    
    db = SessionLocal()
    ts_utc = parse_timestamp(data.timestamp)
    
    alert = AlertDB(
        device_id=data.device_id,
        alert_type=data.alert_type,
        violation_type=data.violation_type,
        latitude=data.latitude,
        longitude=data.longitude,
        accuracy=data.accuracy,
        battery=data.battery,
        priority=data.priority,
        timestamp=ts_utc
    )
    db.add(alert)
    db.commit()
    db.close()
    
    ts_uae = to_uae(ts_utc)
    logger.warning(
        f"🚨 PANIC ALERT from {data.device_id} | Type={data.alert_type}"
    )
    
    await manager.broadcast({
        "alert": True,
        "alert_type": data.alert_type,
        "deviceid": data.device_id,
        "latitude": data.latitude,
        "longitude": data.longitude,
        "battery": data.battery,
        "priority": data.priority,
        "timestamp_uae": ts_uae.strftime("%H:%M:%S")
    })
    
    return {"status": "alert_logged"}

@app.post("/upload")
async def uploadphoto(
    device_id: Optional[str] = Form(None),
    deviceId: Optional[str] = Form(None),
    liveness_verified_client: bool = Form(False),
    liveness_confidence: float = Form(0.0),
    spoof_type: str = Form("none"),
    override_used: bool = Form(False),
    liveness_reasons: str = Form(""),
    image: UploadFile = File(...),
):
    final_device_id = device_id or deviceId or "UNKNOWN"
    
    try:
        filename = f"{datetime.now().strftime('%Y%m%d%H%M%S')}_{image.filename}"
        filepath = os.path.join(UPLOAD_DIR, filename)
        
        with open(filepath, "wb") as buffer:
            buffer.write(await image.read())

        db = SessionLocal()
        
        alert_type = "suspicious_override" if override_used else "hourly_checkin"
        priority = "HIGH" if override_used else "NORMAL"
        
        new_alert = AlertDB(
            device_id=final_device_id,
            image_path=filename,
            alert_type=alert_type,
            priority=priority,
            liveness_verified=liveness_verified_client,
            liveness_confidence=liveness_confidence,
            spoof_type=spoof_type,
            liveness_reasons=liveness_reasons,
            override_used=override_used,
            timestamp=utc_now()  # ✅ FIX: Changed utc_now() to datetime.now(UTC)
        )
        
        db.add(new_alert)
        db.commit()
        db.refresh(new_alert)
        db.close()

        await manager.broadcast({
            "type": "new_photo_alert",
            "deviceid": final_device_id,
            "alert_type": alert_type,
            "image": filename,
            "liveness_data": {
                "confidence": liveness_confidence,
                "reasons": liveness_reasons,
                "override": override_used
            },
            "timestamp_uae": to_uae(utc_now()).strftime("%H:%M:%S")
        })

        logger.info(f"📸 Photo Upload from {final_device_id}: {alert_type}")
        return {"status": "success", "id": new_alert.id}

    except Exception as e:
        logger.error(f"Upload failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))
# ============================================================
# INCIDENT ENDPOINTS
# ============================================================
@app.post("/incident")
async def receive_incident(request: Request):
    try:
        body = await request.json()
        data = IncidentReport(**body)
    except Exception as e:
        logger.error(f"Incident validation error: {e}")
        return JSONResponse({"status": "error", "message": str(e)}, status_code=400)
    
    db = SessionLocal()
    ts_utc = parse_timestamp(data.timestamp)
    
    incident = IncidentDB(
        device_id=data.device_id,
        incident_type=data.incident_type,
        description=data.description,
        latitude=data.latitude,
        longitude=data.longitude,
        accuracy=data.accuracy,
        has_photo=data.has_photo,
        photo_path=data.photo_path,
        timestamp=ts_utc
    )
    db.add(incident)
    db.commit()
    db.refresh(incident)
    incident_id = incident.id
    db.close()
    
    ts_uae = to_uae(ts_utc)
    logger.info(
        f"📝 Incident #{incident_id} by {data.device_id}: {data.incident_type} | {ts_uae.strftime('%H:%M:%S')}"
    )
    
    await manager.broadcast({
        "type": "incident",
        "incident_id": incident_id,
        "deviceid": data.device_id,
        "incident_type": data.incident_type,
        "latitude": data.latitude,
        "longitude": data.longitude,
        "timestamp_uae": ts_uae.strftime("%H:%M:%S")
    })
    
    return {"status": "incident_logged", "incident_id": incident_id}

@app.get("/incidents")
async def get_incidents(
    device_id: Optional[str] = None,
    hours: int = Query(24, ge=1, le=720),
    resolved: Optional[bool] = None
):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)
    q = db.query(IncidentDB).filter(IncidentDB.timestamp >= since)
    if device_id:
        q = q.filter(IncidentDB.device_id == device_id)
    if resolved is not None:
        q = q.filter(IncidentDB.resolved == resolved)
    incidents = q.order_by(desc(IncidentDB.timestamp)).limit(200).all()
    db.close()
    
    return [{
        "id": i.id,
        "deviceid": i.device_id,
        "incident_type": i.incident_type,
        "description": i.description,
        "latitude": i.latitude,
        "longitude": i.longitude,
        "accuracy": i.accuracy,
        "has_photo": i.has_photo,
        "photo_path": i.photo_path,
        "resolved": i.resolved,
        "timestamp_utc": i.timestamp.isoformat(),
        "timestamp_uae": to_uae(i.timestamp).isoformat()
    } for i in incidents]

# ============================================================
# CHECKPOINT ENDPOINTS
# ============================================================
@app.post("/checkpoint")
async def checkpoint_visit(request: Request):
    try:
        body = await request.json()
        data = CheckpointVisit(**body)
    except Exception as e:
        logger.error(f"Checkpoint validation error: {e}")
        return JSONResponse({"status": "error", "message": str(e)}, status_code=400)
    
    db = SessionLocal()
    ts_utc = parse_timestamp(data.timestamp)
    
    visit = CheckpointDB(
        device_id=data.device_id,
        checkpoint_id=data.checkpoint_id,
        checkpoint_name=data.checkpoint_name,
        latitude=data.latitude,
        longitude=data.longitude,
        timestamp=ts_utc
    )
    db.add(visit)
    db.commit()
    db.close()
    
    ts_uae = to_uae(ts_utc)
    logger.info(
        f"📍 Checkpoint '{data.checkpoint_name}' → {data.device_id} | {ts_uae.strftime('%H:%M:%S')}"
    )
    
    await manager.broadcast({
        "type": "checkpoint",
        "deviceid": data.device_id,
        "checkpoint_name": data.checkpoint_name,
        "timestamp_uae": ts_uae.strftime("%H:%M:%S")
    })
    
    return {"status": "checkpoint_logged"}

@app.get("/checkpoints")
async def get_checkpoints(
    device_id: Optional[str] = None, 
    hours: int = Query(24, ge=1, le=168)
):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)
    q = db.query(CheckpointDB).filter(CheckpointDB.timestamp >= since)
    if device_id:
        q = q.filter(CheckpointDB.device_id == device_id)
    visits = q.order_by(desc(CheckpointDB.timestamp)).limit(200).all()
    db.close()
    
    return [{
        "id": v.id,
        "deviceid": v.device_id,
        "checkpoint_id": v.checkpoint_id,
        "checkpoint_name": v.checkpoint_name,
        "latitude": v.latitude,
        "longitude": v.longitude,
        "timestamp_utc": v.timestamp.isoformat(),
        "timestamp_uae": to_uae(v.timestamp).isoformat()
    } for v in visits]

# ============================================================
# CRASH REPORTING
# ============================================================
@app.post("/crash")
async def receive_crash_report(request: Request):
    try:
        body = await request.json()
        device_id = body.get("deviceid", body.get("device_id", "UNKNOWN"))
        crash_time = body.get("crashtime", body.get("crash_time"))
        error = body.get("error", "")
        stacktrace = body.get("stacktrace", "")
        
        ts_utc = parse_timestamp(crash_time)
        
        db = SessionLocal()
        crash = CrashReportDB(
            device_id=device_id,
            crash_time=ts_utc,
            error_message=error,
            stacktrace=stacktrace
        )
        db.add(crash)
        db.commit()
        db.close()
        
        ts_uae = to_uae(ts_utc)
        logger.error(f"💥 CRASH from {device_id} @ {ts_uae} | {error}")
        
        return {"status": "crash_logged"}
    except Exception as e:
        logger.error(f"Failed to log crash: {e}")
        return {"status": "error", "message": str(e)}
@app.post("/bug-report")
async def report_bug(
    payload: str = Form(...),
    screenshot: UploadFile | None = File(None),
    db: Session = Depends(get_db),
):
    try:
        data = json.loads(payload)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid payload JSON: {e}")

    # Map deviceid -> device_id
    device_id = data.get("device_id") or data.get("deviceid") or "UNKNOWN"

    bug = BugReportDB(
        device_id=device_id,
        title=data.get("title") or "Bug Report",
        description=data.get("description") or "",
        severity=data.get("severity") or "MEDIUM",
        app_version=data.get("app_version") or "Unknown",
        os_version=data.get("os_version") or data.get("android_version") or "Unknown",
        device_model=data.get("device_model") or "Unknown",
        logs=data.get("logs") or "",
    )

    # optional: save screenshot into BUG_DIR and set screenshot_path
    # ...

    db.add(bug)
    db.commit()
    db.refresh(bug)

    await manager.broadcast({
        "type": "bug_report",
        "id": bug.id,
        "deviceid": bug.device_id,
        "severity": bug.severity,
        "title": bug.title,
    })

    return {"status": "bug_reported", "id": bug.id}


@app.get("/bug-reports")
def list_bug_reports(
    resolved: Optional[bool] = None,
    db: Session = Depends(get_db)
):
    q = db.query(BugReportDB)
    if resolved is not None:
        q = q.filter(BugReportDB.resolved == resolved)

    rows = q.order_by(desc(BugReportDB.created_at)).limit(200).all()

    return [{
        "id": r.id,
        "deviceid": r.device_id,
        "title": r.title,
        "severity": r.severity,
        "resolved": r.resolved,
        "created_at_uae": to_uae(r.created_at).isoformat(),
        "screenshot": f"/bugs/{r.screenshot_path}" if r.screenshot_path else None,
        "description": r.description,            # ✅ ADD
        "app_version": r.app_version,            # ✅ optional
        "os_version": r.os_version,              # ✅ optional
        "device_model": r.device_model,          # ✅ optional
        "logs": r.logs,                          # ✅ optional
    } for r in rows]


# ============================================================
# GEOFENCE ENDPOINTS
# ============================================================
@app.get("/geofence/config")
async def get_geofence_config(
    deviceid: Optional[str] = Query(None),
    device_id: Optional[str] = Query(None)
):
    dev_id = deviceid if isinstance(deviceid, str) and deviceid else (device_id if isinstance(device_id, str) and device_id else None)
    if not dev_id:
        raise HTTPException(status_code=400, detail="deviceid query parameter is required")
    db = SessionLocal()
    geofences = db.query(GeofenceDB).filter(GeofenceDB.enabled == True).all()
    db.close()
    
    return {
        "geofences": [{
            "id": str(g.id),
            "centerlat": g.latitude,
            "centerlon": g.longitude,
            "radiusmeters": g.radius,
            "name": g.name,
            "enabled": g.enabled
        } for g in geofences if g.type == "circle"],
        "lastupdated": int(utc_now().timestamp() * 1000)
    }

@app.get("/geofences")
async def get_geofences():
    db = SessionLocal()
    geofences = db.query(GeofenceDB).all()
    db.close()
    return [{
        "id": g.id,
        "name": g.name,
        "type": g.type,
        "latitude": g.latitude,
        "longitude": g.longitude,
        "radius": g.radius,
        "coordinates": g.coordinates,
        "color": g.color,
        "enabled": g.enabled,
        "created_at_uae": to_uae(g.created_at).isoformat()
    } for g in geofences]

@app.post("/geofences")
async def create_geofence(data: dict):
    db = SessionLocal()
    geofence_type = data.get("type", "circle")
    
    geofence = GeofenceDB(
        name=data.get("name", "Unnamed Zone"),
        type=geofence_type,
        color=data.get("color", "#00f5ff"),
        enabled=data.get("enabled", True)
    )
    
    if geofence_type == "circle":
        geofence.latitude = data["latitude"]
        geofence.longitude = data["longitude"]
        geofence.radius = data.get("radius", 100)
    elif geofence_type == "polygon":
        geofence.coordinates = data["coordinates"]

    db.add(geofence)
    db.commit()
    db.refresh(geofence)
    
    result = {
        "id": geofence.id,
        "name": geofence.name,
        "type": geofence.type,
        "latitude": geofence.latitude,
        "longitude": geofence.longitude,
        "radius": geofence.radius,
        "coordinates": geofence.coordinates,
        "color": geofence.color
    }
    db.close()
    
    await broadcast_update({"type": "geofence_created", "geofence": result})
    logger.info(f"🗺️ Geofence created: {geofence.name}")
    return {"status": "created", "geofence": result}

@app.put("/geofences/{geofence_id}")
async def update_geofence(geofence_id: int, data: dict):
    db = SessionLocal()
    geofence = db.query(GeofenceDB).filter(GeofenceDB.id == geofence_id).first()
    if not geofence:
        db.close()
        raise HTTPException(status_code=404, detail="Geofence not found")
    
    if "name" in data: geofence.name = data["name"]
    if "latitude" in data: geofence.latitude = data["latitude"]
    if "longitude" in data: geofence.longitude = data["longitude"]
    if "radius" in data: geofence.radius = data["radius"]
    if "color" in data: geofence.color = data["color"]
    if "enabled" in data: geofence.enabled = data["enabled"]
    
    db.commit()
    db.close()
    await broadcast_update({"type": "geofence_updated", "geofence_id": geofence_id, "deviceid": "global"})
    db.close()
    
    await broadcast_update({"type": "geofence_updated", "geofence_id": geofence_id, "deviceid": "global"})
    return {"status": "updated"}


# ============================================================
# INCIDENT REPORTING
# ============================================================
@app.post("/incident")
async def create_incident(
    device_id: str = Form(...),
    incident_type: str = Form(...),
    description: str = Form(...),
    latitude: float = Form(...),
    longitude: float = Form(...),
    accuracy: float = Form(0.0),
    timestamp: str = Form(None),
    file: UploadFile | None = File(None),
    db: Session = Depends(get_db)
):
    ts_utc = parse_timestamp(timestamp)
    
    incident = IncidentDB(
        device_id=device_id,
        incident_type=incident_type,
        description=description,
        latitude=latitude,
        longitude=longitude,
        accuracy=accuracy,
        timestamp=ts_utc,
        has_photo=False
    )
    
    if file:
        try:
            filename = f"inc_{device_id}_{int(utc_now().timestamp())}.jpg"
            file_path = os.path.join(INCIDENTS_DIR, filename)
            with open(file_path, "wb") as buffer:
                content = await file.read()
                buffer.write(content)
            incident.has_photo = True
            incident.photo_path = filename
        except Exception as e:
            logger.error(f"Failed to save incident photo: {e}")
            
    db.add(incident)
    db.commit()
    db.refresh(incident)
    
    # Broadcast to dashboard
    await broadcast_update({
        "type": "incident",
        "incident_id": incident.id,
        "deviceid": device_id,
        "incident_type": incident_type,
        "description": description,
        "latitude": latitude,
        "longitude": longitude,
        "timestamp_uae": to_uae(ts_utc).isoformat(),
        "has_photo": incident.has_photo,
        "photo_url": f"/incidents/{incident.photo_path}" if incident.has_photo else None
    })
    
    return {"status": "ok", "id": incident.id}

@app.get("/incidents")
async def get_incidents(hours: int = Query(72, ge=1), device_id: str | None = None):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)
    q = db.query(IncidentDB).filter(IncidentDB.timestamp >= since)
    if device_id:
        q = q.filter(IncidentDB.device_id == device_id)
    
    incidents = q.order_by(desc(IncidentDB.timestamp)).all()
    db.close()
    
    return [{
        "id": i.id,
        "deviceid": i.device_id,
        "incident_type": i.incident_type,
        "description": i.description,
        "latitude": i.latitude,
        "longitude": i.longitude,
        "timestamp_uae": to_uae(i.timestamp).isoformat(),
        "has_photo": i.has_photo,
        "photo_url": f"/incidents/{i.photo_path}" if i.has_photo else None,
        "resolved": i.resolved
    } for i in incidents]
    

# ============================================================
# LIVE LOCATION REQUEST (Dashboard → Device)
# ============================================================
@app.post("/request-location")
async def request_location(device_id: str = Query(..., min_length=3)):
    """
    Dashboard calls this.  
    We set a flag telling the device to send its GPS NOW.
    """
    try:
        pending_location_requests[device_id] = utc_now()
        logger.info(f"📡 Live location requested for {device_id}")
        return {
            "status": "ok",
            "requested_at": utc_now().isoformat()
        }
    except Exception as e:
        logger.error(f"Error requesting location: {e}")
        raise HTTPException(500, "Failed to request location")

@app.get("/check-location-request")
def check_location(device_id: Optional[str] = Query(None), deviceid: Optional[str] = Query(None)):
    """
    Device calls this every 5–10 seconds.
    If dashboard requested location, returns: { "request": true }
    """
    dev_id = device_id if isinstance(device_id, str) and device_id else (deviceid if isinstance(deviceid, str) and deviceid else "")
    ts = pending_location_requests.pop(dev_id, None)
    return {"request": ts is not None}
# ============================================================
# 🛠️ MISSING DASHBOARD GET ENDPOINTS (Add these to main.py)
# ============================================================

@app.get("/alerts")
async def get_alerts(hours: int = Query(24, ge=1), device_id: str | None = None):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)
    q = db.query(AlertDB).filter(AlertDB.timestamp >= since)
    if device_id:
        q = q.filter(AlertDB.device_id == device_id)

    alerts = q.order_by(desc(AlertDB.timestamp)).all()
    db.close()

    return [{
        "id": a.id,
        "deviceid": a.device_id,
        "alert_type": a.alert_type,
        "violation_type": a.violation_type,
        "priority": a.priority,
        "image_path": a.image_path,
        "battery": a.battery,
        "accuracy": a.accuracy,
        "liveness_verified": a.liveness_verified,
        "liveness_confidence": a.liveness_confidence,
        "spoof_type": a.spoof_type,
        "liveness_reasons": a.liveness_reasons,
        "override_used": a.override_used,
        "timestamp_uae": to_uae(a.timestamp).isoformat(),
        "latitude": a.latitude,
        "longitude": a.longitude,
    } for a in alerts]
@app.get("/stats/liveness")
def liveness_stats(db: Session = Depends(get_db)):
    return {
        "verified": db.query(AlertDB).filter(AlertDB.liveness_verified == True).count(),
        "failed": db.query(AlertDB).filter(AlertDB.liveness_verified == False).count(),
        "spoof": db.query(AlertDB).filter(AlertDB.spoof_type != "none").count(),
        "override": db.query(AlertDB).filter(AlertDB.override_used == True).count(),
    }

@app.get("/geofence_events")
async def get_geofence_events(hours: int = Query(24, ge=1), device_id: str | None = None):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)
    q = db.query(GeofenceEventDB).filter(GeofenceEventDB.timestamp >= since)
    if device_id:
        q = q.filter(GeofenceEventDB.device_id == device_id)

    events = q.order_by(desc(GeofenceEventDB.timestamp)).all()
    db.close()

    return [{
        "id": e.id,
        "deviceid": e.device_id,
        "geofence_name": e.geofence_name,
        "event_type": e.event_type,
        "timestamp_uae": to_uae(e.timestamp).isoformat(),
    } for e in events]

@app.get("/images")
async def get_recent_images(limit: int = 10):
    """Fetches only alerts that contain images (for the photo gallery)"""
    db = SessionLocal()
    # Filter for alerts where image_path is NOT NULL
    alerts = db.query(AlertDB)\
        .filter(AlertDB.image_path != None)\
        .order_by(desc(AlertDB.timestamp))\
        .limit(limit)\
        .all()
    db.close()
    
    return [{
        "deviceid": a.device_id,
        "alert_type": a.alert_type,
        "image_url": f"/uploads/{a.image_path}",
        "liveness_verified": a.liveness_verified,
        "timestamp_uae": to_uae(a.timestamp).strftime("%H:%M")
    } for a in alerts]
@app.post("/telemetry_batch")
async def telemetry_batch(
    points: List[OfflineTelemetryPoint],
    device_id: Optional[str] = Query(None),
    deviceid: Optional[str] = Query(None),
):
    dev_id = device_id if isinstance(device_id, str) and device_id else (deviceid if isinstance(deviceid, str) and deviceid else "unknown")
    db = SessionLocal()
    inserted = 0

    try:
        for p in points:
            payload = {
                "deviceid": dev_id,
                "latitude": p.lat,
                "longitude": p.lon,
                "timestamp": p.ts,
                "accuracy": p.acc,
                "speed": p.spd,
                "bearing": p.brg,
                "trackingstate": p.st,
                "offline": 1,
                "battery": 0.0,  # offline batches may not know battery
            }

            result = await process_telemetry_payload(payload, db, broadcast=False)
            if result == "stored":
                inserted += 1

        db.commit()

        await broadcast_update({
            "type": "offline_batch_ingested",
            "deviceid": dev_id,
            "inserted": inserted
        })


    finally:
        db.close()

    logger.info(f"📦 Offline batch processed | device={dev_id} points={inserted}")
    return {
        "status": "ok",
        "deviceid": dev_id,
        "inserted": inserted
    }
@app.get("/summary")
async def route_summary(
    device_id: str, 
    since_minutes: int = Query(60, ge=0, le=7*24*60),  # ✅ Changed ge=1 to ge=0
    db: Session = Depends(get_db)
):
    try:
        # Handle "all time" when since_minutes is 0
        if since_minutes == 0:
            cutoff = datetime(1970, 1, 1, tzinfo=UTC)  # Epoch start = all data
        else:
            cutoff = utc_now() - timedelta(minutes=since_minutes)
        
        rows = db.query(TelemetryDB)\
            .filter(
                TelemetryDB.device_id == device_id,
                TelemetryDB.timestamp >= cutoff
            )\
            .order_by(TelemetryDB.timestamp.asc())\
            .all()
        
        if len(rows) < 2:

            return {"deviceid": device_id,
        	"points": len(rows),
        	"distance_m": 0.0,
        	"steps_total": 0,
        	"avg_speed_kmh": 0.0,
        	"offline_ratio_percent": 0.0,
        	"start_time_uae": None,
        	"end_time_uae": None,
        	"warning": "Not enough data"
	    }

        total_dist = 0.0
        offline_points = 0

        for i in range(1, len(rows)):
            total_dist += haversine(
                rows[i - 1].latitude,
                rows[i - 1].longitude,
                rows[i].latitude,
                rows[i].longitude
            )
            if rows[i].offline:
                offline_points += 1

        total_steps = sum(r.steps or 0 for r in rows)

        total_time = (rows[-1].timestamp - rows[0].timestamp).total_seconds()
        avg_speed = (
            (total_dist / 1000) / (total_time / 3600)
            if total_time > 0 else 0.0
        )

        offline_ratio = (offline_points / len(rows)) * 100

        return {
            "deviceid": device_id,
    	    "points": len(rows),
    	    "distance_m": round(total_dist, 2),
    	    "steps_total": total_steps,
    	    "avg_speed_kmh": round(avg_speed, 2),
    	    "offline_ratio_percent": round(offline_ratio, 1),
    	    "start_time_uae": to_uae(rows[0].timestamp).isoformat(),
    	    "end_time_uae": to_uae(rows[-1].timestamp).isoformat(),
        }

    finally:
        db.close()
@app.get("/anomalies")
async def get_anomalies(
    device_id: str,
    hours: int = Query(24, ge=1),
    speed_limit: float = 120.0,
):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)

    rows = db.query(TelemetryDB)\
        .filter(
            TelemetryDB.device_id == device_id,
            TelemetryDB.timestamp >= since
        )\
        .order_by(TelemetryDB.timestamp.asc())\
        .all()

    anomalies = []

    for i in range(1, len(rows)):
        dt = (rows[i].timestamp - rows[i-1].timestamp).total_seconds()
        if dt <= 0:
            continue

        dist = haversine(
            rows[i-1].latitude, rows[i-1].longitude,
            rows[i].latitude, rows[i].longitude
        )

        speed_kmh = (dist / dt) * 3.6

        if speed_kmh > speed_limit:
            anomalies.append({
                "type": "speed_anomaly",
                "speed_kmh": round(speed_kmh, 1),
                "latitude": rows[i].latitude,
                "longitude": rows[i].longitude,
                "timestamp_uae": to_uae(rows[i].timestamp).isoformat()
            })

    db.close()
    return anomalies

@app.get("/stats/battery")
def battery_stats(db: Session = Depends(get_db)):
    rows = db.query(TelemetryDB.battery).order_by(desc(TelemetryDB.timestamp)).limit(100).all()
    if not rows:
        return {"avg": 0, "min": 0, "max": 0}

    values = [r[0] for r in rows if r[0] is not None]
    return {
        "avg": round(sum(values) / len(values), 1),
        "min": min(values),
        "max": max(values),
    }
@app.get("/stats/distance")
def distance_stats(db: Session = Depends(get_db)):
    rows = db.query(TelemetryDB).order_by(TelemetryDB.timestamp.asc()).all()
    total = 0.0
    for i in range(1, len(rows)):
        total += haversine(
            rows[i-1].latitude, rows[i-1].longitude,
            rows[i].latitude, rows[i].longitude
        )
    return {
        "distance_m": round(total, 2),
        "distance_km": round(total / 1000, 2),
    }
@app.get("/stats/states")
def state_distribution(db: Session = Depends(get_db)):
    rows = db.query(TelemetryDB.tracking_state).all()
    result = {}
    for r in rows:
        result[r[0]] = result.get(r[0], 0) + 1
    return result
# ADD these missing endpoints to your FastAPI server:

@app.delete("/geofences/{geofence_id}")
async def delete_geofence(geofence_id: int, db: Session = Depends(get_db)):
    """Delete geofence - missing in your server"""
    geofence = db.query(GeofenceDB).filter(GeofenceDB.id == geofence_id).first()
    if not geofence:
        raise HTTPException(status_code=404, detail="Geofence not found")
    db.delete(geofence)
    db.commit()
    return {"status": "deleted", "id": geofence_id}

@app.put("/incidents/{incident_id}/resolve")
async def resolve_incident(incident_id: int, db: Session = Depends(get_db)):
    """Resolve incident - missing in your server"""
    incident = db.query(IncidentDB).filter(IncidentDB.id == incident_id).first()
    if not incident:
        raise HTTPException(status_code=404, detail="Incident not found")
    incident.resolved = True
    db.commit()
    return {"status": "resolved", "id": incident_id}


@app.post("/security-alert")
async def security_alert_endpoint(alert: SecurityAlert):
    db = SessionLocal()
    try:
        ts_utc = parse_timestamp(alert.timestamp)
        row = SecurityAlertDB(
            device_id=alert.device_id,
            alert_type=alert.alert_type,
            details=alert.details,
            timestamp=ts_utc,
        )
        db.add(row)
        db.commit()
    finally:
        db.close()

    ts_uae = to_uae(ts_utc)
    await broadcast_update({
        "securityalert": True,
        "deviceid": alert.device_id,
        "alerttype": alert.alert_type,
        "details": alert.details,
        "timestampuae": ts_uae.strftime("%H:%M:%S"),
    })
    return {"status": "securityalert_logged"}

@app.get("/security-alerts")
async def get_security_alerts(
    hours: int = Query(24, ge=1),
    device_id: str | None = None
):
    db = SessionLocal()
    since = utc_now() - timedelta(hours=hours)
    q = db.query(SecurityAlertDB).filter(SecurityAlertDB.timestamp >= since)
    if device_id:
        q = q.filter(SecurityAlertDB.device_id == device_id)

    alerts = q.order_by(desc(SecurityAlertDB.timestamp)).limit(100).all()
    db.close()

    return [{
        "id": a.id,
        "deviceid": a.device_id,
        "alert_type": a.alert_type,
        "details": a.details,
        "timestamp_uae": to_uae(a.timestamp).isoformat()
    } for a in alerts]

@app.get("/device/{device_id}/health")
def get_device_health(device_id: str, db: Session = Depends(get_db)):
    last_telemetry = db.query(TelemetryDB).filter(TelemetryDB.device_id == device_id).order_by(desc(TelemetryDB.timestamp)).first()
    if not last_telemetry:
         return {"status": "unknown", "message": "No telemetry found"}

    time_diff = (utc_now() - last_telemetry.timestamp).total_seconds()
    status = "online" if time_diff < 300 else "offline"

    return {
        "deviceid": device_id,
        "status": status,
        "last_seen_seconds_ago": int(time_diff),
        "battery": last_telemetry.battery,
        "last_seen_uae": to_uae(last_telemetry.timestamp).isoformat()
    }
@app.post("/announcements")
async def create_announcement(data: dict, db: Session = Depends(get_db)):
    ann = AnnouncementDB(
        title=data.get("title", "Announcement"),
        message=data["message"],
        priority=data.get("priority", "NORMAL"),
        language=data.get("language", "en"),
        tts=data.get("tts", True),
        vibrate=data.get("vibrate", False),
        raise_alert=data.get("raise_alert", False),
        device_id=data.get("device_id"),  # optional
        expires_at=parse_timestamp(data.get("expires_at"))
            if data.get("expires_at") else None
    )

    db.add(ann)
    db.commit()
    db.refresh(ann)

    # 🔴 push instantly to connected dashboards
    await broadcast_update({
        "type": "announcement_created",
        "id": ann.id,
        "title": ann.title,
        "message": ann.message,
        "priority": ann.priority
    })

    logger.warning(f"📣 Announcement created: {ann.title}")
    return {"status": "created", "id": ann.id}

@app.get("/check-announcements")
async def check_announcements(
    device_id: Optional[str] = Query(None),
    deviceid: Optional[str] = Query(None),
    db: Session = Depends(get_db)
):
    dev_id = device_id if isinstance(device_id, str) and device_id else (deviceid if isinstance(deviceid, str) and deviceid else "")
    now = utc_now()

    ann = (
    db.query(AnnouncementDB)
    .outerjoin(
        AnnouncementReceiptDB,
        (AnnouncementReceiptDB.announcement_id == AnnouncementDB.id) &
        (AnnouncementReceiptDB.device_id == dev_id)
    )
    .filter(
        AnnouncementReceiptDB.id == None,
        or_(
            AnnouncementDB.device_id == None,
            AnnouncementDB.device_id == dev_id
        ),
        or_(
            AnnouncementDB.expires_at == None,
            AnnouncementDB.expires_at > now
        )
    )
    .order_by(AnnouncementDB.created_at.asc())
    .first()
)



    if not ann:
        return {"has_announcement": False}

    receipt = (
        db.query(AnnouncementReceiptDB)
        .filter_by(
            announcement_id=ann.id,
            device_id=dev_id
        )
        .first()
    )

    if not receipt:
        receipt = AnnouncementReceiptDB(
            announcement_id=ann.id,
            device_id=dev_id,
            delivered_at=utc_now()
        )
        db.add(receipt)
        db.commit()

    return {
        "has_announcement": True,
        "id": ann.id,
        "title": ann.title,
        "message": ann.message,
        "priority": ann.priority,
        "tts": ann.tts,
        "vibrate": ann.vibrate,
        "raise_alert": ann.raise_alert,
        "language": ann.language,
    }

@app.get("/announcements")
def list_announcements(
    hours: int = Query(24, ge=1),
    db: Session = Depends(get_db)
):
    since = utc_now() - timedelta(hours=hours)
    anns = db.query(AnnouncementDB)\
        .filter(AnnouncementDB.created_at >= since)\
        .order_by(desc(AnnouncementDB.created_at))\
        .all()

    return [{
    "id": a.id,
    "title": a.title,
    "message": a.message,
    "priority": a.priority,
    "deviceid": a.device_id,
    "created_at_uae": to_uae(a.created_at).isoformat()
} for a in anns]

@app.post("/announcements/{announcement_id}/ack")
async def acknowledge_announcement(
    announcement_id: int,
    payload: AnnouncementAck,
    db: Session = Depends(get_db)
):
    receipt = (
        db.query(AnnouncementReceiptDB)
        .filter_by(
            announcement_id=announcement_id,
            device_id=payload.device_id
        )
        .first()
    )

    if not receipt:
        raise HTTPException(404, "Receipt not found (not delivered yet)")

    if receipt.acked_at:
        return {"status": "already_acked"}

    receipt.acked_at = utc_now()
    receipt.ack_status = payload.status

    db.commit()

    await broadcast_update({
        "type": "announcement_acked",
        "id": announcement_id,
        "deviceid": payload.device_id,
        "status": payload.status,
        "timestamp_uae": to_uae(receipt.acked_at).strftime("%H:%M:%S")
    })

    return {"status": "acknowledged"}

@app.post("/announcements/{announcement_id}/retry")
async def retry_announcement(
    announcement_id: int,
    device_id: str,
    db: Session = Depends(get_db)
):
    receipt = db.query(AnnouncementReceiptDB).filter_by(
        announcement_id=announcement_id,
        device_id=device_id
    ).first()

    if not receipt:
        raise HTTPException(404, "Receipt not found")

    if receipt.retry_count >= 3:
        raise HTTPException(400, "Retry limit reached")

    receipt.retry_count += 1
    receipt.acked_at = None
    receipt.ack_status = None

    db.commit()

    return {"status": "retried", "retry_count": receipt.retry_count}

logger = logging.getLogger("watchmen")

@app.middleware("http")
async def request_timer(request, call_next):
    start = time.time()
    response = await call_next(request)
    duration = time.time() - start
    if duration > 5:
        logger.warning(f"Slow request {request.url.path}: {duration:.2f}s")
    return response


# ============================================================
# WEBSOCKET
# ============================================================
# -------------------- WEBSOCKET MANAGER --------------------
class ConnectionManager:
    def __init__(self):
        # device_id -> WebSocket
        self.active_connections: Dict[str, WebSocket] = {}
        # dashboard_id -> WebSocket
        self.dashboard_connections: List[WebSocket] = []

    def count(self) -> int:
        return len(self.active_connections) + len(self.dashboard_connections)

    async def connect(self, websocket: WebSocket, client_type: str, device_id: str = None):
        try:
            await websocket.accept()
            logger.info(f"✅ WebSocket accepted | type={client_type} | device_id={device_id}")
        except Exception as e:
            logger.error(f"❌ Failed to accept WebSocket: {e}", exc_info=True)
            raise
        
        if client_type == "device" and device_id:
            logger.info(f"🔌 DEVICE CONNECTED: {device_id} | Current active devices: {list(self.active_connections.keys())}")
            # If device already connected, close old one (force single session)
            if device_id in self.active_connections:
                logger.warning(f"⚠️ Device {device_id} already connected, closing old connection")
                try:
                    old_ws = self.active_connections[device_id]
                    await old_ws.close(code=status.WS_1000_NORMAL_CLOSURE, reason="Replaced by new connection")
                except Exception as e:
                    logger.warning(f"⚠️ Error closing old connection: {e}")
            
            self.active_connections[device_id] = websocket
            logger.info(f"✅ Device {device_id} registered in active_connections | Total devices: {len(self.active_connections)}")
            
            # Broadcast device connection to all dashboards
            try:
                await self.broadcast({
                    "type": "device_connected",
                    "device_id": device_id,
                    "timestamp": utc_now().isoformat(),
                    "active_devices": list(self.active_connections.keys())
                })
                logger.info(f"📢 Broadcasted device_connected event for {device_id}")
            except Exception as e:
                logger.error(f"❌ Failed to broadcast device_connected: {e}")
        elif client_type == "dashboard":
            logger.info(f"💻 DASHBOARD CONNECTED | Total dashboards: {len(self.dashboard_connections) + 1}")
            self.dashboard_connections.append(websocket)
        else:
            logger.warning(f"⚠️ Unknown client_type: {client_type}")

    def disconnect(self, websocket: WebSocket, client_type: str, device_id: str = None):
        if client_type == "device" and device_id:
            logger.info(f"🔌 DEVICE DISCONNECTED: {device_id} | Before: {list(self.active_connections.keys())}")
            was_registered = device_id in self.active_connections
            if was_registered:
                # Only remove if it's the same websocket instance
                if self.active_connections.get(device_id) == websocket:
                    del self.active_connections[device_id]
                    logger.info(f"✅ Device {device_id} removed from active_connections | Remaining: {list(self.active_connections.keys())}")
                else:
                    logger.warning(f"⚠️ Device {device_id} websocket mismatch, not removing")
            else:
                logger.warning(f"⚠️ Device {device_id} was not in active_connections")
            
            # Broadcast device disconnection to all dashboards
            if was_registered:
                import asyncio
                try:
                    asyncio.create_task(self.broadcast({
                        "type": "device_disconnected",
                        "device_id": device_id,
                        "timestamp": utc_now().isoformat(),
                        "active_devices": list(self.active_connections.keys())
                    }))
                except Exception as e:
                    logger.error(f"❌ Failed to broadcast device_disconnected: {e}")
        elif client_type == "dashboard":
            if websocket in self.dashboard_connections:
                self.dashboard_connections.remove(websocket)
                logger.info(f"💻 Dashboard disconnected | Remaining dashboards: {len(self.dashboard_connections)}")

    async def broadcast(self, message: dict):
        """Send message to all dashboards"""
        dead_connections = []
        for connection in self.dashboard_connections:
            try:
                await connection.send_json(message)
            except Exception as e:
                logger.error(f"WebSocket send error: {e}")
                dead_connections.append(connection)
        
        for dead in dead_connections:
            if dead in self.dashboard_connections:
                self.dashboard_connections.remove(dead)

    async def send_to_device(self, device_id: str, message: dict) -> bool:
        """Send specific command/message to a device"""
        if device_id in self.active_connections:
            try:
                await self.active_connections[device_id].send_json(message)
                return True
            except Exception as e:
                logger.error(f"Failed to send to device {device_id}: {e}")
                del self.active_connections[device_id]
                return False
        return False



# ✅ NEW: Get list of connected devices
@app.get("/devices/connected")
async def get_connected_devices():
    """Returns list of currently connected device IDs via WebSocket"""
    return {
        "connected_devices": list(manager.active_connections.keys()),
        "count": len(manager.active_connections),
        "timestamp": to_uae(utc_now()).isoformat()
    }

# ✅ NEW: Diagnostic endpoint to check specific device connection state
@app.get("/device/{device_id}/connection-status")
async def check_device_connection_status(device_id: str):
    """Check if a specific device is connected via WebSocket"""
    is_connected = device_id in manager.active_connections
    websocket_obj = manager.active_connections.get(device_id)
    
    return {
        "device_id": device_id,
        "is_connected": is_connected,
        "has_websocket_object": websocket_obj is not None,
        "all_connected_devices": list(manager.active_connections.keys()),
        "total_connected": len(manager.active_connections),
        "timestamp": to_uae(utc_now()).isoformat()
    }

manager = ConnectionManager()

# ============================================================
# ⚙️ DEVICE REMOTE CONTROL (Command/Message)
# ============================================================

@app.post("/device/{device_id}/command")
async def send_remote_command(device_id: str, data: CommandRequest):
    """Dashboard calls this to trigger an action on the device via WS. Body: {"command": "START_SIREN"|"GET_LOCATION"|"WIPE_CACHE"|"RESTART_SERVICE"}"""
    command = data.command

    # Log current connection state for debugging
    connected_devices = list(manager.active_connections.keys())
    logger.info(f"📤 Command request for {device_id} | Active devices: {connected_devices} | Command: {command}")

    success = await manager.send_to_device(device_id, {
        "type": "command",  # Matched to TrackingService.kt
        "command": command,
        "timestamp_uae": to_uae(utc_now()).isoformat()
    })

    if success:
        logger.info(f"✅ Command '{command}' sent successfully to {device_id}")
        return {"status": "sent", "device_id": device_id}
    else:
        connected = list(manager.active_connections.keys())
        logger.warning(f"❌ Failed to send command to {device_id}. Active devices: {connected}")
        logger.warning(f"   Device {device_id} not found in active_connections. Available: {connected}")
        raise HTTPException(503, f"Device {device_id} is offline or disconnected. (Connected: {connected})")


@app.post("/device/{device_id}/message")
async def send_remote_message(device_id: str, data: dict):
    """Dashboard calls this to send a chat message to the device"""
    message = data.get("message")
    if not message:
        raise HTTPException(400, "Missing message")

    try:
        success = await manager.send_to_device(device_id, {
            "type": "message",  # Matched to TrackingService.kt
            "message": message,
            "urgent": data.get("urgent", True),
            "timestamp_uae": to_uae(utc_now()).isoformat()
        })

        if success:
            logger.info(f"💬 Message sent to {device_id}: {message[:20]}...")
            return {"status": "sent", "device_id": device_id}
        else:
            logger.warning(f"❌ Failed to send message to {device_id} (offline)")
            raise HTTPException(503, "Device is offline")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error in send_remote_message: {e}")
        raise HTTPException(500, str(e))


# ✅ Helper for backward compatibility with existing code
async def broadcast_update(data: dict):
    await manager.broadcast(data)

@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    # Extract and log connection parameters
    client_type = ws.query_params.get("type", "dashboard") # Default to dashboard if missing
    device_id = ws.query_params.get("deviceid")
    
    # Log incoming connection attempt
    logger.info(f"🔗 WebSocket connection attempt | type={client_type} | device_id={device_id} | query_params={dict(ws.query_params)}")
    
    # If device_id is present but type is missing, assume it's a device (legacy check)
    if device_id and "type" not in ws.query_params:
         client_type = "device"
         logger.info(f"🔧 Auto-detected device type from device_id presence")

    # Validate client type
    if client_type not in ["device", "dashboard"]:
        logger.warning(f"❌ Invalid client type: {client_type}")
        await ws.close(code=status.WS_1008_POLICY_VIOLATION, reason="Invalid client type")
        return

    # Validate device_id is present for device connections
    if client_type == "device" and not device_id:
        logger.error("❌ Device connection missing device_id parameter")
        await ws.close(code=status.WS_1008_POLICY_VIOLATION, reason="Missing device_id")
        return

    # Connect and register
    try:
        await manager.connect(ws, client_type, device_id)
        logger.info(f"✅ WebSocket connection registered | type={client_type} | device_id={device_id} | active_devices={len(manager.active_connections)}")
    except Exception as e:
        logger.error(f"❌ Failed to register WebSocket connection: {e}", exc_info=True)
        await ws.close(code=status.WS_1011_INTERNAL_ERROR, reason=f"Registration failed: {str(e)}")
        return

    try:
        while True:
            # Keep connection alive, devices might send data, dashboards just listen
            data = await ws.receive_text()
            if client_type == "device":
                # Handle incoming messages from device (e.g. ACKs, handshake, keepalive)
                try:
                    msg = json.loads(data)
                    msg_type = msg.get('type', 'unknown')
                    logger.debug(f"📨 Message from device {device_id}: {msg_type}")
                    
                    if msg_type == "device_handshake":
                        # Confirm handshake received
                        logger.info(f"✅ Handshake received from device {device_id}")
                        await manager.broadcast({
                            "type": "device_handshake_ack",
                            "device_id": device_id,
                            "timestamp": utc_now().isoformat()
                        })
                    elif msg_type == "keepalive" or msg_type == "ping":
                        # Respond to keepalive/ping
                        try:
                            await ws.send_json({"type": "pong", "timestamp": utc_now().isoformat()})
                        except:
                            pass
                    elif msg_type in ["command_ack", "message_ack", "device_message"]:
                        await manager.broadcast(msg)
                except json.JSONDecodeError as e:
                    logger.warning(f"⚠️ Invalid JSON from device {device_id}: {data[:100]}")
                except Exception as e:
                    logger.error(f"❌ Error processing device message: {e}")
    except WebSocketDisconnect:
        logger.info(f"🔌 WebSocket disconnect (normal) | type={client_type} | device_id={device_id}")
        manager.disconnect(ws, client_type, device_id)
    except Exception as e:
        logger.error(f"❌ WS error for {client_type} {device_id or ''}: {e}", exc_info=True)
        manager.disconnect(ws, client_type, device_id)
    finally:
        logger.info(f"🔌 WS disconnected | device={device_id} | active_devices={len(manager.active_connections)} | total_conns={manager.count()}")
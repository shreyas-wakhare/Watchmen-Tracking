import os
import sys
import asyncio

# Ensure backend directory is in path
sys.path.insert(0, os.path.dirname(__file__))

# Set dummy DATABASE_URL for local test import if needed
os.environ.setdefault("DATABASE_URL", "sqlite:///./watchmen_test.db")

from main import app, get_discovery_config, health_check

async def run_tests():
    # Test /api/v1/config directly
    config_response = await get_discovery_config()
    assert config_response["status"] == "online"
    assert config_response["service"] == "Watchmen Tracker Backend"
    assert config_response["version"] == "4.2"
    assert config_response["ws_path"] == "/ws"
    assert "timestamp_uae" in config_response
    print("[SUCCESS] /api/v1/config endpoint test passed")

    # Test /health directly
    health_response = await health_check()
    assert health_response["status"] == "healthy"
    assert "telemetry_count" in health_response
    assert "active_connections" in health_response
    print("[SUCCESS] /health endpoint test passed")

if __name__ == "__main__":
    asyncio.run(run_tests())
    print("ALL BACKEND DISCOVERY AND HEALTH TESTS PASSED SUCCESSFULLY!")

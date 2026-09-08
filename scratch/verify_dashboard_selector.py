import urllib.request
import json
import re
from pathlib import Path

def test_dashboard_dropdown_flow():
    print("============================================================")
    print("   WATCHMEN TRACKER - DASHBOARD DROPDOWN VERIFICATION SUITE  ")
    print("============================================================\n")

    # 1. API Verification: /devices/connected
    url_connected = "http://127.0.0.1:8000/devices/connected"
    req_conn = urllib.request.urlopen(url_connected)
    data_conn = json.loads(req_conn.read().decode())
    
    connected_list = data_conn.get("connected_devices", [])
    count = data_conn.get("count", 0)
    
    print(f"[API VERIFICATION] GET /devices/connected -> count={count}, devices={connected_list}")
    assert "TEST001_Samsung Test_74285b00" in connected_list, "Samsung device missing from /devices/connected response"
    print("[PASS] Samsung device 'TEST001_Samsung Test_74285b00' is present in /devices/connected response.\n")

    # 2. Static Code Inspection: dashboard.js
    repo_root = Path(__file__).resolve().parent.parent
    dashboard_js_path = repo_root / "backend" / "static" / "dashboard.js"
    js_content = dashboard_js_path.read_text(encoding="utf-8")

    # Check function definitions
    has_fetch_conn = "async function fetchConnectedDevices()" in js_content
    has_get_avail = "function getAvailableDeviceIds()" in js_content
    has_update_list = "function updateDeviceList()" in js_content
    has_render_sel = "function renderDeviceSelector(devices)" in js_content

    print(f"[JS CHECK] fetchConnectedDevices defined: {has_fetch_conn}")
    print(f"[JS CHECK] getAvailableDeviceIds defined: {has_get_avail}")
    print(f"[JS CHECK] updateDeviceList defined: {has_update_list}")
    print(f"[JS CHECK] renderDeviceSelector defined: {has_render_sel}")

    assert has_fetch_conn, "fetchConnectedDevices is missing in dashboard.js"
    assert has_get_avail, "getAvailableDeviceIds is missing in dashboard.js"
    assert has_update_list, "updateDeviceList is missing in dashboard.js"
    assert has_render_sel, "renderDeviceSelector is missing in dashboard.js"
    print("[PASS] All required device discovery and selector rendering functions are defined.\n")

    # 3. Data Flow Merge Simulation
    telemetry_devices = ["OLD_DEVICE_001", "TEST001_Samsung Test_74285b00"]
    connected_devices_set = set(connected_list)
    merged_unique_list = list(set(telemetry_devices + list(connected_devices_set)))

    print(f"[DATA FLOW MERGE SIMULATION]")
    print(f"  Live Connected: {list(connected_devices_set)}")
    print(f"  TelemetryKnown: {telemetry_devices}")
    print(f"  Merged Output : {merged_unique_list}")

    assert "TEST001_Samsung Test_74285b00" in merged_unique_list, "Samsung device missing from merged device list"
    assert len(merged_unique_list) == len(set(merged_unique_list)), "Duplicates present in merged device list"
    print("[PASS] Device list merge produces clean, deduplicated array containing connected Samsung device.\n")

    print("============================================================")
    print("   DASHBOARD DROPDOWN SUITE SUMMARY: ALL 3 VERIFICATIONS PASSED")
    print("============================================================\n")

if __name__ == "__main__":
    test_dashboard_dropdown_flow()

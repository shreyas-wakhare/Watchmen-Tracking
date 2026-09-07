#!/usr/bin/env python3
"""
WebSocket Connection Tester for Watchmen Backend
This script tests the WebSocket endpoint to diagnose connection issues.
"""

import asyncio
import websockets
import json
import sys
from datetime import datetime

import os

BACKEND_URL = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("BACKEND_URL", "wss://watchmen-backend.onrender.com")
TEST_DEVICE_ID = "test_diagnostic_device"

async def test_device_connection():
    """Test device WebSocket connection"""
    uri = f"{BACKEND_URL}/ws?type=device&deviceid={TEST_DEVICE_ID}"
    
    print("=" * 60)
    print("WEBSOCKET CONNECTION TEST - DEVICE MODE")
    print("=" * 60)
    print(f"URL: {uri}")
    print(f"Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print()
    
    try:
        print("🔗 Attempting to connect...")
        async with websockets.connect(uri) as websocket:
            print("✅ CONNECTION SUCCESSFUL!")
            print(f"   - Connection established to {BACKEND_URL}")
            print(f"   - Device ID: {TEST_DEVICE_ID}")
            print()
            
            # Send a test message
            test_message = {
                "type": "device_message",
                "message": "Test message from diagnostic script",
                "deviceid": TEST_DEVICE_ID,
                "timestamp": int(datetime.now().timestamp() * 1000)
            }
            
            print("📤 Sending test message:")
            print(f"   {json.dumps(test_message, indent=2)}")
            await websocket.send(json.dumps(test_message))
            print("   ✅ Message sent successfully")
            print()
            
            # Try to receive a response (with timeout)
            print("📥 Waiting for response (5 seconds)...")
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                print("✅ RECEIVED RESPONSE:")
                print(f"   {response}")
            except asyncio.TimeoutError:
                print("   ⏱️ No response received (timeout)")
                print("   ℹ️ This is normal - backend may not send immediate response")
            print()
            
            # Keep connection alive
            print("⏳ Keeping connection alive for 10 seconds...")
            print("   ℹ️ Check your backend logs for:")
            print("   - '🔌 DEVICE CONNECTED: test_diagnostic_device'")
            print("   - '📱 Active devices: ['test_diagnostic_device']'")
            print()
            
            await asyncio.sleep(10)
            
            print("✅ TEST COMPLETED SUCCESSFULLY")
            print()
            print("SUMMARY:")
            print("  - WebSocket connection: ✅ WORKING")
            print("  - Message sending: ✅ WORKING")
            print("  - Connection stability: ✅ STABLE (10 seconds)")
            print()
            print("👉 If backend logs show device connected, the issue is likely:")
            print("   1. SSL certificate problem on Android device")
            print("   2. Network/firewall blocking WebSocket on mobile")
            print("   3. Android app WebSocket client configuration")
            
    except websockets.exceptions.InvalidStatusCode as e:
        print(f"❌ CONNECTION FAILED - Invalid Status Code")
        print(f"   Status: {e.status_code}")
        print(f"   Message: {e}")
        print()
        print("POSSIBLE CAUSES:")
        print("  - Backend WebSocket endpoint not properly configured")
        print("  - Authentication/authorization issue")
        print("  - Backend returned HTTP error instead of WebSocket upgrade")
        
    except websockets.exceptions.InvalidURI as e:
        print(f"❌ CONNECTION FAILED - Invalid URI")
        print(f"   Error: {e}")
        print()
        print("POSSIBLE CAUSES:")
        print("  - Incorrect backend URL")
        print("  - Malformed WebSocket URL")
        
    except websockets.exceptions.WebSocketException as e:
        print(f"❌ CONNECTION FAILED - WebSocket Error")
        print(f"   Error: {e}")
        print()
        print("POSSIBLE CAUSES:")
        print("  - Backend not accepting WebSocket connections")
        print("  - Network connectivity issue")
        print("  - Firewall blocking WebSocket traffic")
        
    except ssl.SSLError as e:
        print(f"❌ CONNECTION FAILED - SSL Error")
        print(f"   Error: {e}")
        print()
        print("POSSIBLE CAUSES:")
        print("  - SSL certificate invalid or expired")
        print("  - Certificate chain incomplete")
        print("  - SSL/TLS version mismatch")
        print()
        print("👉 THIS IS LIKELY YOUR ISSUE IF:")
        print("   - HTTP telemetry works but WebSocket doesn't")
        print("   - Android logs show SSL-related errors")
        
    except Exception as e:
        print(f"❌ CONNECTION FAILED - Unexpected Error")
        print(f"   Error Type: {type(e).__name__}")
        print(f"   Error: {e}")
        print()
        import traceback
        print("FULL TRACEBACK:")
        traceback.print_exc()

async def test_dashboard_connection():
    """Test dashboard WebSocket connection"""
    uri = f"{BACKEND_URL}/ws?type=dashboard"
    
    print("=" * 60)
    print("WEBSOCKET CONNECTION TEST - DASHBOARD MODE")
    print("=" * 60)
    print(f"URL: {uri}")
    print(f"Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print()
    
    try:
        print("🔗 Attempting to connect...")
        async with websockets.connect(uri) as websocket:
            print("✅ CONNECTION SUCCESSFUL!")
            print(f"   - Dashboard connected to {BACKEND_URL}")
            print()
            
            print("📥 Listening for broadcasts (30 seconds)...")
            print("   ℹ️ Waiting for device connection events...")
            print()
            
            # Listen for broadcasts for 30 seconds
            try:
                while True:
                    message = await asyncio.wait_for(websocket.recv(), timeout=30.0)
                    print(f"📨 Received broadcast:")
                    try:
                        data = json.loads(message)
                        print(f"   Type: {data.get('type')}")
                        if data.get('device_id'):
                            print(f"   Device: {data.get('device_id')}")
                        print(f"   Full data: {json.dumps(data, indent=2)}")
                    except:
                        print(f"   Raw: {message}")
                    print()
            except asyncio.TimeoutError:
                print("   ⏱️ No broadcasts received in 30 seconds")
                print("   ℹ️ This is normal if no devices are connecting")
            
            print("✅ DASHBOARD TEST COMPLETED")
            
    except Exception as e:
        print(f"❌ DASHBOARD CONNECTION FAILED")
        print(f"   Error: {type(e).__name__}: {e}")

async def main():
    """Main test function"""
    print("\n")
    print("╔═══════════════════════════════════════════════════════════╗")
    print("║                                                           ║")
    print("║        WATCHMEN WEBSOCKET DIAGNOSTIC TOOL                 ║")
    print("║                                                           ║")
    print("╚═══════════════════════════════════════════════════════════╝")
    print()
    
    # Test device connection
    await test_device_connection()
    
    print()
    print("-" * 60)
    print()
    
    # Ask if user wants to test dashboard
    print("Would you like to test dashboard connection? (y/n)")
    print("(This will listen for broadcasts for 30 seconds)")
    
    # For automated testing, skip this
    # response = input("> ").strip().lower()
    # if response == 'y':
    #     await test_dashboard_connection()
    
    print()
    print("=" * 60)
    print("DIAGNOSTIC COMPLETE")
    print("=" * 60)
    print()
    print("NEXT STEPS:")
    print()
    print("If connection SUCCEEDED:")
    print("  1. The backend WebSocket endpoint is working correctly")
    print("  2. The issue is on the Android device side")
    print("  3. Check Android logs for WebSocket errors")
    print("  4. Likely causes: SSL certificate, network, or app config")
    print()
    print("If connection FAILED:")
    print("  1. Check backend is running and accessible")
    print("  2. Verify backend WebSocket endpoint is configured")
    print("  3. Check firewall/proxy settings")
    print("  4. Review backend logs for errors")
    print()
    print("BACKEND LOGS TO CHECK:")
    print("  - '🔌 DEVICE CONNECTED: [device_id]'")
    print("  - '📱 Active devices: [...]'")
    print("  - Any WebSocket-related errors")
    print()

if __name__ == "__main__":
    try:
        import ssl
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n\n⚠️ Test interrupted by user")
        sys.exit(0)
    except ImportError as e:
        print(f"❌ Missing required package: {e}")
        print("\nInstall required packages:")
        print("  pip install websockets")
        sys.exit(1)

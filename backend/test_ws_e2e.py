import asyncio
import json
import uvicorn
import multiprocessing
import websockets
import urllib.request
import time
import os
import sys

# Ensure backend directory is in path
sys.path.insert(0, os.path.dirname(__file__))

def run_server():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    uvicorn.run('main:app', host='127.0.0.1', port=8765, log_level='error')

async def client_test():
    device_id = 'test_samsung_e2e'
    device_uri = f'ws://127.0.0.1:8765/ws?type=device&deviceid={device_id}'
    dash_uri = 'ws://127.0.0.1:8765/ws?type=dashboard'

    async with websockets.connect(dash_uri) as dash_ws:
        print('[PASS] Dashboard connected to WebSocket')
        async with websockets.connect(device_uri) as dev_ws:
            print('[PASS] Samsung Device connected to WebSocket')

            # 1. Send device handshake
            await dev_ws.send(json.dumps({'type': 'device_handshake', 'deviceid': device_id}))
            
            # Read messages from dashboard
            while True:
                msg = json.loads(await dash_ws.recv())
                if msg.get('type') in ['device_connected', 'device_handshake_ack']:
                    print(f"[PASS] Dashboard received event: {msg.get('type')}")
                    if msg.get('type') == 'device_handshake_ack':
                        break

            # 2. Trigger START_SIREN command from dashboard via HTTP endpoint
            req = urllib.request.Request(
                f'http://127.0.0.1:8765/device/{device_id}/command',
                data=json.dumps({'command': 'START_SIREN'}).encode('utf-8'),
                headers={'Content-Type': 'application/json'}
            )
            with urllib.request.urlopen(req) as resp:
                assert resp.status == 200
            print('[PASS] POST /device/{device_id}/command returned HTTP 200')

            # 3. Device receives START_SIREN command
            dev_cmd = json.loads(await dev_ws.recv())
            assert dev_cmd.get('type') == 'command'
            assert dev_cmd.get('command') == 'START_SIREN'
            print('[PASS] Samsung Device received START_SIREN command over WS')

            # 4. Device executes siren and sends command_ack
            ack_payload = {
                'type': 'command_ack',
                'deviceid': device_id,
                'device_id': device_id,
                'command': 'START_SIREN',
                'status': 'success',
                'details': 'Siren and vibration activated',
                'timestamp_uae': '2026-09-07T11:00:00'
            }
            await dev_ws.send(json.dumps(ack_payload))

            # 5. Dashboard receives command_ack broadcast
            dash_ack = json.loads(await dash_ws.recv())
            assert dash_ack.get('type') == 'command_ack'
            assert dash_ack.get('command') == 'START_SIREN'
            assert dash_ack.get('status') == 'success'
            print('[PASS] Dashboard received command_ack broadcast with status=success!')

            # 6. Test STOP_SIREN flow
            req_stop = urllib.request.Request(
                f'http://127.0.0.1:8765/device/{device_id}/command',
                data=json.dumps({'command': 'STOP_SIREN'}).encode('utf-8'),
                headers={'Content-Type': 'application/json'}
            )
            with urllib.request.urlopen(req_stop) as resp:
                assert resp.status == 200
            dev_stop_cmd = json.loads(await dev_ws.recv())
            assert dev_stop_cmd.get('command') == 'STOP_SIREN'
            print('[PASS] Samsung Device received STOP_SIREN command over WS')

            await dev_ws.send(json.dumps({
                'type': 'command_ack',
                'deviceid': device_id,
                'command': 'STOP_SIREN',
                'status': 'success',
                'details': 'Siren stopped'
            }))
            dash_stop_ack = json.loads(await dash_ws.recv())
            assert dash_stop_ack.get('command') == 'STOP_SIREN'
            print('[PASS] Dashboard received STOP_SIREN ACK!')

    print('ALL E2E WEBSOCKET COMMAND & ACK TESTS PASSED 100%!')

import threading

if __name__ == '__main__':
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    config = uvicorn.Config('main:app', host='127.0.0.1', port=8765, log_level='warning')
    server = uvicorn.Server(config)
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    timeout = 15.0
    start = time.time()
    while not getattr(server, 'started', False) and time.time() - start < timeout:
        time.sleep(0.1)
    time.sleep(0.5)
    try:
        asyncio.run(client_test())
    finally:
        server.should_exit = True
        t.join(timeout=2.0)

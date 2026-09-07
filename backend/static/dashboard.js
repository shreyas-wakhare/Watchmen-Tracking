// ============================================================
// WATCHMEN TRACKER - COMPLETE FUNCTIONAL DASHBOARD
// Every feature fully implemented - Zero placeholders
// ============================================================

// -------------------- GLOBAL STATE --------------------
let map = null;
let marker = null;

let currentDeviceId = null;
let ws = null;
let reconnectTimer = null;
let activityChart = null;
let autoFollow = true;
let geofences = [];
let geofenceCircles = [];
let deviceData = {};
const lastStepCount = {};
let lastFollowPanMs = 0;
let connectedDevices = new Set();  // Track which devices are online via WebSocket

let playbackPoints = [];
let bufferedDuringPlayback = [];
let playbackTimer = null;
let isPlaying = false;
let drawnItems = new L.FeatureGroup();
let drawControl = null;
let tempLayer = null;
let incidentMarkers = [];
let reconnectAttempts = 0;
const BACKEND = window.location.origin;
// ================= DEVICE MAP STATE =================
const deviceMarkers = {};     // deviceId → Leaflet marker
const deviceLabels = {};      // deviceId → Leaflet tooltip
const deviceColors = {};      // deviceId → color
const deviceTrailsMap = {};   // deviceId → polyline

// -------------------- CONFIGURATION --------------------
const CONFIG = {
    wsUrl: BACKEND.replace('http', 'ws') + '/ws',
    mapCenter: [25.2048, 55.2708],
    mapZoom: 13,
    maxTrailPoints: 500,
    maxDevices: 50,
    reconnectDelay: 3000,
    chartMaxPoints: 40
};

// -------------------- INITIALIZATION --------------------
window.addEventListener('load', async function () {
    console.log('🚀 Watchmen Tracker initializing...');

    await initMap();
    await initWebSocket();
    await initActivityChart();
    await initEventListeners();

    await loadDevices();
    await fetchConnectedDevices();  // ✅ Get initial connected devices list
    await loadData();
    await loadGeofences();
    await loadAlerts();
    await loadIncidents();
    await loadPhotos();
    await updateAlertBadge();

    if ('Notification' in window && Notification.permission === 'default') {
        Notification.requestPermission();
    }

    setInterval(updateAlertBadge, 60000);
    setInterval(() => loadPhotos(), 90000);
    setInterval(fetchConnectedDevices, 30000);  // ✅ Refresh connected devices every 30s
    setInterval(() => loadGeofenceEvents(), 120000);
    setInterval(cleanupDeviceData, 300000);

    console.log('✅ Watchmen Elite fully initialized!');
});

// -------------------- MAP INITIALIZATION --------------------
function initMap() {
    console.log('Initializing map...');

    map = L.map('map', { zoomControl: false }).setView(CONFIG.mapCenter, CONFIG.mapZoom);

    L.control.zoom({ position: 'topright' }).addTo(map);

    const darkTile = L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png', {
        maxZoom: 20,
        attribution: '© OpenStreetMap, CartoDB'
    });

    const satTile = L.tileLayer('https://{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}', {
        maxZoom: 20,
        subdomains: ['mt0', 'mt1', 'mt2', 'mt3']
    });

    darkTile.addTo(map);
    L.control.layers({ 'Dark': darkTile, 'Satellite': satTile }).addTo(map);



    map.addLayer(drawnItems);

    if (typeof L.Control.Draw !== 'undefined') {
        drawControl = new L.Control.Draw({
            position: 'topright',
            draw: {
                polyline: false,
                marker: false,
                circlemarker: false,
                rectangle: false,
                polygon: false,
                circle: {
                    shapeOptions: {
                        color: '#00f5ff',
                        fillColor: '#00f5ff',
                        fillOpacity: 0.2
                    }
                }
            },
            edit: {
                featureGroup: drawnItems,
                remove: true
            }
        });

        map.on(L.Draw.Event.CREATED, function (e) {
            tempLayer = e.layer;
            drawnItems.addLayer(tempLayer);
            const center = tempLayer.getLatLng();
            const radius = tempLayer.getRadius();
            const radiusInput = document.getElementById('geofenceRadius');
            if (radiusInput) radiusInput.value = Math.round(radius);
            const modal = document.getElementById('createGeofenceModal');
            if (modal) modal.classList.add('active');
            map.removeControl(drawControl);

        });
    }

    console.log('✅ Map initialized');
}
function getLivenessBadge(alert) {
    if (alert.override_used) {
        return `<span class="badge badge-override">OVERRIDE</span>`;
    }
    if (!alert.liveness_verified) {
        return `<span class="badge badge-failed">FAILED</span>`;
    }
    if (alert.spoof_type && alert.spoof_type !== "none") {
        return `<span class="badge badge-spoof">${alert.spoof_type.toUpperCase()}</span>`;
    }
    return `<span class="badge badge-live">LIVE</span>`;
}

function confidencePercent(value) {
    return Math.round((value || 0) * 100);
}
const DEVICE_PALETTE = [
    '#ff3b3b', // red
    '#00e5ff', // cyan
    '#ffc107', // amber
    '#7c4dff', // deep violet
    '#00c853', // green
    '#ff6d00', // orange
    '#2979ff', // blue
    '#d500f9', // magenta
    '#1de9b6', // teal
    '#f50057', // pink
    '#76ff03', // lime
    '#651fff'  // indigo
];

function getDeviceColor(deviceId) {
    if (!deviceColors[deviceId]) {
        let hash = 0;
        for (let i = 0; i < deviceId.length; i++) {
            hash = (hash << 5) - hash + deviceId.charCodeAt(i);
            hash |= 0;
        }
        deviceColors[deviceId] =
            DEVICE_PALETTE[Math.abs(hash) % DEVICE_PALETTE.length];
    }
    return deviceColors[deviceId];
}



function computeLivenessStats(alerts) {
    const stats = {
        live: 0,
        failed: 0,
        spoof: 0,
        override: 0
    };

    alerts.forEach(a => {
        if (a.override_used) {
            stats.override++;
        } else if (a.spoof_type && a.spoof_type !== 'none') {
            stats.spoof++;
        } else if (a.liveness_verified) {
            stats.live++;
        } else {
            stats.failed++;
        }
    });

    return stats;
}

// -------------------- WEBSOCKET CONNECTION --------------------
function initWebSocket() {
    console.log('Connecting to WebSocket:', CONFIG.wsUrl);

    if (ws) {
        ws.close();
    }

    ws = new WebSocket(CONFIG.wsUrl + '?type=dashboard');

    ws.onopen = () => {
        console.log('✅ WebSocket connected');
        showNotification('Connected to Command Center', 'success');
        const statusDot = document.getElementById('connectionStatus');
        const statusText = document.getElementById('connectionText');
        if (statusDot) statusDot.style.background = 'var(--accent-success)';
        if (statusText) statusText.textContent = 'Connected';
        reconnectAttempts = 0;
        clearTimeout(reconnectTimer);
    };

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data)
            handleWebSocketMessage(data)
        } catch (e) {
            console.error('Failed to parse WebSocket message:', e)
        }
    }


    function scheduleReconnect() {
        // 1. Clear any existing timer so we don't have duplicates
        if (reconnectTimer) clearTimeout(reconnectTimer);

        // 2. Increment attempts and calculate backoff
        reconnectAttempts++;
        const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000);

        console.log(`Reconnecting in ${delay}ms...`);

        // 3. Set the new timer
        reconnectTimer = setTimeout(initWebSocket, delay);
    }

    ws.onerror = (error) => {
        console.error('❌ WebSocket error:', error);
        showNotification('Connection Error. Check backend status.', 'danger');

        // UI Update
        const statusDot = document.getElementById('connectionStatus');
        const statusText = document.getElementById('connectionText');
        if (statusDot) statusDot.style.background = 'var(--accent-danger)';
        if (statusText) statusText.textContent = 'Error';

        // In many browsers, onError is followed by onClose, so we can 
        // strictly wait for onClose, OR use the safe helper function here.
        // Using the helper is safe because of the clearTimeout check at the top.
        scheduleReconnect();
    };

    ws.onclose = () => {
        console.warn('⚠️ WebSocket disconnected');
        showNotification('Connection lost. Reconnecting...', 'warning');

        // UI Update
        const statusDot = document.getElementById('connectionStatus');
        const statusText = document.getElementById('connectionText');
        if (statusDot) statusDot.style.background = 'var(--accent-warning)';
        if (statusText) statusText.textContent = 'Reconnecting...';

        scheduleReconnect();
    };
}

// -------------------- WEBSOCKET MESSAGE HANDLER --------------------
function handleWebSocketMessage(data) {
    if (data.lat && data.lon) {
        const point = {
            lat: data.lat,
            lon: data.lon,
            speed: data.speed || 0,
            battery: data.battery || 0,
            steps: data.steps || 0,
            deviceid: data.deviceid,
            timestamp: data.timestampuae || new Date().toISOString(),
            offline: data.offline || 0,
            trackingstate: data.trackingstate || 'UNKNOWN',
            accuracy: data.accuracy || 0,
            altitude: data.altitude || 0,
            bearing: data.bearing || 0
        };

        if (isPlaying) {
            bufferedDuringPlayback.push(point);
        } else {
            if (currentDeviceId && point.deviceid === currentDeviceId) {
                updateActivityChart(point);
            }
        }

        // Handle Chat/Commands
        if (data.type === 'message_ack' || data.type === 'command_ack') {
            console.log("ACK received:", data);
            showNotification(`✅ ACK: ${data.command || 'Message received'}`, 'success');
        }

        if (data.type === 'device_message') {
            appendChatMessage(data.message, 'received');
        }

        if (!deviceData[data.deviceid]) {
            deviceData[data.deviceid] = {};
        }
        Object.assign(deviceData[data.deviceid], point);

        if (currentDeviceId && point.deviceid === currentDeviceId) {
            updateLiveStats(point);
        }


        scheduleDeviceListUpdate();

    }

    if (data.alert) {
        showNotification(`🚨 ${data.alert_type} Alert from ${data.deviceid}`, 'danger');
        loadAlerts();
        updateAlertBadge();

        if (data.latitude && data.longitude) {
            addAlertMarker(data.latitude, data.longitude, data.alert_type);
        }
    }

    if (data.type === 'new_photo_alert') {
        showNotification(`📸 New Photo Alert from ${data.deviceid}`, 'success');
        loadPhotos();
    }

    if (data.security_alert || data.securityalert) {
        showNotification(`⚠️ Security: ${data.alert_type || data.alerttype}`, 'warning');
        loadAlerts();
    }

    if (Array.isArray(data.geofenceevent)) {
        data.geofenceevent.forEach(evt => {
            const action = evt.type.includes('enter') ? 'entered' : 'exited';
            showNotification(
                `🚪 ${evt.deviceid} ${action} ${evt.geofence_name}`,
                'info'
            );
        });
        loadGeofenceEvents();
    }


    if (data.type === 'incident') {
        showNotification(`📝 Incident ${data.incident_id} reported`, 'warning');
        loadIncidents();
    }

    if (data.type === 'checkpoint') {
        showNotification(`📍 Checkpoint: ${data.checkpoint_name}`, 'success');
    }
    if (data.type === 'announcement_created') {
        showNotification(
            `📣 New announcement: ${data.title || 'Announcement'}`,
            data.priority === 'HIGH' ? 'danger' : 'info'
        );
        // refresh list if we are on the announcements view
        const view = document.querySelector('#view-announcements');
        if (view && view.classList.contains('active')) {
            loadAnnouncements();
        }
    }

    if (data.type === 'announcement_acked') {
        const msg = `Announcement #${data.id} ${data.status} by ${data.deviceid}`;
        showNotification(msg, 'success');
        // optional: just refresh the list, or later show per-device stats
        const view = document.querySelector('#view-announcements');
        if (view && view.classList.contains('active')) {
            loadAnnouncements();
        }
    }

    // ✅ NEW: Track device connections
    if (data.type === 'device_connected') {
        connectedDevices.add(data.device_id);
        showNotification(`📱 Device ${data.device_id} connected`, 'success');
        console.log('Device connected:', data.device_id);
    }

    if (data.type === 'device_disconnected') {
        connectedDevices.delete(data.device_id);
        showNotification(`📴 Device ${data.device_id} disconnected`, 'warning');
        console.log('Device disconnected:', data.device_id);
    }
}
function applyDeviceFilter() {
    // Markers + labels
    Object.keys(deviceMarkers).forEach(id => {
        const shouldShow = !currentDeviceId || id === currentDeviceId;

        if (shouldShow) {
            if (!map.hasLayer(deviceMarkers[id])) {
                map.addLayer(deviceMarkers[id]);
            }
        } else {
            if (map.hasLayer(deviceMarkers[id])) {
                map.removeLayer(deviceMarkers[id]);
            }
        }
    });


    // Trails
    Object.keys(deviceTrailsMap).forEach(id => {
        const shouldShow = !currentDeviceId || id === currentDeviceId;
        if (shouldShow) {
            if (!map.hasLayer(deviceTrailsMap[id])) map.addLayer(deviceTrailsMap[id]);
        } else {
            if (map.hasLayer(deviceTrailsMap[id])) map.removeLayer(deviceTrailsMap[id]);
        }
    });
}


// -------------------- LIVE POSITION UPDATE --------------------
function updateLivePosition(point) {
    const deviceId = point.deviceid;
    const color = point.offline ? '#888' : getDeviceColor(deviceId);
    if (currentDeviceId && deviceId !== currentDeviceId) {
        return;
    }
    // ---------- MARKER ----------
    if (!deviceMarkers[deviceId]) {
        const marker = L.divIcon({
            className: 'premium-marker',
            html: `
                <div class="marker-pulse" style="background: ${color}; box-shadow: 0 0 15px ${color}"></div>
                <div class="marker-label">${deviceId}</div>
            `,
            iconSize: [20, 20],
            iconAnchor: [10, 10]
        });

        deviceMarkers[deviceId] = L.marker([point.lat, point.lon], { icon: marker }).addTo(map);

        // Tooltip for detailed info
        deviceMarkers[deviceId].bindTooltip(
            `<strong>${deviceId}</strong><br>State: ${point.trackingstate || 'N/A'}<br>Battery: ${point.battery}%`,
            { direction: 'top', offset: [0, -15] }
        );
    } else {
        deviceMarkers[deviceId].setLatLng([point.lat, point.lon]);
        deviceMarkers[deviceId].setIcon(L.divIcon({
            className: 'premium-marker',
            html: `
                <div class="marker-pulse" style="background: ${color}; box-shadow: 0 0 15px ${color}"></div>
                <div class="marker-label">${deviceId}</div>
            `,
            iconSize: [20, 20],
            iconAnchor: [10, 10]
        }));
    }

    // ---------- TRAIL ----------
    const toggleTrail = document.getElementById('toggleTrail');
    if (toggleTrail && toggleTrail.checked) {
        if (!deviceTrailsMap[deviceId]) {
            deviceTrailsMap[deviceId] = L.polyline([], {
                color: color,
                weight: 3,
                opacity: 0.7
            }).addTo(map);
        }
        deviceTrailsMap[deviceId].addLatLng([point.lat, point.lon]);
    }

    // ---------- FOLLOW MODE ----------
    const toggleFollow = document.getElementById('toggleFollow');
    if (
        toggleFollow &&
        toggleFollow.checked &&
        (!currentDeviceId || currentDeviceId === deviceId)
    ) {
        const now = Date.now();
        if (now - lastFollowPanMs > 700) { // pan at most ~1.4x/sec
            lastFollowPanMs = now;
            map.panTo([point.lat, point.lon], { animate: true, duration: 0.25 });
        }
    }


    // ---------- STATS ----------

    applyDeviceFilter();

}


// -------------------- UPDATE LIVE STATS --------------------
function updateLiveStats(data) {
    const batteryEl = document.getElementById('battery');
    if (batteryEl) {
        batteryEl.textContent = data.battery != null ? `${data.battery}%` : '--%';
    }

    const stepsEl = document.getElementById('steps');
    if (stepsEl) {
        stepsEl.textContent = data.steps ?? '--';
    }

    const speedEl = document.getElementById('speed');
    if (speedEl) {
        const speedVal = data.speed != null ? data.speed.toFixed(1) : '--';
        speedEl.textContent = speedVal !== '--' ? `${speedVal} m/s` : '--';
    }

    const timeEl = document.getElementById('time');
    if (timeEl) {
        timeEl.textContent = formatTime(data.timestamp);
    }

    const deviceIdEl = document.getElementById('deviceId');
    if (deviceIdEl) {
        deviceIdEl.textContent = data.deviceid ?? '--';
    }

    const accuracyEl = document.getElementById('accuracy');
    if (accuracyEl) {
        accuracyEl.textContent = data.accuracy != null ? `${data.accuracy.toFixed(1)} m` : '-- m';
    }

    const altitudeEl = document.getElementById('altitude');
    if (altitudeEl) {
        altitudeEl.textContent = data.altitude != null ? `${data.altitude.toFixed(0)} m` : '-- m';
    }

    const bearingEl = document.getElementById('bearing');
    if (bearingEl) {
        bearingEl.textContent = data.bearing != null ? `${data.bearing.toFixed(0)}°` : '--°';
    }

    const trackingEl = document.getElementById('trackingState');
    if (trackingEl) {
        trackingEl.textContent = data.trackingstate ?? '--';
    }

    const offlineStatus = document.getElementById('offlineStatus');
    if (offlineStatus) {
        if (data.offline) {
            offlineStatus.innerHTML = '<i class="fas fa-wifi-slash" style="color: var(--accent-danger)"></i> Offline';
        } else {
            offlineStatus.innerHTML = '<i class="fas fa-wifi" style="color: var(--accent-success)"></i> Online';
        }
    }
}

// -------------------- ACTIVITY CHART --------------------
function initActivityChart() {
    const canvas = document.getElementById('activityChart');
    if (!canvas) {
        console.warn('Activity chart canvas not found');
        return;
    }

    const ctx = canvas.getContext('2d');

    activityChart = new Chart(ctx, {
        data: {
            labels: [],
            datasets: [
                // SPEED — primary signal
                {
                    type: 'line',
                    label: 'Speed (m/s)',
                    data: [],
                    borderColor: '#00f5ff',
                    backgroundColor: 'rgba(0, 245, 255, 0.15)',
                    borderWidth: 3,
                    tension: 0.4,
                    fill: true,
                    pointRadius: 0,
                    pointHoverRadius: 6,
                    yAxisID: 'y'
                },

                // ALTITUDE — vertical movement
                {
                    type: 'line',
                    label: 'Altitude (m)',
                    data: [],
                    borderColor: '#7c3aed',
                    backgroundColor: 'rgba(124, 58, 237, 0.05)',
                    borderWidth: 2,
                    borderDash: [5, 5],
                    tension: 0.4,
                    fill: false,
                    pointRadius: 0,
                    yAxisID: 'yGrid' // Using a secondary axis or same
                },

                // BATTERY — health
                {
                    type: 'line',
                    label: 'Battery (%)',
                    data: [],
                    borderColor: '#10b981',
                    backgroundColor: 'rgba(16, 185, 129, 0.1)',
                    borderWidth: 2,
                    tension: 0.3,
                    fill: false,
                    pointRadius: 0,
                    yAxisID: 'y1'
                },

                // ACCURACY — GPS reliability
                {
                    type: 'line',
                    label: 'Accuracy (m)',
                    data: [],
                    borderColor: 'rgba(239, 68, 68, 0.5)',
                    backgroundColor: 'rgba(239, 68, 68, 0.03)',
                    borderWidth: 1,
                    tension: 0.4,
                    fill: true,
                    pointRadius: 0,
                    yAxisID: 'yAccuracy'
                }
            ]
        },

        options: {
            responsive: true,
            maintainAspectRatio: false,

            plugins: {
                legend: {
                    display: true,
                    position: 'top',
                    labels: {
                        color: '#94a3b8',
                        font: { size: 11 },
                        usePointStyle: true,
                        padding: 16
                    }
                },

                tooltip: {
                    mode: 'index',
                    intersect: false,
                    backgroundColor: 'rgba(15, 23, 42, 0.95)',
                    titleColor: '#e5e7eb',
                    bodyColor: '#cbd5f5',
                    borderColor: 'rgba(255,255,255,0.08)',
                    borderWidth: 1,
                    padding: 10,
                    callbacks: {
                        label: ctx => {
                            const label = ctx.dataset.label;
                            const v = ctx.raw;

                            if (v == null) return `${label}: --`;
                            if (label.includes('Speed')) return `Speed: ${v.toFixed(2)} m/s`;
                            if (label.includes('Battery')) return `Battery: ${v.toFixed(0)}%`;
                            if (label.includes('Steps')) return `Steps: ${v}`;
                            return `${label}: ${v}`;
                        }
                    }
                }
            },

            interaction: {
                mode: 'index',
                intersect: false,
                axis: 'x'
            },

            scales: {
                x: {
                    grid: {
                        color: 'rgba(255,255,255,0.04)',
                        drawBorder: false
                    },
                    ticks: {
                        color: '#64748b',
                        maxTicksLimit: 8,
                        font: { size: 10 }
                    }
                },

                // SPEED
                y: {
                    position: 'left',
                    title: {
                        display: true,
                        text: 'Speed (m/s)',
                        color: '#64748b',
                        font: { size: 11, weight: '500' }
                    },
                    grid: {
                        color: 'rgba(255,255,255,0.06)',
                        drawBorder: false
                    },
                    ticks: {
                        color: '#64748b',
                        font: { size: 10 }
                    }
                },

                // BATTERY
                y1: {
                    position: 'right',
                    min: 0,
                    max: 100,
                    title: {
                        display: true,
                        text: 'Battery (%)',
                        color: '#64748b',
                        font: { size: 11, weight: '500' }
                    },
                    grid: { drawOnChartArea: false },
                    ticks: {
                        color: '#64748b',
                        callback: v => `${v}%`
                    }
                },

                // ALTITUDE / OTHER
                yGrid: {
                    position: 'right',
                    display: false,
                    grid: { drawOnChartArea: false }
                },

                // ACCURACY
                yAccuracy: {
                    position: 'right',
                    min: 0,
                    max: 100,
                    display: false,
                    grid: { drawOnChartArea: false }
                },

                // STEPS
                y2: {
                    position: 'right',
                    offset: true,
                    title: {
                        display: true,
                        text: 'Steps / interval',
                        color: '#64748b',
                        font: { size: 11, weight: '500' }
                    },
                    grid: { drawOnChartArea: false },
                    ticks: { color: '#64748b' }
                }
            }
        }
    });

    console.log('✅ Activity chart initialized');
}


function updateActivityChart(data) {
    if (!activityChart) return;

    // ❗ Chart is single-device only
    if (!currentDeviceId || data.deviceid !== currentDeviceId) return;

    const time = formatTime(data.timestamp);

    // ----- STEP DELTA (IMPORTANT) -----
    const prevSteps = lastStepCount[data.deviceid] ?? data.steps ?? 0;
    const currentSteps = data.steps ?? prevSteps;
    const deltaSteps = Math.max(0, currentSteps - prevSteps);
    lastStepCount[data.deviceid] = currentSteps;

    // ----- PUSH DATA -----
    activityChart.data.labels.push(time);

    activityChart.data.datasets[0].data.push(
        Number.isFinite(data.speed) ? data.speed : null
    );

    activityChart.data.datasets[1].data.push(
        Number.isFinite(data.altitude) ? data.altitude : null
    );

    activityChart.data.datasets[2].data.push(
        Number.isFinite(data.battery) ? data.battery : null
    );

    activityChart.data.datasets[3].data.push(
        Number.isFinite(data.accuracy) ? data.accuracy : null
    );

    // ----- TRIM WINDOW -----
    if (activityChart.data.labels.length > CONFIG.chartMaxPoints) {
        activityChart.data.labels.shift();
        activityChart.data.datasets.forEach(ds => ds.data.shift());
    }

    activityChart.update('none'); // no animation = realtime-safe
}

function updateActivityChartHistory(data) {
    if (!activityChart) return;

    // Filter points for the selected device and limit to display points
    const points = data.slice(-CONFIG.chartMaxPoints);

    activityChart.data.labels = points.map(p => formatTime(p.timestampuae || p.timestamp));
    activityChart.data.datasets[0].data = points.map(p => p.speed || 0);
    activityChart.data.datasets[1].data = points.map(p => p.altitude || 0);
    activityChart.data.datasets[2].data = points.map(p => p.battery || 0);
    activityChart.data.datasets[3].data = points.map(p => p.accuracy || 0);

    activityChart.update(); // Full update with animation for first load
}


// -------------------- LOAD TELEMETRY DATA --------------------
// -------------------- LOAD TELEMETRY DATA --------------------
async function loadData() {
    const rangeEl = document.getElementById('rangeSel');
    const countEl = document.getElementById('countInput');

    const range = rangeEl ? rangeEl.value : '60';
    const count = countEl ? countEl.value : '200';
    const device = currentDeviceId;

    let url = `/data?limit=${count}`;
    if (range !== '0') url += `&sinceminutes=${range}`;
    if (device) url += `&deviceid=${device}`;

    try {
        const response = await fetch(url);
        const data = await response.json();

        console.log(`Loaded ${data.length} telemetry points`);

        const pointCountEl = document.getElementById('pointCount');
        if (pointCountEl) pointCountEl.textContent = data.length;

        playbackPoints = data;

        // If a device is selected, populate ONLY that device trail (if trail toggle is on)
        const toggleTrail = document.getElementById('toggleTrail');
        const shouldDrawTrail = !!(toggleTrail && toggleTrail.checked && device);

        if (shouldDrawTrail) {
            const color = getDeviceColor(device);
            if (!deviceTrailsMap[device]) {
                deviceTrailsMap[device] = L.polyline([], {
                    color,
                    weight: 3,
                    opacity: 0.7
                }).addTo(map);
            }

            const latlngs = [];
            for (const p of data) {
                if (Number.isFinite(p.lat) && Number.isFinite(p.lon)) {
                    latlngs.push([p.lat, p.lon]);
                }
            }
            deviceTrailsMap[device].setLatLngs(latlngs.slice(-CONFIG.maxTrailPoints));
        }

        // Update map markers using the latest points in the dataset
        if (data.length > 0) {
            // If single device: last point is enough
            if (device) {
                const lastPoint = data[data.length - 1];
                updateLivePosition(lastPoint);
                updateLiveStats(lastPoint);
                updateActivityChartHistory(data);

                const bounds = data
                    .filter(p => Number.isFinite(p.lat) && Number.isFinite(p.lon))
                    .map(p => [p.lat, p.lon]);

                if (bounds.length >= 2) map.fitBounds(bounds, { padding: [50, 50] });
                else map.setView([data[data.length - 1].lat, data[data.length - 1].lon], CONFIG.mapZoom);
            } else {
                // All devices: update markers per record (cheap enough for 2k)
                data.forEach(p => updateLivePosition(p));
            }
        }

        applyDeviceFilter();
        return data;
    } catch (error) {
        console.error('Error loading data:', error);
        showNotification('Failed to load telemetry data', 'danger');
        return [];
    }
}



async function loadAnnouncements() {
    let url = '/announcements?hours=24';

    try {
        const res = await fetch(url);
        if (!res.ok) {
            showNotification('Failed to load announcements', 'danger');
            return;
        }

        const anns = await res.json();
        const list = document.getElementById('announcementsList');
        const badge = document.getElementById('annBadge');

        if (!list) return;

        if (!anns.length) {
            list.innerHTML = `
                <p style="color: var(--text-secondary); text-align:center; padding:2rem;">
                    No announcements in the last 24 hours
                </p>`;
            if (badge) {
                badge.style.display = 'none';
                badge.textContent = '0';
            }
            return;
        }

        if (badge) {
            badge.style.display = 'inline-block';
            badge.textContent = anns.length;
        }

        list.innerHTML = anns.map(a => {
            const safeTitle = escapeHtml(a.title || 'Announcement');
            const safeMsg = escapeHtml(a.message);
            const safeDev = a.deviceid ? escapeHtml(a.deviceid) : 'Broadcast';
            const time = a.created_at_uae
                ? new Date(a.created_at_uae).toLocaleString()
                : '';

            const priorityBadge = a.priority === 'HIGH'
                ? '<span class="badge" style="background:var(--accent-danger);color:#fff;">HIGH</span>'
                : '<span class="badge">NORMAL</span>';

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">
                            <i class="fas fa-bullhorn" style="color:var(--accent-primary)"></i>
                            ${safeTitle} ${priorityBadge}
                        </div>
                        <div class="list-item-meta">
                            <strong>Target:</strong> ${safeDev}<br>
                            <strong>Time:</strong> ${time}<br>
                            <strong>Message:</strong> ${safeMsg}
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    } catch (err) {
        console.error('Error loading announcements:', err);
        showNotification('Failed to load announcements', 'danger');
    }
}

async function requestLiveLocation() {
    if (!currentDeviceId) {
        showNotification("Select a device first", "warning");
        return;
    }

    await fetch(
        `${BACKEND}/request-location?device_id=${encodeURIComponent(currentDeviceId)}`,
        { method: "POST" }
    );

    showNotification("📡 Live location requested", "success");
}


// -------------------- LOAD DEVICES --------------------
async function loadDevices() {
    try {
        const response = await fetch(`${BACKEND}/data?limit=1000&sinceminutes=1440`);
        const data = await response.json();

        const devices = [...new Set(data.map(d => d.deviceid))];
        renderDeviceSelector(devices);

        console.log(`Loaded ${devices.length} devices`);
    } catch (error) {
        console.error('Error loading devices:', error);
    }
}

function renderDeviceSelector(devices) {
    const selector = document.getElementById('deviceSelector');
    if (!selector) return;

    const current = selector.value;

    selector.innerHTML = '<option value="">All Devices</option>';
    devices.forEach(dev => {
        const opt = document.createElement('option');
        opt.value = dev;
        opt.textContent = dev;
        selector.appendChild(opt);
    });

    if (devices.includes(current)) {
        selector.value = current;
    }
}
// Throttle device selector re-render (DOM heavy)
let deviceListTimer = null;

function scheduleDeviceListUpdate() {
    if (deviceListTimer) return;
    deviceListTimer = setTimeout(() => {
        deviceListTimer = null;
        updateDeviceList();
    }, 800); // update at most ~1.25x/sec
}

function updateDeviceList() {
    const devices = Object.keys(deviceData);
    renderDeviceSelector(devices);
}

// -------------------- LOAD ALERTS --------------------
async function loadAlerts() {
    const device = currentDeviceId;
    let url = '/alerts?hours=24';
    if (device) url += `&device_id=${device}`;

    try {
        const response = await fetch(url);
        const alerts = await response.json();
        // 🔹 Liveness stats (client-side, no /stats endpoint needed)
        const stats = computeLivenessStats(alerts);

        const statLive = document.getElementById('statLive');
        const statFailed = document.getElementById('statFailed');
        const statSpoof = document.getElementById('statSpoof');
        const statOverride = document.getElementById('statOverride');

        if (statLive) statLive.textContent = stats.live;
        if (statFailed) statFailed.textContent = stats.failed;
        if (statSpoof) statSpoof.textContent = stats.spoof;
        if (statOverride) statOverride.textContent = stats.override;

        const container = document.getElementById('alertsHistory');
        if (!container) return;

        if (alerts.length === 0) {
            container.innerHTML = `<p style="color: var(--text-secondary); text-align: center; padding: 2rem">No recent alerts</p>`;
            return;
        }

        container.innerHTML = alerts.slice(0, 10).map(alert => {
            const safeType = escapeHtml(alert.alert_type);
            const safeDeviceId = escapeHtml(alert.deviceid);
            const battery = alert.battery ?? '--';
            const accuracy = alert.accuracy ? alert.accuracy.toFixed(1) : '--';
            const timestamp = new Date(alert.timestamp_uae).toLocaleString();

            const locationBtn = alert.latitude
                ? `<div class="list-item-actions">
                        <button class="btn btn-sm" onclick="viewLocation(${alert.latitude}, ${alert.longitude})" title="Show on map">
                            <i class="fas fa-map-marker-alt"></i>
                        </button>
                   </div>`
                : '';

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">
                            <i class="fas fa-exclamation-triangle" style="color: var(--accent-danger)"></i>
   			    ${safeType}
      			    ${getLivenessBadge(alert)}
                        </div>
                        <div class="list-item-meta">
                            <strong>${safeDeviceId}</strong><br>
                            Battery: ${battery}% | Accuracy: ${accuracy}m<br>
                            ${timestamp}
                        </div>
                    </div>
                    ${locationBtn}
                </div>
            `;
        }).join('');

        console.log(`Loaded ${alerts.length} alerts`);
    } catch (error) {
        console.error('Error loading alerts:', error);
    }
}

async function loadSecurityAlerts() {
    const device = currentDeviceId;
    let url = '/security-alerts?hours=24';
    if (device) url += `&device_id=${device}`;

    try {
        const response = await fetch(url);
        const alerts = await response.json();

        const container = document.getElementById('securityAlerts');
        if (!container) return;

        if (alerts.length === 0) {
            container.innerHTML = `<p style="color: var(--text-secondary); text-align: center; padding: 2rem">No security alerts</p>`;
            return;
        }

        container.innerHTML = alerts.map(a => {
            const safeType = escapeHtml(a.alert_type);
            const safeDetails = escapeHtml(a.details || '');
            const safeDeviceId = escapeHtml(a.deviceid);
            const timestamp = new Date(a.timestamp_uae).toLocaleString();

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">
                            <i class="fas fa-shield-alt" style="color: var(--accent-warning)"></i>
                            ${safeType}
                        </div>
                        <div class="list-item-meta">
                            <strong>${safeDeviceId}</strong><br>
                            ${safeDetails}<br>
                            ${timestamp}
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    } catch (error) {
        console.error('Error loading security alerts:', error);
    }
}

async function updateAlertBadge() {
    try {
        const response = await fetch(`${BACKEND}/alerts?hours=24`);
        const alerts = await response.json();
        const badge = document.getElementById('alertBadge');
        if (badge) {
            badge.textContent = alerts.length;
            badge.style.display = alerts.length > 0 ? 'inline-block' : 'none';
        }
    } catch (error) {
        console.error('Error updating alert badge:', error);
    }
}

// -------------------- LOAD INCIDENTS --------------------
async function loadIncidents() {
    const device = currentDeviceId;
    let url = '/incidents?hours=48';
    if (device) url += `&device_id=${device}`;

    try {
        const response = await fetch(url);
        const incidents = await response.json();

        const container = document.getElementById('incidentsBox');
        if (!container) return;

        if (incidents.length === 0) {
            container.innerHTML = `<p style="color: var(--text-secondary); text-align: center; padding: 2rem">No incidents reported</p>`;
            return;
        }

        container.innerHTML = incidents.map(inc => {
            const badge = inc.resolved ? '<span class="badge success">Resolved</span>' : '<span class="badge warning">Open</span>';
            const safeType = escapeHtml(inc.incident_type);
            const safeDesc = escapeHtml(inc.description);
            const safeDeviceId = escapeHtml(inc.deviceid);
            const timestamp = new Date(inc.timestamp_uae).toLocaleString();

            const resolveBtn = !inc.resolved
                ? `<button class="btn btn-sm btn-danger" onclick="resolveIncident(${inc.id})" title="Resolve">
                        <i class="fas fa-check"></i>
                   </button>`
                : '';

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">${safeType} ${badge}</div>
                        <div class="list-item-meta">
                            ${safeDesc}<br>
                            <strong>${safeDeviceId}</strong> | ${timestamp}
                        </div>
                    </div>
                    <div class="list-item-actions">
                        <button class="btn btn-sm" onclick="viewLocation(${inc.latitude}, ${inc.longitude})" title="Show on map">
                            <i class="fas fa-map-marker-alt"></i>
                        </button>
                        ${inc.has_photo ?
                    `<a class="btn btn-sm" href="${BACKEND}${inc.photo_url}" target="_blank" title="View Photo">
                                <i class="fas fa-image"></i>
                             </a>` : ''}
                        ${resolveBtn}
                    </div>
                </div>
            `;
        }).join('');

        console.log(`Loaded ${incidents.length} incidents`);
    } catch (error) {
        console.error('Error loading incidents:', error);
    }
}

// -------------------- LOAD GEOFENCES --------------------
async function loadGeofences() {
    try {
        const response = await fetch(`${BACKEND}/geofences`);
        geofences = await response.json();

        geofenceCircles.forEach(c => map.removeLayer(c));
        geofenceCircles = [];

        geofences.forEach(gf => {
            if (gf.type === 'circle' && gf.enabled) {
                const circle = L.circle([gf.latitude, gf.longitude], {
                    radius: gf.radius,
                    color: gf.color || '#00f5ff',
                    fillColor: gf.color || '#00f5ff',
                    fillOpacity: 0.2,
                    weight: 2
                }).addTo(map);

                circle.bindPopup(`<strong>${escapeHtml(gf.name)}</strong><br>Radius: ${gf.radius}m`);
                geofenceCircles.push(circle);
            }
        });

        renderGeofenceList();

        console.log(`Loaded ${geofences.length} geofences`);
    } catch (error) {
        console.error('Error loading geofences:', error);
    }
}

function renderGeofenceList() {
    const container = document.getElementById('geofenceList');
    if (!container) return;

    if (geofences.length === 0) {
        container.innerHTML = `<p style="color: var(--text-secondary); text-align: center; padding: 2rem">No geofences created</p>`;
        return;
    }

    container.innerHTML = geofences.map(gf => {
        const safeName = escapeHtml(gf.name);
        const statusBadge = gf.enabled ? '<span class="badge success">Active</span>' : '<span class="badge">Disabled</span>';
        return `
            <div class="list-item">
                <div class="list-item-content">
                    <div class="list-item-title">${safeName}</div>
                    <div class="list-item-meta">
                        Type: ${gf.type} | Radius: ${gf.radius}m<br>
                        ${statusBadge}
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

async function loadGeofenceEvents() {
    const device = currentDeviceId;
    let url = '/geofence_events?hours=24';
    if (device) url += `&device_id=${device}`;

    try {
        const response = await fetch(url);
        const events = await response.json();

        const container = document.getElementById('geofenceEvents');
        if (!container) return;

        if (events.length === 0) {
            container.innerHTML = `<p style="color: var(--text-secondary); text-align: center; padding: 2rem">No recent activity</p>`;
            return;
        }

        container.innerHTML = events.slice(0, 10).map(evt => {
            const icon = evt.event_type === 'ENTER' ? '🚪➡️' : '🚪⬅️';
            const action = evt.event_type === 'ENTER' ? 'entered' : 'exited';
            const safeName = escapeHtml(evt.geofence_name);
            const safeDeviceId = escapeHtml(evt.deviceid);
            const timestamp = new Date(evt.timestamp_uae).toLocaleString();

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">${icon} ${safeName}</div>
                        <div class="list-item-meta">
                            ${safeDeviceId} ${action}<br>
                            ${timestamp}
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    } catch (error) {
        console.error('Error loading geofence events:', error);
    }
}

// -------------------- LOAD PHOTOS --------------------
async function loadPhotos() {
    const limitEl = document.getElementById('photoLimit');
    const limit = limitEl ? limitEl.value : '10';

    try {
        const response = await fetch(`${BACKEND}/images?limit=${limit}`);

        if (!response.ok) {
            console.warn('Images endpoint not available');
            return;
        }

        const photos = await response.json();

        const strip = document.getElementById('thumbStrip');
        const mainPhoto = document.getElementById('photoMain');
        const downloadLink = document.getElementById('downloadPhoto');

        if (!strip || !mainPhoto) return;

        strip.innerHTML = '';

        if (photos.length === 0) {
            mainPhoto.src = '';
            mainPhoto.alt = 'No photos available';
            return;
        }

        const resolvePhotoUrl = (p) => {
            let url = p.url || p.image_url || p.photo_url || null;
            if (!url) return null;
            // Ensure absolute prefix if it looks like just a filename
            if (!url.startsWith('http') && !url.startsWith('/')) {
                url = '/uploads/' + url;
            }
            // Prefix with BACKEND if it's a relative path from root
            if (url.startsWith('/')) {
                return BACKEND + url;
            }
            return url;
        };

        const firstUrl = resolvePhotoUrl(photos[0]);
        if (firstUrl) {
            mainPhoto.src = firstUrl;
            if (downloadLink) downloadLink.href = firstUrl;
        } else {
            console.warn('Photo URL missing:', photos[0]);
        }



        photos.forEach((photo, i) => {
            const thumb = document.createElement('img');
            const url = resolvePhotoUrl(photo);
            if (!url) return;
            thumb.src = url;

            thumb.className = i === 0 ? 'thumb active' : 'thumb';
            thumb.onclick = () => {
                const u = resolvePhotoUrl(photo);
                if (!u) return;
                mainPhoto.src = u;
                if (downloadLink) downloadLink.href = u;
                document.querySelectorAll('.thumb').forEach(t => t.classList.remove('active'));
                thumb.classList.add('active');
            };

            strip.appendChild(thumb);
        });

        console.log(`Loaded ${photos.length} photos`);
    } catch (error) {
        console.error('Error loading photos:', error);
    }
}

// -------------------- LOAD CHECKPOINTS --------------------
async function loadCheckpoints() {
    const device = currentDeviceId;
    let url = '/checkpoints?hours=24';
    if (device) url += `&device_id=${device}`;

    try {
        const response = await fetch(url);

        if (!response.ok) {
            showNotification('Checkpoints feature not available', 'warning');
            return;
        }

        const checkpoints = await response.json();

        const container = document.getElementById('checkpointsBox');
        if (!container) return;

        if (checkpoints.length === 0) {
            container.innerHTML = `<p style="color: var(--text-secondary); text-align: center; padding: 2rem">No checkpoints visited</p>`;
            return;
        }

        container.innerHTML = checkpoints.map(cp => {
            const safeName = escapeHtml(cp.checkpoint_name);
            const safeId = escapeHtml(cp.checkpoint_id);
            const safeDeviceId = escapeHtml(cp.deviceid);
            const timestamp = new Date(cp.timestamp_uae).toLocaleString();

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">${safeName}</div>
                        <div class="list-item-meta">
                            ID: ${safeId}<br>
                            <strong>${safeDeviceId}</strong> | ${timestamp}
                        </div>
                    </div>
                    <div class="list-item-actions">
                        <button class="btn btn-sm" onclick="viewLocation(${cp.latitude}, ${cp.longitude})" title="Show on map">
                            <i class="fas fa-map-marker-alt"></i>
                        </button>
                    </div>
                </div>
            `;
        }).join('');

        console.log(`Loaded ${checkpoints.length} checkpoints`);
    } catch (error) {
        console.error('Error loading checkpoints:', error);
    }
}

// -------------------- LOAD SUMMARY --------------------
async function loadSummary() {
    const rangeEl = document.getElementById('rangeSel');
    const range = rangeEl ? rangeEl.value : '60';
    const device = currentDeviceId || Object.keys(deviceData)[0];

    if (!device) {
        showNotification('No device selected', 'warning');
        return;
    }

    const url = `/summary?device_id=${encodeURIComponent(device)}&since_minutes=${encodeURIComponent(range || 60)}`;

    try {
        const response = await fetch(url);
        if (!response.ok) {
            showNotification('Summary endpoint not available', 'warning');
            return;
        }

        const data = await response.json();

        const summaryBox = document.getElementById('summaryBox');
        if (!summaryBox) return;

        if (data.message) {
            summaryBox.textContent = data.message;
            return;
        }

        const distM = Number(data.distance_m ?? 0);
        const avgSpeed = Number(data.avg_speed_kmh ?? 0);
        const offlineRatio = Number(data.offline_ratio_percent ?? 0);

        const summary =
            `ROUTE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Device ID:        ${data.deviceid ?? device}
Points:           ${data.points ?? 0}
Distance:         ${distM.toFixed(2)} m (${(distM / 1000).toFixed(2)} km)
Total Steps:      ${data.steps_total ?? 0}
Average Speed:    ${avgSpeed.toFixed(2)} km/h
Offline Ratio:    ${offlineRatio.toFixed(1)}%

Time Range:
  Start: ${data.start_time_uae ? new Date(data.start_time_uae).toLocaleString() : '--'}
  End:   ${data.end_time_uae ? new Date(data.end_time_uae).toLocaleString() : '--'}`;

        summaryBox.textContent = summary;
        console.log('Summary loaded');
    } catch (error) {
        console.error('Error loading summary:', error);
        showNotification('Failed to load summary', 'danger');
    }
}

// -------------------- PLAYBACK --------------------
let playbackIndex = 0;

function startPlayback() {
    if (!currentDeviceId) {
        showNotification('Select a device first for playback', 'warning');
        return;
    }

    if (!playbackPoints.length) {
        showNotification('No data to play. Load data first.', 'warning');
        return;
    }
    const playbackDevice = currentDeviceId;

    if (!deviceTrailsMap[playbackDevice]) {
        deviceTrailsMap[playbackDevice] = L.polyline([], {
            color: getDeviceColor(playbackDevice),
            weight: 3,
            opacity: 0.8
        }).addTo(map);
    }

    deviceTrailsMap[playbackDevice].setLatLngs([]);

    isPlaying = true;
    playbackIndex = 0;

    const playBtn = document.getElementById('playToggle');
    if (playBtn) playBtn.innerHTML = '<i class="fas fa-pause"></i> Pause';


    playbackTimer = setInterval(() => {
        if (!isPlaying || playbackIndex >= playbackPoints.length) {
            stopPlayback();
            return;
        }

        updateLivePosition(playbackPoints[playbackIndex]);

        const slider = document.getElementById('playSlider');
        if (slider) {
            slider.value = Math.round((playbackIndex / (playbackPoints.length - 1)) * 100);
        }

        playbackIndex++;
    }, 200);
}


function stopPlayback() {
    isPlaying = false;
    clearInterval(playbackTimer);
    const playBtn = document.getElementById('playToggle');
    if (playBtn) playBtn.innerHTML = '<i class="fas fa-play"></i> Play';

    bufferedDuringPlayback.forEach(p => {
        updateLivePosition(p);
    });
    bufferedDuringPlayback = [];

}
function computeDeviceStats(points) {
    if (!points.length) return null;

    const speeds = points.map(p => p.speed || 0);
    const batteries = points.map(p => p.battery || 0);

    return {
        avgSpeed: speeds.reduce((a, b) => a + b, 0) / speeds.length,
        minBattery: Math.min(...batteries),
        maxBattery: Math.max(...batteries)
    };
}

// -------------------- VIEW SWITCHING --------------------
function switchView(viewName) {
    document.querySelectorAll('.view-container').forEach(v => v.classList.remove('active'));

    const targetView = document.getElementById(`view-${viewName}`);
    if (targetView) targetView.classList.add('active');

    document.querySelectorAll('.menu-item').forEach(m => m.classList.remove('active'));
    const activeMenuItem = document.querySelector(`.menu-item[data-view="${viewName}"]`);
    if (activeMenuItem) activeMenuItem.classList.add('active');

    if (viewName === 'alerts') {
        loadAlerts();
        loadSecurityAlerts();
    } else if (viewName === 'incidents') {
        loadIncidents();
    } else if (viewName === 'geofences') {
        loadGeofenceEvents();
        loadGeofences();
    } else if (viewName === 'photos') {
        loadPhotos();
    } else if (viewName === 'checkpoints') {
        loadCheckpoints();
    }
    else if (viewName === 'announcements') {
        loadAnnouncements();
    }
    else if (viewName === 'bugs') {
        loadBugReports();
    }


    console.log(`Switched to ${viewName} view`);
}
// --- ANNOUNCEMENTS ---

async function sendAnnouncement() {
    console.log("📣 Send announcement clicked");

    const message = document.getElementById('annMessage')?.value.trim();
    if (!message) {
        showNotification("Message required", "warning");
        return;
    }

    const payload = {
        title: document.getElementById('annTitle')?.value || "Announcement",
        message,
        device_id: document.getElementById('annDevice')?.value || null,
        priority: document.getElementById('annPriority')?.value || "NORMAL",
        tts: document.getElementById('annTTS')?.checked ?? true,
        vibrate: document.getElementById('annVibrate')?.checked ?? false,
        raise_alert: document.getElementById('annRaiseAlert')?.checked ?? false
    };

    const res = await fetch(`${BACKEND}/announcements`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
    });

    if (!res.ok) {
        showNotification("Failed to send announcement", "danger");
        return;
    }

    showNotification("📣 Announcement sent", "success");
    closeAnnouncementModal();
}


// -------------------- EVENT LISTENERS --------------------
function initEventListeners() {
    console.log('Initializing event listeners...');

    const deviceSelector = document.getElementById('deviceSelector');
    if (deviceSelector) {
        deviceSelector.onchange = (e) => {
            currentDeviceId = e.target.value;
            console.log('Selected device:', currentDeviceId);
            loadData();
            updateLiveStats({});
        };
    }

    const settingsBtn = document.getElementById('settingsBtn');
    if (settingsBtn) {
        settingsBtn.onclick = openSettingsModal;
    }
    const refreshBugs = document.getElementById('refreshBugs');
    if (refreshBugs) refreshBugs.onclick = loadBugReports;

    const refreshAnnouncements = document.getElementById('refreshAnnouncements');
    if (refreshAnnouncements) {
        refreshAnnouncements.onclick = loadAnnouncements;
    }

    const playSlider = document.getElementById('playSlider');
    if (playSlider) {
        playSlider.oninput = (e) => {
            if (!playbackPoints.length) return;

            playbackIndex = Math.floor(
                (e.target.value / 100) * (playbackPoints.length - 1)
            );

            updateLivePosition(playbackPoints[playbackIndex]);
        };
    }

    // Request Live Location button
    const requestLocationBtn = document.getElementById("requestLocationBtn");
    if (requestLocationBtn) {
        requestLocationBtn.onclick = requestLiveLocation;
    }


    const reloadBtn = document.getElementById('reloadBtn');
    if (reloadBtn) {
        reloadBtn.onclick = () => {
            console.log('Reloading data...');
            loadData();
            loadGeofenceEvents();
            loadAlerts();
            updateAlertBadge();
        };
    }

    const refreshDashboard = document.getElementById('refreshDashboard');
    if (refreshDashboard) {
        refreshDashboard.onclick = () => {
            loadData();
            updateAlertBadge();
            showNotification('Dashboard refreshed', 'success');
        };
    }

    const resetView = document.getElementById('resetView');
    if (resetView) {
        resetView.onclick = () => {
            if (playbackPoints.length > 0) {
                const bounds = playbackPoints.map(p => [p.lat, p.lon]);
                map.fitBounds(bounds, { padding: [50, 50] });
            }
        };
    }



    const btnOpenAnnouncementTop = document.getElementById('btnOpenAnnouncementTop');
    if (btnOpenAnnouncementTop) {
        btnOpenAnnouncementTop.onclick = openAnnouncementModal;
    }

    const btnOpenAnnouncementMain = document.getElementById('btnOpenAnnouncementMain');
    if (btnOpenAnnouncementMain) {
        btnOpenAnnouncementMain.onclick = openAnnouncementModal;
    }

    const btnAnnouncementCancel = document.getElementById('btnAnnouncementCancel');
    if (btnAnnouncementCancel) {
        btnAnnouncementCancel.onclick = closeAnnouncementModal;
    }

    const drawGeofence = document.getElementById('drawGeofence');
    if (drawGeofence) {
        drawGeofence.onclick = () => {
            if (drawControl) {
                map.addControl(drawControl);
                showNotification('Click on map to draw geofence', 'info');
            }
        };
    }

    const manageGeofencesBtn = document.getElementById('manageGeofences');
    if (manageGeofencesBtn) {
        manageGeofencesBtn.onclick = openGeofenceManager;
    }

    const playToggle = document.getElementById('playToggle');
    if (playToggle) {
        playToggle.onclick = () => {
            if (isPlaying) stopPlayback();
            else startPlayback();
        };
    }

    const toggleSidebar = document.getElementById('toggleSidebar');
    if (toggleSidebar) {
        toggleSidebar.onclick = () => {
            document.getElementById('sidebar')?.classList.toggle('collapsed');
            document.getElementById('mainContent')?.classList.toggle('expanded');
        };
    }

    const toggleFullscreen = document.getElementById('toggleFullscreen');
    if (toggleFullscreen) {
        toggleFullscreen.onclick = () => {
            if (!document.fullscreenElement) {
                document.documentElement.requestFullscreen();
                toggleFullscreen.innerHTML = '<i class="fas fa-compress"></i>';
            } else {
                document.exitFullscreen();
                toggleFullscreen.innerHTML = '<i class="fas fa-expand"></i>';
            }
        };
    }

    document.querySelectorAll('.menu-item[data-view]').forEach(item => {
        item.onclick = () => {
            const view = item.dataset.view;
            switchView(view);
        };
    });

    const refreshAlerts = document.getElementById('refreshAlerts');
    if (refreshAlerts) refreshAlerts.onclick = () => { loadAlerts(); updateAlertBadge(); };



    const loadIncidentsBtn = document.getElementById('loadIncidents');
    if (loadIncidentsBtn) loadIncidentsBtn.onclick = loadIncidents;

    const loadCheckpointsBtn = document.getElementById('loadCheckpoints');
    if (loadCheckpointsBtn) loadCheckpointsBtn.onclick = loadCheckpoints;

    const refreshPhotos = document.getElementById('refreshPhotos');
    if (refreshPhotos) refreshPhotos.onclick = loadPhotos;

    const exportDataBtn = document.getElementById('exportDataBtn');
    if (exportDataBtn) exportDataBtn.onclick = exportCSV;

    const playbackBtn = document.getElementById('playbackBtn');
    if (playbackBtn) {
        playbackBtn.onclick = () => {
            if (isPlaying) stopPlayback();
            else startPlayback();
        };
    }

    const refreshAllBtn = document.getElementById('refreshAllBtn');
    if (refreshAllBtn) {
        refreshAllBtn.onclick = () => {
            loadData();
            loadAlerts();
            loadGeofences();
            loadPhotos();
            loadGeofenceEvents();
            updateAlertBadge();
            showNotification('All data refreshed', 'success');
        };
    }

    document.querySelectorAll('.tab').forEach(tab => {
        tab.onclick = () => {
            const target = tab.dataset.tab;
            const parent = tab.closest('.card');
            if (!parent) return;

            parent.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            parent.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            tab.classList.add('active');
            const targetContent = parent.querySelector(`#tab-${target}`);
            if (targetContent) targetContent.classList.add('active');
        };
    });
    const annTopBtn = document.getElementById('btnOpenAnnouncementTop');
    if (annTopBtn) {
        annTopBtn.onclick = openAnnouncementModal;
    }
    const selector = document.getElementById('deviceSelector');
    if (selector) {
        selector.onchange = () => {
            currentDeviceId = selector.value || null;

            // Reset chart cleanly
            if (activityChart) {
                activityChart.data.labels = [];
                activityChart.data.datasets.forEach(ds => ds.data = []);
                activityChart.update();
            }

            loadData();
            applyDeviceFilter();
        };
    }

    const annMainBtn = document.getElementById('btnOpenAnnouncementMain');
    if (annMainBtn) {
        annMainBtn.onclick = openAnnouncementModal;
    }

    const annBackdrop = document.getElementById('announcementBackdrop');
    if (annBackdrop) {
        annBackdrop.onclick = closeAnnouncementModal;
    }

    const annCancel = document.getElementById('btnAnnouncementCancel');
    if (annCancel) {
        annCancel.onclick = closeAnnouncementModal;
    }

    const annSend = document.getElementById('btnAnnouncementSend');
    if (annSend) {
        annSend.onclick = sendAnnouncement;
    }
    const btnSummary = document.getElementById('loadSummary');
    if (btnSummary) btnSummary.onclick = loadSummaryAnalytics;

    const btnAnom = document.getElementById('loadAnomalies');
    if (btnAnom) btnAnom.onclick = loadAnomalyAnalytics;

    const btnBatt = document.getElementById('loadBattery');
    if (btnBatt) btnBatt.onclick = loadBatteryAnalytics;

    const btnDist = document.getElementById('loadDistance');
    if (btnDist) btnDist.onclick = loadDistanceAnalytics;

    const btnStates = document.getElementById('loadStates');
    if (btnStates) btnStates.onclick = loadStateAnalytics;
    console.log('✅ Event listeners initialized');
}

// -------------------- UTILITY FUNCTIONS --------------------
function showNotification(message, type = 'warning') {
    const banner = document.getElementById('alertBanner');
    if (!banner) {
        console.log(`[${type}] ${message}`);
        return;
    }

    const icons = {
        warning: 'exclamation-triangle',
        danger: 'times-circle',
        success: 'check-circle',
        info: 'info-circle'
    };

    banner.className = `alert ${type}`;
    banner.innerHTML = `<i class="fas fa-${icons[type]}"></i> ${escapeHtml(message)}`;
    banner.classList.remove('hidden');

    setTimeout(() => banner.classList.add('hidden'), 6000);

    if (type === 'danger' && 'Notification' in window && Notification.permission === 'granted') {
        new Notification('Watchmen Alert', {
            body: message,
            icon: '/favicon.ico',
            tag: 'watchmen-alert'
        });
    }
}

async function exportCSV() {
    const deviceId = currentDeviceId || ''
    const rangeEl = document.getElementById('rangeSel')
    const minutes = rangeEl ? rangeEl.value : 1440 // fallback to 24h if null

    showNotification('Preparing high-fidelity export...', 'info');

    // Use a high limit for full history export
    const url =
        `/data?limit=100000` +
        (deviceId ? `&deviceid=${encodeURIComponent(deviceId)}` : '') +
        (minutes && minutes !== '0' ? `&sinceminutes=${minutes}` : '')

    console.log("📤 Exporting detailed telemetry from:", url)

    try {
        const res = await fetch(url)
        if (!res.ok) {
            showNotification("Export failed: Server error", "danger");
            return
        }

        const rows = await res.json()
        if (!rows.length) {
            showNotification("No data found for the selected criteria", "warning");
            return
        }

        downloadCSV(rows)
        showNotification(`Exported ${rows.length} points successfully`, 'success');
    } catch (e) {
        console.error("Export error:", e);
        showNotification("Export failed: Network error", "danger");
    }
}

function downloadCSV(rows) {
    if (!rows || rows.length === 0) return

    // Define columns for enhanced export
    const columns = [
        { label: 'ID', key: 'id' },
        { label: 'Device ID', key: 'deviceid' },
        { label: 'Device Name', key: 'devicename' },
        { label: 'Project Number', key: 'projectnumber' },
        { label: 'Latitude', key: 'lat' },
        { label: 'Longitude', key: 'lon' },
        { label: 'Speed (m/s)', key: 'speed' },
        { label: 'Speed (km/h)', formula: r => (r.speed * 3.6).toFixed(2) },
        { label: 'Bearing (deg)', key: 'bearing' },
        { label: 'Altitude (m)', key: 'altitude' },
        { label: 'Accuracy (m)', key: 'accuracy' },
        { label: 'Steps', key: 'steps' },
        { label: 'Battery (%)', key: 'battery' },
        { label: 'Offline', key: 'offline' },
        { label: 'Tracking State', key: 'trackingstate' },
        { label: 'Timestamp (UTC)', key: 'timestamputc' },
        { label: 'Timestamp (UAE)', key: 'timestampuae' },
        { label: 'Device Health', key: 'devicehealth', formula: r => JSON.stringify(r.devicehealth || {}) }
    ];

    const header = columns.map(c => `"${c.label}"`).join(',')

    const body = rows.map(row => {
        return columns.map(col => {
            let val = col.formula ? col.formula(row) : row[col.key];
            if (val === null || val === undefined) val = '';
            // Escape quotes and wrap in quotes
            return `"${String(val).replace(/"/g, '""')}"`;
        }).join(',');
    }).join('\n');

    const csv = header + '\n' + body
    const filename = `watchmen_export_${currentDeviceId || 'all'}_${new Date().toISOString().split('T')[0]}.csv`;

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)

    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()

    setTimeout(() => URL.revokeObjectURL(url), 100);
}



function escapeHtml(text) {
    if (!text) return '';
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.toString().replace(/[&<>"']/g, m => map[m]);
}

function formatTime(timestamp) {
    if (!timestamp) return '--:--';
    try {
        const date = new Date(timestamp);
        if (isNaN(date.getTime())) return timestamp;
        return date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch {
        return '--:--';
    }
}

function cleanupDeviceData() {
    if (Object.keys(deviceData).length > CONFIG.maxDevices) {
        const devices = Object.keys(deviceData);
        const toRemove = devices.slice(0, devices.length - CONFIG.maxDevices);
        toRemove.forEach(id => {
            delete deviceData[id];
            delete deviceTrailsMap[id];

        });
        console.log(`Cleaned up ${toRemove.length} old devices`);
    }
}

function addAlertMarker(lat, lon, type) {
    const marker = L.marker([lat, lon], {
        icon: L.divIcon({
            className: 'alert-marker',
            html: '<div style="background: red; width: 15px; height: 15px; border-radius: 50%; border: 2px solid white; box-shadow: 0 2px 8px rgba(255,0,0,0.5);"></div>'
        })
    }).addTo(map).bindPopup(`🚨 Alert: ${type}`);

    incidentMarkers.push(marker);
}

// -------------------- GLOBAL WINDOW FUNCTIONS --------------------
window.viewLocation = function (lat, lon) {
    incidentMarkers.forEach(m => map.removeLayer(m));
    incidentMarkers = [];

    map.setView([lat, lon], 18);

    const marker = L.marker([lat, lon], {
        icon: L.divIcon({
            html: '<i class="fas fa-exclamation-triangle" style="color: var(--accent-danger); font-size: 24px"></i>',
            className: '',
            iconSize: [24, 24]
        })
    }).addTo(map).bindPopup('Location').openPopup();

    incidentMarkers.push(marker);
    switchView('dashboard');
};

window.resolveIncident = async function (id) {
    try {
        const response = await fetch(`${BACKEND}/incidents/${id}/resolve`, { method: 'PUT' });
        if (response.ok) {
            showNotification('Incident resolved', 'success');
            loadIncidents();
        }
    } catch (error) {
        console.error('Error resolving incident:', error);
    }
};

window.toggleGeofence = async function (id, enabled) {
    try {
        const response = await fetch(`${BACKEND}/geofences/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
        });
        if (response.ok) {
            showNotification(`Geofence ${enabled ? 'enabled' : 'disabled'}`, 'success');
            loadGeofences();
            openGeofenceManager();
        }
    } catch (error) {
        console.error('Error toggling geofence:', error);
    }
};

window.deleteGeofence = async function (id) {
    if (!confirm('Delete this geofence?')) return;

    try {
        const response = await fetch(`${BACKEND}/geofences/${id}`, { method: 'DELETE' });
        if (response.ok) {
            showNotification('Geofence deleted', 'success');
            loadGeofences();
            openGeofenceManager();
        }
    } catch (error) {
        console.error('Error deleting geofence:', error);
    }
};
// ADD these missing implementations:
async function loadSummaryAnalytics() {
    const box = document.getElementById('summaryBox');
    if (!box) return;
    box.textContent = 'Computing summary...';

    const rangeEl = document.getElementById('rangeSel');
    const minutes = rangeEl ? rangeEl.value : 60;
    const device = currentDeviceId || '';

    let url = `/data?limit=50000&sinceminutes=${minutes}`;
    if (device) url += `&deviceid=${encodeURIComponent(device)}`;

    try {
        const res = await fetch(url);
        const rows = await res.json();

        if (!rows.length) {
            box.textContent = 'No telemetry in selected window.';
            return;
        }

        const total = rows.length;
        const startTime = rows[0].timestampuae || rows[0].timestamputc;
        const endTime = rows[rows.length - 1].timestampuae || rows[rows.length - 1].timestamputc;

        const avgSpeed =
            rows.reduce((s, r) => s + (r.speed || 0), 0) / total;

        box.innerHTML = `
            <p><strong>Points:</strong> ${total}</p>
            <p><strong>Start:</strong> ${startTime}</p>
            <p><strong>End:</strong> ${endTime}</p>
            <p><strong>Avg speed:</strong> ${avgSpeed.toFixed(2)} m/s</p>
        `;
    } catch (e) {
        console.error('Summary error', e);
        box.textContent = 'Failed to load summary.';
    }
}

async function loadBatteryAnalytics() {
    const box = document.getElementById('batteryBox');
    if (!box) return;
    box.textContent = 'Computing battery stats...';

    const res = await fetch('/data?limit=5000&sinceminutes=1440');
    const rows = await res.json();

    if (!rows.length) {
        box.textContent = 'No battery data.';
        return;
    }

    const batteries = rows.map(r => r.battery).filter(b => b != null);
    const min = Math.min(...batteries);
    const max = Math.max(...batteries);
    const avg = batteries.reduce((s, v) => s + v, 0) / batteries.length;

    box.innerHTML = `
        <p><strong>Samples:</strong> ${batteries.length}</p>
        <p><strong>Min:</strong> ${min.toFixed(1)}%</p>
        <p><strong>Max:</strong> ${max.toFixed(1)}%</p>
        <p><strong>Avg:</strong> ${avg.toFixed(1)}%</p>
    `;
}

// distance: simple cumulative distance using haversine approx
function haversineKm(lat1, lon1, lat2, lon2) {
    const R = 6371;
    const toRad = x => x * Math.PI / 180;
    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);
    const a =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
        Math.sin(dLon / 2) ** 2;
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

async function loadDistanceAnalytics() {
    const box = document.getElementById('distanceBox');
    if (!box) return;
    box.textContent = 'Computing distance...';

    const res = await fetch('/data?limit=5000&sinceminutes=1440');
    const rows = await res.json();

    if (rows.length < 2) {
        box.textContent = 'Not enough data.';
        return;
    }

    let dist = 0;
    for (let i = 1; i < rows.length; i++) {
        dist += haversineKm(
            rows[i - 1].lat, rows[i - 1].lon,
            rows[i].lat, rows[i].lon
        );
    }

    box.innerHTML = `
        <p><strong>Points:</strong> ${rows.length}</p>
        <p><strong>Total distance:</strong> ${dist.toFixed(2)} km</p>
    `;
}

async function loadStateAnalytics() {
    const box = document.getElementById('statesBox');
    if (!box) return;
    box.textContent = 'Computing state distribution...';

    const res = await fetch('/data?limit=5000&sinceminutes=1440');
    const rows = await res.json();

    if (!rows.length) {
        box.textContent = 'No state data.';
        return;
    }

    const counts = {};
    rows.forEach(r => {
        const st = r.trackingstate || 'UNKNOWN';
        counts[st] = (counts[st] || 0) + 1;
    });

    const total = rows.length;
    const lines = Object.entries(counts)
        .map(([st, c]) => `${st}: ${c} (${((c / total) * 100).toFixed(1)}%)`)
        .join('<br>');

    box.innerHTML = `
        <p><strong>Total points:</strong> ${total}</p>
        <p>${lines}</p>
    `;
}

async function loadAnomalyAnalytics() {
    const box = document.getElementById('anomaliesBox');
    if (!box) return;
    box.textContent = 'Scanning for anomalies...';

    const res = await fetch('/data?limit=5000&sinceminutes=60');
    const rows = await res.json();

    if (rows.length < 2) {
        box.textContent = 'Not enough data.';
        return;
    }

    const anomalies = [];
    for (let i = 1; i < rows.length; i++) {
        const prev = rows[i - 1];
        const cur = rows[i];
        const dt = (new Date(cur.timestamp) - new Date(prev.timestamp)) / 1000;
        if (dt <= 0) continue;

        // crude jerk: speed jump > 5 m/s in < 2s
        const dv = (cur.speed || 0) - (prev.speed || 0);
        if (Math.abs(dv) > 5 && dt < 2) {
            anomalies.push({
                idx: i,
                timestamp: cur.timestampuae || cur.timestamp,
                dv: dv.toFixed(2),
                dt: dt.toFixed(2)
            });
        }
    }

    if (!anomalies.length) {
        box.textContent = 'No anomalies detected with simple heuristic.';
        return;
    }

    box.innerHTML = anomalies
        .slice(0, 20)
        .map(a => `#${a.idx} @ ${a.timestamp}: Δv=${a.dv} m/s in ${a.dt}s`)
        .join('<br>');
}

async function loadBattery() {
    try {
        const response = await fetch(`${BACKEND}/stats/battery`);
        const data = await response.json();
        const box = document.getElementById('batteryBox');
        if (box) {
            box.textContent = `Average: ${data.avg}%\nMin: ${data.min}%\nMax: ${data.max}%`;
        }
    } catch (error) {
        console.error('Error loading battery stats:', error);
    }
}

async function loadDistance() {
    try {
        const response = await fetch(`${BACKEND}/stats/distance`);
        const data = await response.json();
        const box = document.getElementById('distanceBox');
        if (box) {
            box.textContent = `Total Distance: ${data.distance_km} km\n(${data.distance_m} meters)`;
        }
    } catch (error) {
        console.error('Error loading distance stats:', error);
    }
}

async function loadStates() {
    try {
        const response = await fetch(`${BACKEND}/stats/states`);
        const data = await response.json();
        const box = document.getElementById('statesBox');
        if (box) {
            let output = "State Distribution:\n";
            for (const [state, count] of Object.entries(data)) {
                output += `${state}: ${count}\n`;
            }
            box.textContent = output;
        }
    } catch (error) {
        console.error('Error loading state stats:', error);
    }
}
async function loadBugReports() {
    try {
        const res = await fetch(`${BACKEND}/bug-reports?hours=72`);
        if (!res.ok) {
            showNotification("Failed to load bug reports", "danger");
            return;
        }

        const bugs = await res.json();
        const list = document.getElementById('bugList');
        const badge = document.getElementById('bugBadge');

        if (!list) return;

        // Reset counters
        const stats = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };

        if (!bugs.length) {
            list.innerHTML = `<p style="color:var(--text-secondary);text-align:center;padding:2rem;">No bug reports</p>`;
            if (badge) badge.style.display = 'none';
            return;
        }

        bugs.forEach(b => {
            if (stats[b.severity] !== undefined) stats[b.severity]++;
        });

        document.getElementById('statBugCritical').textContent = stats.CRITICAL;
        document.getElementById('statBugHigh').textContent = stats.HIGH;
        document.getElementById('statBugMedium').textContent = stats.MEDIUM;
        document.getElementById('statBugLow').textContent = stats.LOW;

        if (badge) {
            badge.textContent = bugs.length;
            badge.style.display = 'inline-block';
        }

        list.innerHTML = bugs.map(bug => {
            const sevClass =
                bug.severity === 'CRITICAL' ? 'danger' :
                    bug.severity === 'HIGH' ? 'warning' :
                        bug.severity === 'MEDIUM' ? 'info' : 'success';

            return `
                <div class="list-item">
                    <div class="list-item-content">
                        <div class="list-item-title">
                            🐞 ${escapeHtml(bug.title || 'Bug Report')}
                            <span class="badge ${sevClass}">${bug.severity}</span>
                        </div>
                        <div class="list-item-meta">
                            <strong>${escapeHtml(bug.deviceid || 'UNKNOWN')}</strong><br>
                            ${escapeHtml(bug.description || 'No description provided')}<br>
                            App: ${bug.app_version || 'unknown'} | OS: ${bug.os_version || 'unknown'}<br>
                            ${bug.created_at_uae
                    ? new Date(bug.created_at_uae).toLocaleString()
                    : 'Unknown time'}
                    </div>
                    </div>
                    <div class="list-item-actions">
                        ${bug.has_screenshot
                    ? `<a class="btn btn-sm" href="${BACKEND}/bug-reports/${bug.id}/screenshot" target="_blank">
                                   <i class="fas fa-image"></i>
                               </a>`
                    : ''}
                    </div>
                </div>
            `;
        }).join('');

    } catch (e) {
        console.error('Bug load error', e);
        showNotification("Bug dashboard error", "danger");
    }
}

async function loadAnomalies() {
    if (!playbackPoints.length) {
        showNotification("Load data first", "warning");
        return;
    }

    const box = document.getElementById('anomaliesBox');
    if (!box) return;

    const anomalies = [];

    for (let i = 1; i < playbackPoints.length; i++) {
        const prev = playbackPoints[i - 1];
        const curr = playbackPoints[i];

        const dist = Math.hypot(curr.lat - prev.lat, curr.lon - prev.lon);
        const speedJump = Math.abs((curr.speed || 0) - (prev.speed || 0));

        if (speedJump > 10) {
            anomalies.push(`⚠ Speed spike at ${formatTime(curr.timestamp)}`);
        }

        if (dist > 0.001) {
            anomalies.push(`⚠ GPS jump at ${formatTime(curr.timestamp)}`);
        }
    }

    box.textContent = anomalies.length
        ? anomalies.join('\n')
        : 'No anomalies detected';
}

// ANNOUNCEMENT MODAL GLOBAL FUNCTIONS
function openAnnouncementModal() {
    const modal = document.getElementById('announcementModal');
    if (modal) modal.classList.add('active');

    // Optional: prefill target device dropdown using current devices
    const sel = document.getElementById('annDevice');
    const deviceSelector = document.getElementById('deviceSelector');
    if (sel && deviceSelector) {
        sel.innerHTML = '';
        const broadcastOpt = document.createElement('option');
        broadcastOpt.value = '';
        broadcastOpt.textContent = 'Broadcast (all devices)';
        sel.appendChild(broadcastOpt);

        Array.from(deviceSelector.options).forEach(opt => {
            if (!opt.value) return;
            const o = document.createElement('option');
            o.value = opt.value;
            o.textContent = opt.value;
            sel.appendChild(o);
        });
    }
}

function closeAnnouncementModal() {
    const modal = document.getElementById('announcementModal');
    if (modal) modal.classList.remove('active');

    const title = document.getElementById('annTitle');
    const msg = document.getElementById('annMessage');
    if (title) title.value = '';
    if (msg) msg.value = '';
}

// -------------------- SETTINGS MODAL --------------------
function openSettingsModal() {
    const modal = document.getElementById('settingsModal');
    if (modal) modal.classList.add('active');
}

function closeSettingsModal() {
    const modal = document.getElementById('settingsModal');
    if (modal) modal.classList.remove('active');
}

function saveSettings() {
    const refresh = parseInt(document.getElementById('settingRefresh').value) || 30;
    const follow = document.getElementById('settingFollow').value === 'true';
    const theme = document.getElementById('settingTheme').value;
    const notifications = document.getElementById('settingNotifs').checked;

    // Apply & Persist
    autoFollow = follow;
    const followToggle = document.getElementById('toggleFollow');
    if (followToggle) followToggle.checked = follow;

    setRefreshInterval(refresh);
    applyTheme(theme);

    localStorage.setItem('watchmen_refresh', refresh);
    localStorage.setItem('watchmen_theme', theme);

    showNotification('Settings saved & applied', 'success');
    closeSettingsModal();
}

let refreshTimer = null;
function setRefreshInterval(seconds) {
    if (refreshTimer) clearInterval(refreshTimer);
    const ms = seconds * 1000;
    refreshTimer = setInterval(() => {
        console.log('🔄 Auto-refreshing dashboard data...');
        loadData();
    }, ms);
}

function applyTheme(themeName) {
    document.body.className = ''; // Reset
    if (themeName !== 'elite-dark') {
        document.body.classList.add(`theme-${themeName}`);
    }
}

// Initial application of saved settings
window.addEventListener('load', () => {
    const savedTheme = localStorage.getItem('watchmen_theme') || 'elite-dark';
    const savedRefresh = parseInt(localStorage.getItem('watchmen_refresh')) || 30;

    applyTheme(savedTheme);
    setRefreshInterval(savedRefresh);

    // Sync UI
    const themeSel = document.getElementById('settingTheme');
    if (themeSel) themeSel.value = savedTheme;
    const refreshInput = document.getElementById('settingRefresh');
    if (refreshInput) refreshInput.value = savedRefresh;
});




async function openGeofenceManager() {
    await loadGeofences();

    const container = document.getElementById('geofenceListModal');
    if (!container) return;

    container.innerHTML = ``;

    if (geofences.length === 0) {
        container.innerHTML = `<p style="color: var(--text-secondary); text-align: center">No geofences</p>`;
    }

    geofences.forEach(gf => {
        const item = document.createElement('div');
        item.className = 'list-item';

        const safeName = escapeHtml(gf.name);
        const statusBadge = gf.enabled ? '<span class="badge success">Active</span>' : '<span class="badge">Disabled</span>';

        item.innerHTML = `
            <div class="list-item-content">
                <div class="list-item-title">${safeName}</div>
                <div class="list-item-meta">
                    Type: ${gf.type} | Radius: ${gf.radius}m<br>
                    ${statusBadge}
                </div>
            </div>
            <div class="list-item-actions">
                <button class="btn btn-sm" onclick="toggleGeofence(${gf.id}, ${!gf.enabled})">
                    <i class="fas fa-${gf.enabled ? 'toggle-on' : 'toggle-off'}"></i>
                </button>
                <button class="btn btn-sm btn-danger" onclick="deleteGeofence(${gf.id})">
                    <i class="fas fa-trash"></i>
                </button>
            </div>
        `;
        container.appendChild(item);
    });

    const modal = document.getElementById('geofenceModal');
    if (modal) modal.classList.add('active');
}

window.saveGeofence = async function () {
    if (!tempLayer) {
        showNotification('No geofence drawn', 'warning');
        return;
    }

    const nameInput = document.getElementById('geofenceName');
    const radiusInput = document.getElementById('geofenceRadius');
    const colorInput = document.getElementById('geofenceColor');

    const name = nameInput ? nameInput.value.trim() : '';
    const radius = radiusInput ? parseFloat(radiusInput.value) : 100;
    const color = colorInput ? colorInput.value : '#00f5ff';


    if (!name) {
        showNotification('Enter a zone name', 'warning');
        return;
    }

    const center = tempLayer.getLatLng();
    const payload = {
        name,
        type: 'circle',
        latitude: center.lat,
        longitude: center.lng,
        radius,
        color: `#${color.replace('#', '')}`,
        enabled: true
    };

    try {
        const response = await fetch(`${BACKEND}/geofences`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (response.ok) {
            showNotification(`Geofence "${name}" created!`, 'success');
            tempLayer.addTo(map);
            tempLayer = null;

            const modal = document.getElementById('createGeofenceModal');
            if (modal) modal.classList.remove('active');
            if (nameInput) nameInput.value = '';

            loadGeofences();
        } else {
            showNotification('Failed to create geofence', 'danger');
        }
    } catch (error) {
        console.error('Error saving geofence:', error);
        showNotification('Failed to save geofence', 'danger');
    }
};

window.closeCreateModal = function () {
    const modal = document.getElementById('createGeofenceModal');
    if (modal) modal.classList.remove('active');
    if (tempLayer) {
        map.removeLayer(tempLayer);
        tempLayer = null;
    }
    if (drawControl) {
        try { map.addControl(drawControl); } catch (e) { }
    }


};

window.closeGeofenceModal = function () {
    const modal = document.getElementById('geofenceModal');
    if (modal) modal.classList.remove('active');
};

console.log('✅ Watchmen Tracker dashboard.js loaded');
// -------------------- COMMAND & CHAT --------------------
// ✅ NEW: Fetch list of connected devices from backend
async function fetchConnectedDevices() {
    try {
        const response = await fetch('/devices/connected');
        if (response.ok) {
            const data = await response.json();
            connectedDevices = new Set(data.connected_devices);
            console.log(`📱 ${data.count} devices connected:`, data.connected_devices);
            return data.connected_devices;
        }
    } catch (e) {
        console.error('Error fetching connected devices:', e);
    }
    return [];
}

async function sendCommand(command) {
    if (!currentDeviceId) {
        alert("Please select a device first!");
        return;
    }

    if (!confirm(`Are you sure you want to send ${command} to ${currentDeviceId}?`)) return;

    try {
        const response = await fetch(`/device/${currentDeviceId}/command`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: command })
        });

        const res = await response.json().catch(() => ({}));
        if (response.ok && res.status === 'sent') {
            appendChatMessage(`COMMAND SENT: ${command}`, 'sent');
        } else {
            const msg = typeof res.detail === 'string' ? res.detail : (Array.isArray(res.detail) ? res.detail.map(d => d.msg || JSON.stringify(d)).join(' ') : res.detail);
            alert("Failed: " + (msg || response.statusText || "Unknown error"));
        }
    } catch (e) {
        console.error(e);
        alert("Error sending command");
    }
}

async function sendMessage() {
    const input = document.getElementById('chatInput');
    const msg = input.value.trim();
    if (!msg) return;

    if (!currentDeviceId) {
        showNotification("Please select a device to chat with!", "warning");
        return;
    }

    // ✅ Check if device is actually connected
    if (!connectedDevices.has(currentDeviceId)) {
        showNotification(`Device ${currentDeviceId} is offline or disconnected`, "danger");
        return;
    }

    try {
        const response = await fetch(`/device/${currentDeviceId}/message`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: msg, urgent: true })
        });

        if (response.ok) {
            const res = await response.json();
            if (res.status === 'sent') {
                appendChatMessage(msg, 'sent');
                input.value = '';
                showNotification("Message sent successfully", "success");
            } else {
                showNotification("Failed: " + (res.detail || "Unknown error"), "danger");
            }
        } else if (response.status === 503) {
            showNotification(`Device ${currentDeviceId} is offline`, "danger");
        } else if (response.status === 400) {
            showNotification("Invalid message format", "warning");
        } else {
            const errorData = await response.json().catch(() => ({}));
            showNotification("Error: " + (errorData.detail || response.statusText), "danger");
        }
    } catch (e) {
        console.error(e);
        showNotification("Network error: Unable to reach server", "danger");
    }
}

// Ensure functions are global for onclick handlers
window.sendCommand = sendCommand;
window.sendMessage = sendMessage;

function appendChatMessage(text, type) {
    const container = document.getElementById('chatMessages');
    const div = document.createElement('div');
    div.className = `chat-msg ${type}`;
    div.innerHTML = `
        ${escapeHtml(text)}
        <div style="font-size:0.7rem; opacity:0.7; margin-top:4px;">
            ${type === 'sent' ? 'Operator' : currentDeviceId} • ${new Date().toLocaleTimeString()}
        </div>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

// Ensure chat input works with Enter key
document.getElementById('chatInput')?.addEventListener('keypress', function (e) {
    if (e.key === 'Enter') sendMessage();
});
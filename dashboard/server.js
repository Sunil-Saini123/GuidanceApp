import express from 'express';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { createServer as createViteServer } from 'vite';
import { exec } from 'child_process';
import path from 'path';

const PORT = 3000;
const PHONE_TIMEOUT_MS = 4000; // 4 seconds without data = disconnected
const MAX_HISTORY = 300; // Keep last 300 telemetry events in memory

// In-memory event buffer so browser refreshes NEVER start from zero
const eventHistory = [];

// Resolve Android SDK adb path automatically
const adbPath = process.env.LOCALAPPDATA
  ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
  : 'adb';

// Cached nav_graph (updated from phone SQLite via ADB & telemetry)
let cachedNavGraph = null;
let lastNavGraphSyncTime = 0;

function fetchNavGraphFromDevice() {
  return new Promise((resolve) => {
    exec(`"${adbPath}" shell "run-as com.example.floatingassistant cat files/nav_graph.json"`, { maxBuffer: 15 * 1024 * 1024 }, (err, stdout) => {
      if (!err && stdout && stdout.trim().startsWith('{')) {
        try {
          cachedNavGraph = JSON.parse(stdout.trim());
          lastNavGraphSyncTime = Date.now();
          console.log(`[Dashboard Server] nav_graph loaded (${cachedNavGraph.total_apps || cachedNavGraph.apps?.length || 0} apps)`);
          resolve(cachedNavGraph);
          return;
        } catch (_) {}
      }
      // [PERMANENT FIX] Do not remove this! Preserve cached nav_graph when device is temporarily disconnected.
      // If ADB fails because phone is unplugged or sleeping, keep previously loaded cachedNavGraph instead of wiping it.
      if (!cachedNavGraph) {
        cachedNavGraph = { apps: [], total_apps: 0, total_screens: 0, total_transitions: 0 };
      }
      lastNavGraphSyncTime = Date.now();
      resolve(cachedNavGraph);
    });
  });
}

// Initial fetch on server start
fetchNavGraphFromDevice();

// Auto-run ADB reverse so the user never has to type it manually
function maintainAdbTunnel() {
  exec(`"${adbPath}" reverse tcp:${PORT} tcp:${PORT}`, (err) => {
    if (!err) {
      // Tunnel active
    }
  });
}

maintainAdbTunnel();
setInterval(maintainAdbTunnel, 5000); // Check every 5s in case phone re-plugged

let lastPhoneEventTime = 0;

async function startServer() {
  const app = express();
  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws' });

  // Store active browser sockets
  const clients = new Set();

  // Broadcast helper
  function broadcast(event) {
    const payload = JSON.stringify(event);
    for (const client of clients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(payload);
      }
    }
  }

  wss.on('connection', (ws) => {
    clients.add(ws);
    console.log('[Dashboard Server] Browser client connected. Total clients:', clients.size);

    const isPhoneAlive = (Date.now() - lastPhoneEventTime) < PHONE_TIMEOUT_MS;

    // 1. Send initial handshake with real device status
    ws.send(JSON.stringify({
      type: 'DEVICE_STATUS',
      phoneConnected: isPhoneAlive,
      lastSeen: lastPhoneEventTime,
      timestamp: Date.now()
    }));

    // 2. Immediately send event history so refreshing browser NEVER resets to zero!
    if (eventHistory.length > 0) {
      ws.send(JSON.stringify({
        type: 'HISTORY_BATCH',
        events: eventHistory
      }));
    }

    // 3. Send cached nav_graph so the browser immediately has the full app navigation tree
    if (cachedNavGraph) {
      ws.send(JSON.stringify({
        type: 'NAV_GRAPH_DATA',
        navGraph: cachedNavGraph
      }));
    }

    ws.on('close', () => {
      clients.delete(ws);
      console.log('[Dashboard Server] Browser client disconnected.');
    });
  });

  // Heartbeat monitor: tells browser every 1 second whether phone is actively streaming
  setInterval(() => {
    const now = Date.now();
    const isPhoneAlive = lastPhoneEventTime > 0 && (now - lastPhoneEventTime) < PHONE_TIMEOUT_MS;
    broadcast({
      type: 'DEVICE_STATUS',
      phoneConnected: isPhoneAlive,
      lastSeen: lastPhoneEventTime,
      timestamp: now
    });
  }, 1000);

  // Parse JSON payloads from Android App
  app.use(express.json({ limit: '10mb' }));

  // CORS support
  app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.header('Access-Control-Allow-Headers', 'Content-Type');
    if (req.method === 'OPTIONS') return res.sendStatus(200);
    next();
  });

  // Health check endpoint
  app.get('/api/health', (req, res) => {
    const isPhoneAlive = (Date.now() - lastPhoneEventTime) < PHONE_TIMEOUT_MS;
    res.json({
      status: 'online',
      phoneConnected: isPhoneAlive,
      connectedClients: clients.size,
      lastSeen: lastPhoneEventTime,
      historyCount: eventHistory.length,
      timestamp: Date.now()
    });
  });

  // Clear history endpoint
  app.post('/api/clear', (req, res) => {
    eventHistory.length = 0;
    cachedNavGraph = { apps: [], total_apps: 0, total_screens: 0, total_transitions: 0 };
    broadcast({ type: 'CLEAR_HISTORY' });
    broadcast({
      type: 'NAV_GRAPH_DATA',
      navGraph: cachedNavGraph,
      data: cachedNavGraph
    });
    console.log('[Dashboard Server] Event history and nav_graph cleared by user');
    res.json({ cleared: true });
  });

  // Nav Graph endpoint (returns full app navigation tree from nav_graph.json)
  app.get('/api/nav_graph', async (req, res) => {
    // Refresh from phone if older than 3 seconds
    if (!cachedNavGraph || (Date.now() - lastNavGraphSyncTime) > 3000) {
      await fetchNavGraphFromDevice();
    }
    res.json(cachedNavGraph || { apps: [], total_apps: 0, total_screens: 0, total_transitions: 0 });
  });

  // [PERMANENT FIX] Do not remove this! Direct nav_graph update endpoint for real-time dashboard sync.
  app.post('/api/nav_graph', (req, res) => {
    if (req.body && (req.body.apps || req.body.total_screens)) {
      cachedNavGraph = req.body;
      lastNavGraphSyncTime = Date.now();
      broadcast({
        type: 'NAV_GRAPH_DATA',
        navGraph: cachedNavGraph,
        data: cachedNavGraph
      });
      return res.json({ success: true, apps: cachedNavGraph.apps?.length || 0 });
    }
    res.status(400).json({ error: 'Invalid nav_graph payload' });
  });

  // Telemetry ingest endpoint called continuously by Android app
  app.post('/api/telemetry', (req, res) => {
    const event = req.body;
    if (!event || !event.type) {
      return res.status(400).json({ error: 'Missing event payload or type' });
    }

    lastPhoneEventTime = Date.now();
    event.phoneConnected = true;

    if (!event.timestamp) {
      event.timestamp = lastPhoneEventTime;
    }

    if (!event.id) {
      event.id = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    }

    // [PERMANENT FIX] Do not remove this! Persist telemetry events in buffer (exempt HEARTBEAT pings from flooding the buffer and evicting real decision/screen events).
    if (event.type !== 'HEARTBEAT') {
      eventHistory.push(event);
      if (eventHistory.length > MAX_HISTORY) {
        eventHistory.shift();
      }
    }

    // Broadcast immediately to all connected browsers
    broadcast(event);

    // If phone signals CLEAR_GRAPH, immediately reset cached graph and broadcast empty tree to all browsers
    if (event.type === 'CLEAR_GRAPH') {
      cachedNavGraph = { apps: [], total_apps: 0, total_screens: 0, total_transitions: 0 };
      broadcast({
        type: 'NAV_GRAPH_DATA',
        navGraph: cachedNavGraph,
        data: cachedNavGraph
      });
      broadcast({ type: 'CLEAR_HISTORY' });
      console.log('[Dashboard Server] CLEAR_GRAPH received from phone. Emitted empty tree to all browsers.');
    }

    // [PERMANENT FIX] Do not remove this! Sync nav_graph on screen transitions and mediator scroll deltas.
    if (event.type === 'SCREEN_CHANGED' || (event.type === 'MEDIATOR_DIFF' && event.mediatorData?.action === 'DELTA_ADDED')) {
      setTimeout(async () => {
        const g = await fetchNavGraphFromDevice();
        if (g) {
          broadcast({
            type: 'NAV_GRAPH_DATA',
            navGraph: g
          });
        }
      }, 1500);
    }

    res.json({ received: true });
  });

  // Attach Vite development server as middleware
  const vite = await createViteServer({
    server: { middlewareMode: true },
    appType: 'spa',
  });
  app.use(vite.middlewares);

  server.listen(PORT, '0.0.0.0', () => {
    console.log('\n======================================================');
    console.log(`🚀 GuidanceApp Dashboard is LIVE at: http://localhost:${PORT}`);
    console.log(`📡 Ingest endpoint: http://localhost:${PORT}/api/telemetry`);
    console.log(`🔌 WebSocket feed: ws://localhost:${PORT}/ws`);
    console.log(`⚡ Auto ADB Reverse: Active (monitoring USB connection)`);
    console.log('======================================================\n');
  });
}

startServer().catch((err) => {
  console.error('[Dashboard Server] Fatal start error:', err);
});

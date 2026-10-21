import express from 'express';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { createServer as createViteServer } from 'vite';
import { exec } from 'child_process';
import path from 'path';

const PORT = 3000;
const PHONE_TIMEOUT_MS = 4000; // 4 seconds without data = disconnected

// Resolve Android SDK adb path automatically
const adbPath = process.env.LOCALAPPDATA
  ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
  : 'adb';

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

    // Send initial handshake with real device status
    ws.send(JSON.stringify({
      type: 'DEVICE_STATUS',
      phoneConnected: isPhoneAlive,
      lastSeen: lastPhoneEventTime,
      timestamp: Date.now()
    }));

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
      timestamp: Date.now()
    });
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

    // Broadcast immediately to all connected browsers
    broadcast(event);

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

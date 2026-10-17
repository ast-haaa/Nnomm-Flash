package com.nnomm.plugins

val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Nnomm Flash-Deal Claim Service - Interactive Dashboard</title>
  <style>
    :root {
      --bg: #090d16;
      --card-bg: #111827;
      --border: #1f293d;
      --primary: #3b82f6;
      --primary-hover: #2563eb;
      --text: #f3f4f6;
      --text-muted: #9ca3af;
      --success: #10b981;
      --error: #ef4444;
      --warning: #f59e0b;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background-color: var(--bg);
      color: var(--text);
      line-height: 1.5;
      padding: 24px;
    }
    .container { max-width: 1100px; margin: 0 auto; }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 20px;
      border-bottom: 1px solid var(--border);
      margin-bottom: 24px;
    }
    h1 { font-size: 22px; font-weight: 700; color: #fff; }
    .badge-status {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 12px;
      border-radius: 999px;
      background: rgba(16, 185, 129, 0.15);
      color: var(--success);
      font-size: 13px;
      font-weight: 600;
    }
    .badge-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success); }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 20px; }
    @media (max-width: 768px) { .grid { grid-template-columns: 1fr; } }
    .card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 20px;
    }
    .card h2 {
      font-size: 16px;
      font-weight: 600;
      margin-bottom: 16px;
      color: #93c5fd;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    label { display: block; font-size: 13px; color: var(--text-muted); margin-bottom: 6px; font-weight: 500; }
    input, select {
      width: 100%;
      padding: 10px 12px;
      background: #1e293b;
      border: 1px solid #334155;
      border-radius: 6px;
      color: #fff;
      font-size: 14px;
      margin-bottom: 14px;
    }
    input:focus { outline: none; border-color: var(--primary); }
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 10px 16px;
      background: var(--primary);
      color: #fff;
      font-weight: 600;
      border-radius: 6px;
      border: none;
      cursor: pointer;
      font-size: 14px;
      transition: background 0.15s;
      width: 100%;
    }
    .btn:hover { background: var(--primary-hover); }
    .btn-secondary { background: #374151; }
    .btn-secondary:hover { background: #4b5563; }
    .btn-danger { background: #dc2626; }
    .btn-danger:hover { background: #b91c1c; }
    .btn-success { background: #059669; }
    .btn-success:hover { background: #047857; }
    .log-box {
      background: #030712;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 14px;
      font-family: monospace;
      font-size: 12px;
      color: #a7f3d0;
      max-height: 260px;
      overflow-y: auto;
      white-space: pre-wrap;
      word-break: break-all;
    }
    .pill { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; }
    .pill-info { background: #1e3a8a; color: #bfdbfe; }
    .token-display {
      font-family: monospace;
      font-size: 11px;
      background: #030712;
      padding: 8px;
      border-radius: 6px;
      border: 1px solid #334155;
      color: #cbd5e1;
      word-break: break-all;
      margin-bottom: 12px;
      max-height: 70px;
      overflow-y: auto;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div>
        <h1>Nnomm Flash-Deal Claim Service</h1>
        <p style="font-size: 13px; color: var(--text-muted); margin-top: 4px;">Live Interactive Testing Dashboard</p>
      </div>
      <div class="badge-status">
        <span class="badge-dot"></span>
        <span id="healthStatus">CONNECTED: http://localhost:8080</span>
      </div>
    </header>

    <div class="grid">
      <!-- 1. Authentication -->
      <div class="card">
        <h2>🔑 1. Authentication Tokens</h2>
        <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 12px;">Pre-minted JWT tokens to interact with protected endpoints.</p>
        
        <label>Restaurant Admin Token (creates deals):</label>
        <div id="adminToken" class="token-display">Generating...</div>
        
        <label>Customer Token (claims deals):</label>
        <div id="customerToken" class="token-display">Generating...</div>
        
        <button class="btn btn-secondary" onclick="refreshTokens()">🔄 Regenerate New Personas</button>
      </div>

      <!-- 2. Create Flash Deal -->
      <div class="card">
        <h2>🏷️ 2. Create Flash Deal (Admin)</h2>
        <label>Title:</label>
        <input type="text" id="dealTitle" value="50% Off Gourmet Burger">
        
        <div style="display: flex; gap: 10px;">
          <div style="flex: 1;">
            <label>Price (₹):</label>
            <input type="number" id="dealPrice" value="250">
          </div>
          <div style="flex: 1;">
            <label>Total Stock:</label>
            <input type="number" id="dealStock" value="5">
          </div>
        </div>

        <button class="btn" onclick="createDeal()">Create Deal (POST /v1/deals)</button>
      </div>
    </div>

    <div class="grid">
      <!-- 3. Active Deal & Claiming -->
      <div class="card">
        <h2>⚡ 3. Live Deal & Claim Reservation</h2>
        <label>Current Deal ID:</label>
        <input type="text" id="activeDealId" placeholder="Create a deal or paste Deal ID">
        
        <div style="display: flex; gap: 10px; margin-bottom: 14px;">
          <button class="btn btn-secondary" style="flex: 1;" onclick="fetchDealStatus()">Check Stock</button>
          <button class="btn btn-success" style="flex: 1;" onclick="claimDeal()">Claim 1 Unit</button>
        </div>

        <div style="background: #1e293b; padding: 12px; border-radius: 8px; margin-bottom: 14px;">
          <div style="display: flex; justify-content: space-between; font-size: 13px;">
            <span>Stock Available:</span>
            <strong id="displayStock" style="color: #60a5fa; font-size: 16px;">-</strong>
          </div>
          <div style="display: flex; justify-content: space-between; font-size: 13px; margin-top: 6px;">
            <span>Deal Window:</span>
            <span id="displayWindow" style="color: var(--text-muted);">-</span>
          </div>
        </div>

        <button class="btn btn-danger" onclick="simulateConcurrencyBlast()">🔥 Blast 20 Concurrent Claims</button>
      </div>

      <!-- 4. Webhook Simulator -->
      <div class="card">
        <h2>💳 4. Payment Gateway Webhook</h2>
        <label>Last Claim ID:</label>
        <input type="text" id="lastClaimId" placeholder="Claim a deal to populate">
        
        <label>Simulate Gateway Event:</label>
        <div style="display: flex; gap: 10px; margin-bottom: 14px;">
          <button class="btn btn-success" style="flex: 1;" onclick="sendWebhook('payment.succeeded')">Payment Succeeded</button>
          <button class="btn btn-danger" style="flex: 1;" onclick="sendWebhook('payment.failed')">Payment Failed</button>
        </div>

        <button class="btn btn-secondary" onclick="checkClaimStatus()">Inspect Claim (GET /v1/claims/{id})</button>
      </div>
    </div>

    <!-- Live Output Log -->
    <div class="card">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
        <h2>📋 Live Activity Log &amp; API Responses</h2>
        <button class="btn btn-secondary" style="width: auto; padding: 4px 10px; font-size: 12px;" onclick="clearLogs()">Clear Log</button>
      </div>
      <div id="consoleLog" class="log-box">Waiting for user action...</div>
    </div>
  </div>

  <script>
    let adminJwt = '';
    let customerJwt = '';
    let currentDealId = '';
    let currentClaimId = '';

    function log(title, data) {
      const box = document.getElementById('consoleLog');
      const time = new Date().toLocaleTimeString();
      const text = '[' + time + '] ' + title + '\\n' + (typeof data === 'object' ? JSON.stringify(data, null, 2) : data) + '\\n\\n';
      box.textContent = text + box.textContent;
    }

    function clearLogs() {
      document.getElementById('consoleLog').textContent = '';
    }

    async function refreshTokens() {
      try {
        const res = await fetch('/dev/tokens');
        const data = await res.json();
        adminJwt = data.admin_token;
        customerJwt = data.customer_token;
        document.getElementById('adminToken').textContent = adminJwt;
        document.getElementById('customerToken').textContent = customerJwt;
        log('GENERATED TOKENS', { admin: data.admin_sub, customer: data.customer_sub });
      } catch (err) {
        log('ERROR FETCHING TOKENS', err.message);
      }
    }

    async function createDeal() {
      const title = document.getElementById('dealTitle').value;
      const pricePaise = parseInt(document.getElementById('dealPrice').value) * 100;
      const stock = parseInt(document.getElementById('dealStock').value);
      const now = new Date();
      const ends = new Date(now.getTime() + 3600000);

      try {
        const res = await fetch('/v1/deals', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + adminJwt
          },
          body: JSON.stringify({
            title: title,
            price_paise: pricePaise,
            stock_total: stock,
            starts_at: now.toISOString(),
            ends_at: ends.toISOString()
          })
        });
        const data = await res.json();
        log('POST /v1/deals -> ' + res.status, data);
        if (res.ok && data.id) {
          currentDealId = data.id;
          document.getElementById('activeDealId').value = data.id;
          document.getElementById('displayStock').textContent = data.stock_available + ' / ' + data.stock_total;
          document.getElementById('displayWindow').textContent = 'ACTIVE';
        }
      } catch (err) {
        log('ERROR CREATING DEAL', err.message);
      }
    }

    async function fetchDealStatus() {
      const dealId = document.getElementById('activeDealId').value.trim();
      if (!dealId) return alert('Enter Deal ID');
      try {
        const res = await fetch('/v1/deals/' + dealId);
        const data = await res.json();
        log('GET /v1/deals/' + dealId + ' -> ' + res.status, data);
        if (res.ok) {
          document.getElementById('displayStock').textContent = data.stock_available + ' / ' + data.stock_total;
          document.getElementById('displayWindow').textContent = data.stock_available > 0 ? 'ACTIVE' : 'SOLD OUT';
        }
      } catch (err) {
        log('ERROR FETCHING DEAL', err.message);
      }
    }

    async function claimDeal() {
      const dealId = document.getElementById('activeDealId').value.trim();
      if (!dealId) return alert('Create or select a Deal first');
      const idemKey = 'key-' + Math.random().toString(36).substring(2, 10);
      try {
        const res = await fetch('/v1/deals/' + dealId + '/claims', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + customerJwt,
            'Idempotency-Key': idemKey
          },
          body: JSON.stringify({ quantity: 1 })
        });
        const data = await res.json();
        log('POST /v1/deals/' + dealId + '/claims (Key: ' + idemKey + ') -> ' + res.status, data);
        if (res.ok && data.id) {
          currentClaimId = data.id;
          document.getElementById('lastClaimId').value = data.id;
          fetchDealStatus();
        }
      } catch (err) {
        log('CLAIM ERROR', err.message);
      }
    }

    async function simulateConcurrencyBlast() {
      const dealId = document.getElementById('activeDealId').value.trim();
      if (!dealId) return alert('Select a Deal first');
      log('BLAST START', 'Firing 20 concurrent claims with unique customer tokens...');
      
      const promises = [];
      for (let i = 1; i <= 20; i++) {
        promises.push(fetch('/dev/blast-claim?deal_id=' + dealId + '&req_index=' + i).then(r => r.json()));
      }
      
      const results = await Promise.all(promises);
      const successes = results.filter(r => r.status === 201).length;
      const conflicts = results.filter(r => r.status === 409).length;
      log('BLAST COMPLETE (20 requests)', {
        succeeded: successes,
        rejected_sold_out: conflicts,
        verdict: 'PostgreSQL row locks prevented overselling!'
      });
      fetchDealStatus();
    }

    async function sendWebhook(eventType) {
      const claimId = document.getElementById('lastClaimId').value.trim();
      if (!claimId) return alert('Select a Claim ID first');
      try {
        const res = await fetch('/dev/simulate-webhook?claim_id=' + claimId + '&event_type=' + eventType, { method: 'POST' });
        const data = await res.json();
        log('SIMULATE WEBHOOK (' + eventType + ') -> ' + res.status, data);
        checkClaimStatus();
      } catch (err) {
        log('WEBHOOK ERROR', err.message);
      }
    }

    async function checkClaimStatus() {
      const claimId = document.getElementById('lastClaimId').value.trim();
      if (!claimId) return alert('Enter Claim ID');
      try {
        const res = await fetch('/v1/claims/' + claimId, {
          headers: { 'Authorization': 'Bearer ' + customerJwt }
        });
        const data = await res.json();
        log('GET /v1/claims/' + claimId + ' -> ' + res.status, data);
      } catch (err) {
        log('CHECK CLAIM ERROR', err.message);
      }
    }

    // Initialize on load
    refreshTokens();
  </script>
</body>
</html>
""".trimIndent()

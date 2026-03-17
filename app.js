// =================================================================
//  app.js — Bank Fraud Detection System
//  Shared JS used by all HTML pages
// =================================================================

const API = "http://localhost:8080";

// ── Session ───────────────────────────────────────────────────────

function saveSession(acc) {
    sessionStorage.setItem("account", acc);
}

function getAccount() {
    return sessionStorage.getItem("account");
}

function requireLogin() {
    if (!getAccount()) window.location.href = "login.html";
}

function logout() {
    sessionStorage.clear();
    window.location.href = "login.html";
}

// ── API Calls ─────────────────────────────────────────────────────

async function apiPost(endpoint, data) {
    try {
        const res = await fetch(API + endpoint, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data)
        });
        return await res.json();
    } catch (e) {
        return { success: false, message: "Cannot connect to Java server. Make sure BankServer is running." };
    }
}

async function apiGet(endpoint) {
    try {
        const res = await fetch(API + endpoint);
        return await res.json();
    } catch (e) {
        return null;
    }
}

// ── Fraud Alert Popup ─────────────────────────────────────────────

function showAlert(risk, amount) {
    const overlay = document.getElementById("alertOverlay");
    const box     = document.getElementById("alertBox");
    const emoji   = document.getElementById("alertEmoji");
    const title   = document.getElementById("alertTitle");
    const msg     = document.getElementById("alertMsg");

    const amt = formatRupee(amount);

    if (risk === "HIGH") {
        box.className     = "alert-box high";
        emoji.textContent = "🚨";
        title.textContent = "HIGH FRAUD RISK DETECTED";
        title.style.color = "#E74C3C";
        msg.textContent   = "Transaction of " + amt + " has been flagged as HIGH RISK and logged for review.";
        playBuzzer();
    } else if (risk === "MEDIUM") {
        box.className     = "alert-box medium";
        emoji.textContent = "⚠️";
        title.textContent = "Medium Risk Warning";
        title.style.color = "#F39C12";
        msg.textContent   = "Transaction of " + amt + " shows unusual activity. Proceed carefully.";
        playBeep();
    } else {
        box.className     = "alert-box safe";
        emoji.textContent = "✅";
        title.textContent = "Transaction Approved";
        title.style.color = "#2ECC71";
        msg.textContent   = "Transaction of " + amt + " completed successfully. No fraud detected.";
    }

    overlay.classList.add("show");
}

function closeAlert() {
    document.getElementById("alertOverlay").classList.remove("show");
}

// ── Buzzer Sound (Web Audio API) ──────────────────────────────────

function playBuzzer() {
    try {
        const ctx = new (window.AudioContext || window.webkitAudioContext)();
        // 4 loud repeating beeps
        [0, 0.30, 0.60, 0.90].forEach(function(t) {
            var osc = ctx.createOscillator();
            var g   = ctx.createGain();
            osc.connect(g);
            g.connect(ctx.destination);
            osc.frequency.value = 880;
            g.gain.setValueAtTime(0.5, ctx.currentTime + t);
            g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + t + 0.25);
            osc.start(ctx.currentTime + t);
            osc.stop(ctx.currentTime + t + 0.28);
        });
    } catch (e) { /* audio blocked */ }
}

function playBeep() {
    try {
        const ctx = new (window.AudioContext || window.webkitAudioContext)();
        var osc = ctx.createOscillator();
        var g   = ctx.createGain();
        osc.connect(g);
        g.connect(ctx.destination);
        osc.frequency.value = 660;
        g.gain.setValueAtTime(0.25, ctx.currentTime);
        g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.45);
        osc.start(ctx.currentTime);
        osc.stop(ctx.currentTime + 0.48);
    } catch (e) { /* audio blocked */ }
}

// ── Live Risk Bar (transfer page) ─────────────────────────────────

function updateRiskBar(amount) {
    var bar   = document.getElementById("riskBar");
    var label = document.getElementById("riskLabel");
    if (!bar || !label) return;

    amount = parseFloat(amount) || 0;

    var risk, pct, color;
    if (amount <= 0) {
        bar.style.width = "0";
        label.textContent = "Enter amount to see risk";
        label.style.color = "#999";
        return;
    } else if (amount > 80000) {
        risk = "HIGH";   pct = 90; color = "#E74C3C";
    } else if (amount > 20000) {
        risk = "MEDIUM"; pct = 55; color = "#F39C12";
    } else {
        risk = "SAFE";   pct = 15; color = "#2ECC71";
    }

    bar.style.width      = pct + "%";
    bar.style.background = color;
    label.textContent    = "Predicted Risk: " + risk;
    label.style.color    = color;
}

// ── Helpers ───────────────────────────────────────────────────────

function formatRupee(n) {
    return "Rs. " + Number(n).toLocaleString("en-IN", { minimumFractionDigits: 2 });
}

function formatDate(str) {
    if (!str) return "-";
    return new Date(str).toLocaleString("en-IN");
}

function riskBadge(risk) {
    if (!risk) return "";
    var cls = risk === "HIGH" ? "badge-high" : risk === "MEDIUM" ? "badge-medium" : "badge-safe";
    return '<span class="badge ' + cls + '">' + risk + '</span>';
}

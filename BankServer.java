import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.Executors;

public class BankServer {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api/login",          new LoginHandler());
        server.createContext("/api/dashboard",      new DashboardHandler());
        server.createContext("/api/transaction",    new TransactionHandler());
        server.createContext("/api/transfer",       new TransferHandler());
        server.createContext("/api/history",        new HistoryHandler());
        server.createContext("/api/beneficiaries",  new BeneficiariesHandler());
        server.createContext("/api/addBeneficiary", new AddBeneficiaryHandler());

        server.setExecutor(Executors.newFixedThreadPool(5));
        server.start();

        System.out.println("===========================================");
        System.out.println("  Bank Fraud Detection System");
        System.out.println("  Server: http://localhost:8080");
        System.out.println("  Open login.html in your browser");
        System.out.println("===========================================");
    }

    // ── Shared helpers ────────────────────────────────────────────

    static void send(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type",                 "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }

    static boolean cors(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static String get(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        i = json.indexOf(":", i) + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int e = i;
        while (e < json.length() && json.charAt(e) != '"' && json.charAt(e) != ',' && json.charAt(e) != '}') e++;
        return json.substring(i, e).trim();
    }

    static String param(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return "";
        for (String p : q.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return "";
    }

    // ── POST /api/login ───────────────────────────────────────────
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;
            String body = body(ex);
            String acc  = get(body, "accountNumber").toUpperCase().trim();
            String pin  = get(body, "pin").trim();

            if (Main.validateLogin(acc, pin)) {
                String name = "";
                try (Connection con = Main.connect()) {
                    PreparedStatement ps = con.prepareStatement(
                        "SELECT c.name FROM customers c " +
                        "JOIN accounts a ON a.customer_id = c.customer_id " +
                        "WHERE a.account_number = ?");
                    ps.setString(1, acc);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) name = rs.getString("name");
                } catch (Exception e) { e.printStackTrace(); }

                send(ex, 200,
                    "{\"success\":true,\"accountNumber\":\"" + acc + "\",\"name\":\"" + name + "\"}");
            } else {
                send(ex, 401,
                    "{\"success\":false,\"message\":\"Invalid account number or PIN\"}");
            }
        }
    }

    // ── GET /api/dashboard?account=ACC1001 ────────────────────────
    static class DashboardHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;

            String acc     = param(ex, "account").toUpperCase().trim();
            double balance = Main.getBalance(acc);
            double deposits = 0, withdrawals = 0;
            int    alerts   = 0;

            try (Connection con = Main.connect()) {

                // Today deposits
                PreparedStatement d = con.prepareStatement(
                    "SELECT IFNULL(SUM(amount), 0) FROM transactions " +
                    "WHERE account_number = ? AND transaction_type = 'DEPOSIT' " +
                    "AND DATE(transaction_time) = CURDATE()");
                d.setString(1, acc);
                ResultSet rd = d.executeQuery();
                if (rd.next()) deposits = rd.getDouble(1);

                // Today withdrawals + transfers
                PreparedStatement w = con.prepareStatement(
                    "SELECT IFNULL(SUM(amount), 0) FROM transactions " +
                    "WHERE account_number = ? AND transaction_type IN ('WITHDRAW', 'TRANSFER') " +
                    "AND DATE(transaction_time) = CURDATE()");
                w.setString(1, acc);
                ResultSet rw = w.executeQuery();
                if (rw.next()) withdrawals = rw.getDouble(1);

                // ── FIXED: use alert_time not created_at ──────────
                PreparedStatement a = con.prepareStatement(
                    "SELECT COUNT(*) FROM fraud_alerts " +
                    "WHERE account_number = ? AND DATE(alert_time) = CURDATE()");
                a.setString(1, acc);
                ResultSet ra = a.executeQuery();
                if (ra.next()) alerts = ra.getInt(1);

            } catch (Exception e) { e.printStackTrace(); }

            send(ex, 200, String.format(
                "{\"balance\":%.2f,\"deposits\":%.2f,\"withdrawals\":%.2f,\"alerts\":%d}",
                balance, deposits, withdrawals, alerts));
        }
    }

    // ── POST /api/transaction  (deposit or withdraw) ──────────────
    static class TransactionHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;
            try {
                String body   = body(ex);
                String acc    = get(body, "account").toUpperCase().trim();
                String type   = get(body, "type").toUpperCase().trim();
                double amount = Double.parseDouble(get(body, "amount"));

                if (type.equals("WITHDRAWAL")) type = "WITHDRAW";

                String risk = Main.processTransaction(acc, amount, type);

                switch (risk) {
                    case "INVALID":
                        send(ex, 404, "{\"success\":false,\"message\":\"Account not found\"}");
                        break;
                    case "INSUFFICIENT":
                        send(ex, 400, "{\"success\":false,\"message\":\"Insufficient balance\"}");
                        break;
                    default:
                        send(ex, 200, "{\"success\":true,\"risk\":\"" + risk + "\"}");
                }
            } catch (Exception e) {
                send(ex, 500, "{\"success\":false,\"message\":\"Server error: " + e.getMessage() + "\"}");
            }
        }
    }

    // ── POST /api/transfer ────────────────────────────────────────
    static class TransferHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;
            try {
                String body   = body(ex);
                String acc    = get(body, "account").toUpperCase().trim();
                double amount = Double.parseDouble(get(body, "amount"));

                String risk = Main.processTransaction(acc, amount, "TRANSFER");

                switch (risk) {
                    case "INVALID":
                        send(ex, 404, "{\"success\":false,\"message\":\"Account not found\"}");
                        break;
                    case "INSUFFICIENT":
                        send(ex, 400, "{\"success\":false,\"message\":\"Insufficient balance\"}");
                        break;
                    default:
                        send(ex, 200, "{\"success\":true,\"risk\":\"" + risk + "\"}");
                }
            } catch (Exception e) {
                send(ex, 500, "{\"success\":false,\"message\":\"Server error: " + e.getMessage() + "\"}");
            }
        }
    }

    // ── GET /api/history?account=ACC1001&period=today ─────────────
    static class HistoryHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;

            String acc    = param(ex, "account").toUpperCase().trim();
            String period = param(ex, "period");

            String interval;
            switch (period) {
                case "7days":  interval = "7 DAY";  break;
                case "30days": interval = "30 DAY"; break;
                default:       interval = "1 DAY";  break;
            }

            StringBuilder json = new StringBuilder("[");
            try (Connection con = Main.connect()) {
                PreparedStatement ps = con.prepareStatement(
                    "SELECT transaction_time, transaction_type, amount, risk_level " +
                    "FROM transactions WHERE account_number = ? " +
                    "AND transaction_time >= NOW() - INTERVAL " + interval +
                    " ORDER BY transaction_time DESC LIMIT 50");
                ps.setString(1, acc);
                ResultSet rs = ps.executeQuery();

                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    json.append(String.format(
                        "{\"date\":\"%s\",\"type\":\"%s\",\"amount\":%.2f,\"risk\":\"%s\"}",
                        rs.getString("transaction_time"),
                        rs.getString("transaction_type"),
                        rs.getDouble("amount"),
                        rs.getString("risk_level")));
                    first = false;
                }
            } catch (Exception e) { e.printStackTrace(); }

            json.append("]");
            send(ex, 200, json.toString());
        }
    }

    // ── GET /api/beneficiaries?account=ACC1001 ────────────────────
    static class BeneficiariesHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;

            String acc = param(ex, "account").toUpperCase().trim();
            StringBuilder json = new StringBuilder("[");

            try (Connection con = Main.connect()) {
                PreparedStatement ps = con.prepareStatement(
                    "SELECT beneficiary_id, beneficiary_account " +
                    "FROM beneficiaries WHERE account_number = ? " +
                    "ORDER BY added_time DESC");
                ps.setString(1, acc);
                ResultSet rs = ps.executeQuery();

                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    json.append(String.format(
                        "{\"id\":%d,\"account\":\"%s\"}",
                        rs.getInt("beneficiary_id"),
                        rs.getString("beneficiary_account")));
                    first = false;
                }
            } catch (Exception e) { e.printStackTrace(); }

            json.append("]");
            send(ex, 200, json.toString());
        }
    }

    // ── POST /api/addBeneficiary ──────────────────────────────────
    static class AddBeneficiaryHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (cors(ex)) return;
            try {
                String body   = body(ex);
                String acc    = get(body, "account").toUpperCase().trim();
                String benAcc = get(body, "beneficiaryAccount").toUpperCase().trim();

                try (Connection con = Main.connect()) {
                    PreparedStatement check = con.prepareStatement(
                        "SELECT account_number FROM accounts WHERE account_number = ?");
                    check.setString(1, benAcc);
                    if (!check.executeQuery().next()) {
                        send(ex, 404, "{\"success\":false,\"message\":\"Account " + benAcc + " does not exist\"}");
                        return;
                    }

                    if (acc.equals(benAcc)) {
                        send(ex, 400, "{\"success\":false,\"message\":\"Cannot add your own account as beneficiary\"}");
                        return;
                    }

                    PreparedStatement ins = con.prepareStatement(
                        "INSERT IGNORE INTO beneficiaries(account_number, beneficiary_account) VALUES(?, ?)");
                    ins.setString(1, acc);
                    ins.setString(2, benAcc);
                    ins.executeUpdate();
                }

                send(ex, 200, "{\"success\":true,\"message\":\"Beneficiary added successfully\"}");

            } catch (Exception e) {
                send(ex, 500, "{\"success\":false,\"message\":\"Error: " + e.getMessage() + "\"}");
            }
        }
    }
}
import java.sql.*;
import java.net.*;
import java.io.*;
import java.time.LocalTime;

public class Main {

    static final String DB_URL  = "jdbc:mysql://127.0.0.1:3306/fraud_bank";
    static final String DB_USER = "root";
    static final String DB_PASS = "root123";

    public static Connection connect() throws Exception {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    // ── Login ─────────────────────────────────────────────────────
    public static boolean validateLogin(String acc, String pin) {
        try (Connection con = connect()) {
            PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM accounts WHERE account_number = ? AND pin = ?");
            ps.setString(1, acc.trim().toUpperCase());
            ps.setString(2, pin.trim());
            return ps.executeQuery().next();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ── Get Balance ───────────────────────────────────────────────
    public static double getBalance(String acc) {
        try (Connection con = connect()) {
            PreparedStatement ps = con.prepareStatement(
                "SELECT balance FROM accounts WHERE account_number = ?");
            ps.setString(1, acc.trim().toUpperCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("balance");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    // ── Process Transaction ───────────────────────────────────────
    // Returns: SAFE | MEDIUM | HIGH | INVALID | INSUFFICIENT
    public static String processTransaction(String acc, double amount, String type) {
        acc = acc.trim().toUpperCase();

        try (Connection con = connect()) {

            // 1. Check account and balance
            PreparedStatement check = con.prepareStatement(
                "SELECT balance FROM accounts WHERE account_number = ?");
            check.setString(1, acc);
            ResultSet rs = check.executeQuery();
            if (!rs.next()) return "INVALID";

            double balance = rs.getDouble("balance");
            if ((type.equals("WITHDRAW") || type.equals("TRANSFER")) && balance < amount)
                return "INSUFFICIENT";

            // 2. Update balance
            String sql = type.equals("DEPOSIT")
                ? "UPDATE accounts SET balance = balance + ? WHERE account_number = ?"
                : "UPDATE accounts SET balance = balance - ? WHERE account_number = ?";
            PreparedStatement upd = con.prepareStatement(sql);
            upd.setDouble(1, amount);
            upd.setString(2, acc);
            upd.executeUpdate();

            // 3. Collect features
            int hour = LocalTime.now().getHour();

            // Count transactions in last 1 minute BEFORE this insert
            // recentCount >= 2 means this will be the 3rd transaction — trigger alert
            PreparedStatement r1 = con.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE account_number = ? " +
                "AND transaction_time >= NOW() - INTERVAL 1 MINUTE");
            r1.setString(1, acc);
            ResultSet rs1 = r1.executeQuery();
            int recentCount = rs1.next() ? rs1.getInt(1) : 0;

            PreparedStatement r2 = con.prepareStatement(
                "SELECT AVG(amount) FROM transactions WHERE account_number = ?");
            r2.setString(1, acc);
            ResultSet rs2 = r2.executeQuery();
            double avgAmount = (rs2.next() && rs2.getDouble(1) > 0) ? rs2.getDouble(1) : amount;

            // 4. Get ML risk
            String risk = getMLRisk(amount, avgAmount, hour, recentCount);

            // 5. Extra rules on top of ML
            boolean isMidnight = (hour >= 22 || hour <= 6);

            // Rule 1 — Any transaction between 10 PM and 6 AM
            if (isMidnight) {
                System.out.println("[RULE] Midnight transaction at hour: " + hour);
                if (risk.equals("SAFE"))   risk = "MEDIUM";
                if (risk.equals("MEDIUM")) risk = "HIGH";
            }

            // Rule 2 — 3rd or more transaction in 1 minute
            // recentCount is taken BEFORE insert so >= 2 means this is the 3rd
            if (recentCount >= 2) {
                System.out.println("[RULE] Rapid transactions: " + (recentCount + 1) + " in last 1 min");
                if (risk.equals("SAFE"))   risk = "MEDIUM";
                if (risk.equals("MEDIUM")) risk = "HIGH";
            }

            // Rule 3 — Large amount at midnight always HIGH
            if (isMidnight && amount > 50000) {
                System.out.println("[RULE] Large midnight transaction: Rs." + amount);
                risk = "HIGH";
            }

            // 6. Save transaction
            PreparedStatement ins = con.prepareStatement(
                "INSERT INTO transactions(account_number, amount, transaction_type, location, transaction_time, risk_level) " +
                "VALUES(?, ?, ?, ?, NOW(), ?)");
            ins.setString(1, acc);
            ins.setDouble(2, amount);
            ins.setString(3, type);
            ins.setString(4, "Hyderabad");
            ins.setString(5, risk);
            ins.executeUpdate();

            // 7. Save alert if risky
            if (risk.equals("HIGH") || risk.equals("MEDIUM")) {
                String reason = buildReason(type, amount, isMidnight, recentCount);
                PreparedStatement al = con.prepareStatement(
                    "INSERT INTO fraud_alerts(account_number, reason, risk_level, alert_time) " +
                    "VALUES(?, ?, ?, NOW())");
                al.setString(1, acc);
                al.setString(2, reason);
                al.setString(3, risk);
                al.executeUpdate();
            }

            return risk;

        } catch (Exception e) {
            e.printStackTrace();
            return "SAFE";
        }
    }

    // ── Build alert reason ────────────────────────────────────────
    static String buildReason(String type, double amount, boolean isMidnight, int recentCount) {
        StringBuilder reason = new StringBuilder();
        reason.append("Suspicious ").append(type).append(" of Rs.").append(amount);
        if (isMidnight)        reason.append(" | Late night transaction");
        if (recentCount >= 2)  reason.append(" | Rapid transactions (" + (recentCount + 1) + " in 1 min)");
        return reason.toString();
    }

    // ── Call Python ML API ────────────────────────────────────────
    static String getMLRisk(double amount, double avgAmount, int hour, int recentCount) {
        try {
            URL url = new URL("http://127.0.0.1:5000/predict");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(3000);
            conn.setDoOutput(true);

            String json = String.format(
                "{\"amount\":%f,\"avg_amount\":%f,\"hour\":%d,\"recent_txn\":%d,\"beneficiary_recent\":0}",
                amount, avgAmount, hour, recentCount);

            conn.getOutputStream().write(json.getBytes());

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String response = br.readLine();
            return response.replaceAll(".*\"risk\"\\s*:\\s*\"(\\w+)\".*", "$1").trim();

        } catch (Exception e) {
            System.out.println("ML API unreachable, using fallback rules.");
            if (amount > 80000) return "HIGH";
            if (amount > 20000) return "MEDIUM";
            return "SAFE";
        }
    }
}
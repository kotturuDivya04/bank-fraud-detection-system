"""
fraud_api.py
============
Python Flask server that serves ML fraud predictions to the Java backend.

Features received from Java (Main.java):
  amount, avg_amount, hour, recent_txn, beneficiary_recent

Start: python fraud_api.py
URL:   http://localhost:5000
"""

from flask import Flask, request, jsonify
import joblib
import os

app = Flask(__name__)

# Load the trained model
MODEL_PATH = "fraud_model.pkl"

if not os.path.exists(MODEL_PATH):
    print("=" * 50)
    print("ERROR: fraud_model.pkl not found!")
    print("Please run first:  python train_model.py")
    print("=" * 50)
    exit(1)

model = joblib.load(MODEL_PATH)
print("Model loaded OK.")

# Label map: model outputs 0, 1, or 2
LABELS = {0: "SAFE", 1: "HIGH", 2: "MEDIUM"}


@app.route("/predict", methods=["POST"])
def predict():
    data = request.get_json()

    # Read features — same names Java sends
    amount             = float(data.get("amount",              0))
    avg_amount         = float(data.get("avg_amount",          amount))
    hour               = int(data.get("hour",                  12))
    recent_txn         = int(data.get("recent_txn",            0))
    beneficiary_recent = int(data.get("beneficiary_recent",    0))

    # Calculated feature (same as dataset)
    if avg_amount > 0:
        amount_ratio = amount / avg_amount
    else:
        amount_ratio = 1.0

    # Feature order must match train_model.py EXACTLY
    features = [[
        amount,
        avg_amount,
        hour,
        recent_txn,
        beneficiary_recent,
        amount_ratio
    ]]

    prediction = model.predict(features)[0]
    risk = LABELS.get(int(prediction), "SAFE")

    print(f"Predict → amount={amount:.0f}, avg={avg_amount:.0f}, "
          f"hour={hour}, recent={recent_txn}, ratio={amount_ratio:.2f} → {risk}")

    return jsonify({"risk": risk})


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "running"})


if __name__ == "__main__":
    print("=" * 45)
    print("  Fraud Detection ML API")
    print("  Running at http://localhost:5000")
    print("=" * 45)
    app.run(host="127.0.0.1", port=5000, debug=False)

"""
train_model.py
==============
Trains a Random Forest classifier on fraud_dataset.csv
and saves fraud_model.pkl

Run once: python train_model.py

Features used (must match fraud_api.py):
  amount, avg_amount, hour, recent_txn, beneficiary_recent, amount_ratio

Labels:
  0 = SAFE
  1 = HIGH
  2 = MEDIUM
"""

import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report
import joblib

# Load dataset
df = pd.read_csv("fraud_dataset.csv")
print(f"Loaded {len(df)} rows")
print(f"Label counts:\n{df['label'].value_counts()}\n")

# Feature columns — order matters, must match fraud_api.py
FEATURES = [
    "amount",
    "avg_amount",
    "hour",
    "recent_txn",
    "beneficiary_recent",
    "amount_ratio"
]

X = df[FEATURES]
y = df["label"]

# Split
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)
print(f"Train: {len(X_train)}  Test: {len(X_test)}")

# Train
model = RandomForestClassifier(
    n_estimators=100,
    random_state=42,
    class_weight="balanced"
)
model.fit(X_train, y_train)

# Evaluate
y_pred = model.predict(X_test)
print("\nResults:")
print(classification_report(y_test, y_pred, target_names=["SAFE", "HIGH", "MEDIUM"]))

# Save
joblib.dump(model, "fraud_model.pkl")
print("Saved: fraud_model.pkl")
print("Now run: python fraud_api.py")

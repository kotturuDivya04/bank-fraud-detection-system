-- ================================================================
--  Bank Fraud Detection System — Schema + Minimal Test Data
-- ================================================================

CREATE DATABASE IF NOT EXISTS fraud_bank;
USE fraud_bank;

-- ================================================================
--  customers
-- ================================================================
CREATE TABLE customers (
    customer_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    phone       VARCHAR(15)  NOT NULL,
    email       VARCHAR(100) NOT NULL
);

-- Dummy customers
INSERT INTO customers (name, phone, email) VALUES
('Divya', '9876543210', 'divya@email.com'),
('Ravi', '9123456780', 'ravi@email.com'),
('Sneha', '9012345678', 'sneha@email.com');

-- ================================================================
--  accounts
-- ================================================================
CREATE TABLE accounts (
    account_number VARCHAR(20)   NOT NULL PRIMARY KEY,
    customer_id    INT           NOT NULL,
    balance        DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    account_type   VARCHAR(20)   NOT NULL DEFAULT 'Savings',
    created_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    pin            VARCHAR(10)   NOT NULL,
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
);

-- Dummy accounts
INSERT INTO accounts (account_number, customer_id, balance, account_type, pin) VALUES
('ACC1001', 1, 150000, 'Savings', '1234'),
('ACC1002', 2, 50000,  'Savings', '2345'),
('ACC1003', 3, 20000,  'Current', '3456');

-- ================================================================
--  beneficiaries
-- ================================================================
CREATE TABLE beneficiaries (
    beneficiary_id      INT AUTO_INCREMENT PRIMARY KEY,
    account_number      VARCHAR(20) NOT NULL,
    beneficiary_account VARCHAR(20) NOT NULL,
    added_time          TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_pair (account_number, beneficiary_account),
    FOREIGN KEY (account_number) REFERENCES accounts(account_number)
);

-- Dummy beneficiaries
INSERT INTO beneficiaries (account_number, beneficiary_account) VALUES
('ACC1001','ACC1002'),
('ACC1001','ACC1003'),
('ACC1002','ACC1001');

-- ================================================================
--  transactions
-- ================================================================
CREATE TABLE transactions (
    transaction_id   INT AUTO_INCREMENT PRIMARY KEY,
    account_number   VARCHAR(20)   NOT NULL,
    amount           DECIMAL(15,2) NOT NULL,
    transaction_type VARCHAR(20)   NOT NULL,
    location         VARCHAR(100)  DEFAULT 'Hyderabad',
    transaction_time TIMESTAMP     NOT NULL,
    risk_level       VARCHAR(10)   NOT NULL DEFAULT 'SAFE',
    FOREIGN KEY (account_number) REFERENCES accounts(account_number)
);

-- ================================================================
--  fraud_alerts
-- ================================================================
CREATE TABLE fraud_alerts (
    alert_id       INT AUTO_INCREMENT PRIMARY KEY,
    account_number VARCHAR(20)  NOT NULL,
    reason         VARCHAR(255) NOT NULL,
    risk_level     VARCHAR(10)  NOT NULL,
    alert_time     TIMESTAMP    NOT NULL,
    FOREIGN KEY (account_number) REFERENCES accounts(account_number)
);
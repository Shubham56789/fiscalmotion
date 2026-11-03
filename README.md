# FiscalMotion | High-Throughput Distributed Ledger Engine

FiscalMotion is a production-grade, double-entry financial transaction engine built to process high-concurrency ledger operations with strict ACID guarantees, guaranteed zero balance-invariants, and end-to-end resilience.

---

## 🏗️ Core Architecture & Design Patterns

* **Double-Entry Accounting Standard:** Strictly enforces $Debits = Credits$ across all ledger entries to guarantee financial balance consistency.
* **Deterministic Concurrency Control:** Employs PostgreSQL pessimistic locking (`PESSIMISTIC_WRITE`) on account IDs ordered deterministically (`MIN(account_id)` $\rightarrow$ `MAX(account_id)`), completely eliminating database deadlocks under heavy concurrent transfers.
* **Distributed Idempotency:** Implements a zero-overhead Redis locking layer to protect endpoints against double-billing and duplicate request retries.
* **Transactional Outbox Pattern:** Guarantees atomicity between local database state changes and downstream event publishing, avoiding distributed two-phase commit overhead.
* **Asynchronous Kafka Settlement:** Consumes outbox events via Apache Kafka to drive eventual consistency and aggregate downstream merchant ledger balances.
* **Resilience4j Guardrails:** Configured with API Rate Limiters (5 req/s per client window) and Circuit Breakers with fallback handlers to isolate downstream payment dependency failures.
* **Full Observability:** Custom Micrometer counters and distribution summaries exposed via Spring Boot Actuator, scraped by Prometheus, and visualised on Grafana dashboards.

---

## 🛠️ Tech Stack

* **Language & Framework:** Java 21, Spring Boot 3
* **Database & Caching:** PostgreSQL 15, Redis
* **Messaging & Resilience:** Apache Kafka, Resilience4j
* **Observability:** Prometheus, Grafana, Micrometer
* **Benchmarking:** k6 Load Tester
* **Containerization:** Docker & Docker Compose

---

## ⚡ Performance & Benchmarking Results (k6)

Benchmarked against a containerized environment under 50 concurrent virtual users:

* **p95 Latency:** < 200 ms
* **p99 Latency:** < 500 ms
* **Failure Rate:** 0.00%
* **Data Integrity:** 0% balance divergence or deadlock exceptions under multi-threaded execution.

---

## 📁 Repository Structure

```text
fiscalmotion/
├── src/main/java/com/fiscalmotion/ledger/
│   ├── controller/      # REST Endpoints with Resilience4j Annotations
│   ├── model/           # Ledger, Account, Transaction & Outbox Entities
│   ├── repository/      # JPA Repositories with Custom Lock Queries
│   └── service/         # Core Transfer Engine, Idempotency & Outbox Logic
├── docker-compose.yml   # Postgres, Redis, Kafka, Prometheus, Grafana Stack
├── prometheus.yml       # Metrics Scraping Configuration
├── load_test.js         # k6 Concurrency Benchmarking Script
└── pom.xml              # Maven Build Configuration
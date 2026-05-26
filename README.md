# Forex-MTL

A Scala HTTP proxy for currency exchange rates, built with `cats-effect` and `http4s`. It sits between your internal services and the [One-Frame](https://hub.docker.com/r/paidyinc/one-frame) third-party API, handling caching and rate limiting transparently.

## The Problem

The One-Frame API provides exchange rates but has a hard cap of **1,000 requests per day** per token. The service needs to support **10,000 requests per day** while keeping rates no older than **5 minutes**. The solution: cache aggressively and track API usage.

---

## Prerequisites

- **Java 11+** (`java -version`)
- **sbt** — the Scala build tool ([install guide](https://www.scala-sbt.org/download.html))
- **Docker** — to run the One-Frame dependency locally

---

## Quick Start

### 1. Start One-Frame (the upstream API)

```bash
docker pull paidyinc/one-frame
docker run -p 8080:8080 paidyinc/one-frame
```

One-Frame will be available at `http://localhost:8080`.

### 2. Compile

```bash
sbt compile
```

First compile is slow (sbt downloads dependencies). Subsequent ones are fast.

### 3. Run Tests

```bash
sbt test
```

Tests are unit tests — they do **not** require One-Frame to be running (they mock the HTTP client).

### 4. Run the Application

```bash
sbt run
```

The server starts on `http://localhost:8081`.

### 5. Make a Request

```bash
curl "http://localhost:8081/rates?from=USD&to=JPY"
```

**Successful response:**
```json
{
  "from": "USD",
  "to": "JPY",
  "price": 0.71,
  "timestamp": "2019-01-01T00:00:00Z"
}
```

**Supported currencies:** `AUD, CAD, CHF, EUR, GBP, NZD, JPY, SGD, USD`

---

## Other Useful Commands

```bash
# Clean compiled artifacts and caches
sbt clean
```

---

## Architecture

The app is split into four strict layers. Each layer only knows about the layer below it:

```
HTTP request
     ↓
[ http ]      — parses query params, serializes JSON responses
     ↓
[ programs ]  — business logic, error mapping
     ↓
[ services ]  — talks to One-Frame API (with cache + rate limiting)
     ↓
[ domain ]    — plain data types: Rate, Price, Currency, Timestamp
```

This layering means you can swap the One-Frame backend for a different provider by only changing `services/` — nothing else needs to change.

---

## Key Implementation Decisions

### Caching to Hit 10K Requests with a 1K Limit

**The math:** 10,000 service requests / day with a maximum of 1,000 upstream API calls means each upstream call must serve at least 10 downstream requests on average.

**The solution:** Results are cached in memory for up to 5 minutes. A request for `USD/JPY` hits One-Frame at most once every 5 minutes (288 times per day per pair). With 9 supported currencies there are 72 possible pairs — far within the 1,000 daily limit.

---

## API Reference

### `GET /rates`

| Parameter | Required | Example |
|---|---|---|
| `from` | yes | `USD` |
| `to` | yes | `JPY` |

**Errors:**

| Status | Meaning |
|---|---|
| `400 Bad Request` | Missing or invalid currency code |
| `404 Not Found` | Currency pair not found in One-Frame |
| `429 Too Many Requests` | Daily upstream API quota exhausted |
| `503 Service Unavailable` | One-Frame is unreachable |

---

## Project Structure

```
forex-mtl/
├── src/
│   ├── main/scala/forex/
│   │   ├── Main.scala                        # entry point, starts http4s server
│   │   ├── Module.scala                      # wires services → programs → routes
│   │   ├── config/                           # reads application.conf
│   │   ├── domain/                           # Rate, Price, Currency, Timestamp
│   │   ├── http/rates/                       # HTTP routes, query params, JSON codecs
│   │   ├── programs/rates/                   # business logic layer
│   │   └── services/rates/
│   │       ├── Algebra.scala                 # interface (trait)
│   │       ├── Interpreters.scala            # factory: live vs dummy
│   │       └── interpreters/
│   │           ├── OneFrame.scala            # live: cache + rate limit + HTTP
│   │           └── OneFrameDummy.scala       # test double
│   └── test/scala/forex/                     # unit tests
└── build.sbt                                 # dependencies and build config
```

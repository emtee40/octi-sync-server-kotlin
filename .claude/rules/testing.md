# Testing

## Stack

JUnit 5 + Kotest matchers + MockK + Ktor test host

## Test Infrastructure

### `TestRunner` (base class)
- Starts a real server on a random port with a temp data directory
- Provides an HTTP client pre-configured with JSON content negotiation
- Tears down server and deletes temp data after each test

### `runTest2 { }` lambda
All integration tests use this pattern — it sets up the environment and provides a receiver with helper functions.

### `TestRunnerExtensions.kt` helpers
Pre-built flows for common operations:
- `createDevice()` / `createDeviceRaw()` — register a device and get credentials
- `createShareCode()` — generate a share code for an account
- `addDeviceId()` / `addAuth()` / `addCredentials()` — attach auth headers
- `readModule()` / `writeModule()` / `deleteModule()` — module CRUD
- `listDevices()` / `deleteDevice()` — device management

### Data classes
- `Credentials` — wraps device ID + auth pair
- `Auth` — account ID + password

## Test Organization

- `*FlowTest` — integration tests with full HTTP round-trips (most tests are this type)
- `*RepoTest` — unit tests for persistence layer
- `*Test` — unit tests for individual components (RateLimiter, ConnectionRegistry, etc.)

## Assertions

Uses Kotest matchers: `shouldBe`, `shouldNotBe`, `shouldBeNull`, etc.

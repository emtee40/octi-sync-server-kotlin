# Architecture

## Domain Hierarchy

Entities nest: **Account → Device → Module**. This maps directly to both the code packages and the on-disk storage layout.

```
dataPath/accounts/{accountId}/
├── account.json
├── shares/{shareId}.json
└── devices/{deviceId}/
    ├── device.json
    └── modules/{sha1(moduleId)}/
        ├── blob          (binary payload)
        └── meta.json     (Module.Info)
```

## Startup Flow

```
App.main(args) → parse CLI config → DaggerAppComponent.build() → App.launch() → Server.start()
```

`Server.start()` installs Ktor middleware (logging, WebSockets, content negotiation, body limits, rate limiting, status pages), then registers all routes.

## Routing

Each domain has a `*Route` class (`@Singleton`, injected by Dagger) with a `setup(Routing)` method called from `Server.kt`. All endpoints live under `/v1/`.

| Route | Path | Purpose |
|-------|------|---------|
| AccountRoute | `/v1/account` | Create/delete accounts |
| ShareRoute | `/v1/account/share` | Generate/consume share codes |
| DeviceRoute | `/v1/devices` | List/delete/reset devices |
| ModuleRoute | `/v1/module/{moduleId}` | Read/write/delete module data |
| WsRoute | `/v1/ws` | WebSocket sync notifications |
| StatusRoute | `/v1/status` | Health check |
| MyIpRoute | `/v1/myip` | Client IP echo |

## Persistence

**No database.** All state is JSON files on disk + in-memory `ConcurrentHashMap` caches.

- Repos (`AccountRepo`, `DeviceRepo`, `ModuleRepo`, `ShareRepo`) load everything into memory at startup via `runBlocking` in `init {}`.
- File writes use `kotlinx.serialization`.
- `Device` operations are protected by a per-device `Mutex`.
- Module IDs are SHA-1 hashed for safe directory names.

## Sync Flow

1. Device A writes module → `POST /v1/module/{moduleId}`
2. `ModuleRepo` stores data, calls `SyncNotifier.enqueue()`
3. `SyncNotifier` debounces 500ms, then broadcasts `ModuleChanged` event
4. `ConnectionRegistry` delivers event to all WebSocket sessions in the account except the originator
5. Device B receives event, fetches updated data via `GET /v1/module/{moduleId}`

## Authentication

- `X-Device-ID` header: device UUID
- `Authorization: Basic base64(accountId:devicePassword)` header
- Device password: 50 random bytes, generated at registration, constant-time comparison
- Helper: `HttpExtensions.authenticateDevice()` — parses headers, looks up device, verifies credentials, updates `lastSeen`

## Background Jobs

All run in `AppScope` (application-wide `SupervisorJob + Dispatchers.Default`):

- **Account GC**: removes accounts with no devices after 10 min
- **Device expiration**: removes devices not seen in 90 days
- **Module expiration**: removes modules not accessed in 90 days
- **Share expiration**: cleans up expired share codes (60 min TTL)

## WebSocket Connection Limits

Managed by `ConnectionRegistry`:
- Per account: 64
- Per IP: 32
- Global: 10,000
- Frame rate: 120 frames/min per connection
- Oldest session evicted when per-account limit exceeded

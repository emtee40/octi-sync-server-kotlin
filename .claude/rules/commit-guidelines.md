# Commit Message Guidelines

## Format

- Imperative mood, present tense ("Add feature" not "Added feature")
- First line: 50-60 characters max
- No module prefix — flat commit messages
- Optionally add a blank line and detailed description body

## Examples from History

```
Restrict GITHUB_TOKEN permissions to minimum required
Rename project from octi-sync-server-kotlin to octi-server
Remove idle timeout cleanup, rely on Ktor ping/pong for WS liveness
Add WebSocket-based real-time sync notifications
Add share code consumption and account linking
```

## Prefixes

- `fix:` / `chore:` / `feat:` style prefixes are used (lowercase, with colon)
- **Release commits**: `Release: {version}`
- **Dependency upgrades**: `Upgrade {dependency} from {old} to {new}`

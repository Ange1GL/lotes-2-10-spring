# CLAUDE.md

## Purpose
Offline bundle of VS Code Java + Spring Boot extensions (`.vsix`), split into `lote-XX/` folders (<= ~35 MB each) so they fit on GitHub. Large extensions are cut into `.partNN` files.

## Layout
- `lote-01..10/` – extension files / parts (do not edit; checksums in `manifest.json`).
- `manifest.json` – name + SHA-256 of every rebuilt `.vsix`.
- `join-java/` – Maven project (Java 21, no dependencies), main class `lotes.JoinVsix`. Joins parts, verifies SHA-256, optional `--install`.
- `join-vsix.mjs` – equivalent Node.js implementation.

## Commands
- `java join-java/src/main/java/lotes/JoinVsix.java [--out dir] [--root dir] [--install]`
- `cd join-java && mvn -q compile exec:java` / `mvn -q package`
- `node join-vsix.mjs [--out dir] [--install]`

## Conventions
- Keep the Java and Node installers behaviorally identical (install order list, manifest format).
- Output goes to `vsix/` (gitignored); never commit rebuilt `.vsix` or `target/`.
- Conventional Commits.

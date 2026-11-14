# Extensiones de VS Code: Java + Spring Boot (offline)

Extensiones `.vsix` divididas en `lote-XX/` (máx. ~35 MB cada uno) para poder subirlas a GitHub.
Las más pesadas (`redhat.java`, `vmware.vscode-spring-boot`) están cortadas en partes `.partNN`.

Plataforma: Windows x64 (la variante de `redhat.java` es `win32-x64`). Requiere Node.js y un JDK instalado.

## Uso en la otra computadora

1. Clona el repositorio (o descarga todos los `lote-XX`).
2. Reconstruye y verifica las extensiones (SHA-256 contra `manifest.json`):

```bash
node join-vsix.mjs
```

   Quedan en `vsix/`.

3. Instálalas en el orden correcto:

```bash
node join-vsix.mjs --install
```

Opciones: `--out <carpeta>` cambia el destino de los `.vsix`.

## Contenido

| Lote | Contenido |
|---|---|
| 01–05 | `redhat.java` (5 partes) |
| 06–08 | `vmware.vscode-spring-boot` (3 partes) |
| 09 | gradle, java-pack, spring-boot-dashboard, boot-dev-pack |
| 10 | maven, java-test, java-debug, java-dependency, spring-initializr |

Si falta un lote o una parte, el script lo indica y no instala nada.

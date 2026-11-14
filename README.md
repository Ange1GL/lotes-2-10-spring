# Extensiones de VS Code: Java + Spring Boot (offline)

Extensiones `.vsix` divididas en `lote-XX/` (máx. ~35 MB cada uno) para poder subirlas a GitHub.
Las más pesadas (`redhat.java`, `vmware.vscode-spring-boot`) están cortadas en partes `.partNN`.

Plataforma: Windows x64 (la variante de `redhat.java` es `win32-x64`). Requiere un JDK 21+ (o Node.js si usas el script `.mjs`) y el comando `code` en el PATH para instalar.

## Uso en la otra computadora

1. Clona el repositorio (o descarga todos los `lote-XX`).
2. Reconstruye y verifica las extensiones (SHA-256 contra `manifest.json`). Quedan en `vsix/`.
   Elige **una** de estas opciones; todas hacen lo mismo.

### Opción A: Java sin compilar (la más simple)

Desde la raíz del repo:

```bash
java join-java/src/main/java/lotes/JoinVsix.java
```

### Opción B: javac

```bash
javac --release 21 -d out join-java/src/main/java/lotes/JoinVsix.java
java -cp out lotes.JoinVsix
```

### Opción C: Maven

```bash
cd join-java
mvn -q compile exec:java
```

O generando un jar ejecutable (desde `join-java/`):

```bash
mvn -q package
java -jar target/join-vsix-1.0.0.jar
```

> Maven necesita internet la primera vez para bajar sus plugins. Las opciones A y B solo necesitan el JDK.
> En JDK 24+ Maven puede imprimir avisos `WARNING: sun.misc.Unsafe...`; son inofensivos.

### Opción D: Node.js

```bash
node join-vsix.mjs
```

### Instalar en VS Code

Añade `--install` a cualquiera de los comandos anteriores. Instala en el orden correcto con `code --install-extension`:

```bash
java join-java/src/main/java/lotes/JoinVsix.java --install
```

Con Maven los argumentos van en `exec.args`:

```bash
mvn -q compile exec:java -Dexec.args="--out ../salida --install"
```

### Opciones

| Opción | Efecto |
|---|---|
| `--out <carpeta>` | Destino de los `.vsix` (por defecto `vsix/`) |
| `--root <carpeta>` | Solo Java: raíz del repo, si ejecutas desde otro lugar (por defecto se busca `manifest.json` hacia arriba desde la carpeta actual) |
| `--install` | Instala con `code --install-extension` tras verificar |

## Contenido

| Lote | Contenido |
|---|---|
| 01–05 | `redhat.java` (5 partes) |
| 06–08 | `vmware.vscode-spring-boot` (3 partes) |
| 09 | gradle, java-pack, spring-boot-dashboard, boot-dev-pack |
| 10 | maven, java-test, java-debug, java-dependency, spring-initializr |

Si falta un lote o una parte, o un SHA-256 no coincide, el script se detiene con un error y no instala nada.

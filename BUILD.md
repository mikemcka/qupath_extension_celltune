# Build Guide

This project builds a QuPath extension fat JAR using the Gradle wrapper.

## Prerequisites

- JDK 25 installed
- `JAVA_HOME` pointing to JDK 25
- Git

## Verify Java

```bash
java -version
```

Expected: version output starts with `25`.

## Build Commands

### Windows (PowerShell)

```powershell
# from repository root
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

.\gradlew.bat clean compileJava
.\gradlew.bat test
.\gradlew.bat shadowJar
```

### Linux / macOS

```bash
# from repository root
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"

chmod +x gradlew
./gradlew clean compileJava
./gradlew test
./gradlew shadowJar
```

## Output Artifact

- Fat JAR: `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar`

## Install in QuPath

Copy the fat JAR into your QuPath extensions folder and restart QuPath.

- Windows: `C:\Users\<you>\QuPath\v0.7\extensions\`
- Linux: `~/.local/share/QuPath/v0.7/extensions/`
- macOS: `~/Library/Application Support/QuPath/v0.7/extensions/`

## Quick Troubleshooting

- Build fails with Java toolchain errors: confirm `JAVA_HOME` points to JDK 25.
- QuPath does not show menu entries: confirm the new jar is in the correct extensions folder and restart QuPath fully.
- Native model issues: use the generated shadow JAR only; do not copy partial classpath jars.

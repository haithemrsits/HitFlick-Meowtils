#!/usr/bin/env bash
# Builds HitFlick.meowtils from Main.java + HitFlick.java.
# A .meowtils file is just a jar of compiled classes, with Main at the jar root.
#
# Usage:
#   MC_JAR=/path/to/minecraft-1.8.9-mcp.jar \
#   MEOWTILS_JAR=/path/to/meowtils.jar \
#   LWJGL_JAR=/path/to/lwjgl.jar \
#   ./build.sh
#
# MC_JAR must contain MCP-named 1.8.9 classes (e.g. a Forge/MCP dev jar), since the
# source uses names like thePlayer and rotationYaw. LWJGL_JAR is needed for the
# Mouse/Keyboard polling. Fix the VERIFY imports in HitFlick.java before building.
set -euo pipefail

MC_JAR="${MC_JAR:-C:/Users/Strange/Downloads/Compressed/Yuri-windows/Yuri.jar}"
MEOWTILS_JAR="${MEOWTILS_JAR:-C:/Users/Strange/Pictures/Meowtils-Lunar/Meowtils-Lunar/Meowtils.jar}"
LWJGL_JAR="${LWJGL_JAR:-C:/Users/Strange/AppData/Roaming/.minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar}"

rm -rf out && mkdir -p out/META-INF

# Detect classpath separator (semicolon on Windows javac, colon on Unix)
CP_SEP=":"
if [[ "$OSTYPE" == "msys"* ]] || [[ "$OSTYPE" == "cygwin"* ]] || [[ "$OSTYPE" == "win32"* ]] || [[ "${OS:-}" == "Windows_NT" ]]; then
  CP_SEP=";"
fi

# Target Java 8 bytecode (1.8.9 era). Works when run on newer JDKs too.
javac -source 8 -target 8 -proc:none \
  -cp "${MC_JAR}${CP_SEP}${MEOWTILS_JAR}${CP_SEP}${LWJGL_JAR}" \
  -d out Main.java HitFlick.java

# Meowtils ExtensionManager requires META-INF/meowtils.extension with main class definition
printf "main=Main\n" > out/META-INF/meowtils.extension

# Main.class must sit at the jar root; jar renamed to .meowtils.
jar cf HitFlick.meowtils -C out .
echo "Built HitFlick.meowtils"

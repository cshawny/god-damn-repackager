#!/usr/bin/env bash
# Wrapper that forces Gradle to run under JDK 17 (required for MC 1.20.1 / Forge).
# Usage:  ./g.sh build        |  ./g.sh runClient        |  ./g.sh <any gradle task>
set -e
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.11.9-hotspot"
cd "$(dirname "$0")"
exec ./gradlew "$@"

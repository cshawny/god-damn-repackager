#!/usr/bin/env bash
# Wrapper that forces Gradle to run under JDK 21 (required for MC 1.21.1 / NeoForge).
# Usage:  ./g.sh build        |  ./g.sh runClient        |  ./g.sh <any gradle task>
#
# Proxy: maven.neoforged.net is blocked by GFW SNI reset from mainland China.
# The user's VPN client listens on 127.0.0.1:10808 (HTTP + SOCKS). We route Gradle's
# HTTPS downloads through it so the NeoForge SDK + transitive deps resolve. To disable
# (e.g. on a network where neoforge is directly reachable, or if your VPN uses a different
# port), set GDR_NO_PROXY=1 in the environment.
set -e
export JAVA_HOME="/c/Program Files/Java/jdk-22"
cd "$(dirname "$0")"

if [ -z "$GDR_NO_PROXY" ]; then
    # JVM system properties for HTTPS/HTTP proxying (Gradle honors these).
    export GRADLE_OPTS="${GRADLE_OPTS} -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808"
    # Bypass proxy for localhost / intranet hosts (avoids breaking local services).
    export GRADLE_OPTS="${GRADLE_OPTS} -Dhttp.nonProxyHosts=localhost|127.0.0.1"
fi

exec ./gradlew "$@"

#!/usr/bin/env bash
# JDK 24+ warns when the bundled LZ4 native library is loaded without an
# explicit native-access grant. Keep JAVA_OPTS available for container users.
exec java ${JAVA_OPTS:-} --enable-native-access=ALL-UNNAMED -jar server.jar

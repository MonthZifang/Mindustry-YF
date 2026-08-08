#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

printf '\n========================================\n'
printf '      开始编译 Mindustry 服务端\n'
printf '========================================\n'
printf '仅构建服务端发行包，不构建客户端。\n\n'

./gradlew :server:dist --no-daemon -PnoLocalArc=true -Pbuildversion=159.7

artifact='server/build/libs/server-release.jar'
if [ ! -f "$artifact" ]; then
    printf '\n[失败] 编译完成但未找到服务端发行包。\n' >&2
    exit 1
fi

printf '\n[完成] 服务端发行包已生成：\n%s\n' "$artifact"

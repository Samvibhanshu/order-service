#!/usr/bin/env bash
# Compile and run all tests with nothing but a JDK (17+).   Usage: ./build.sh [--no-tests]
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/main out/test
javac -Xlint:all -d out/main $(find src/main -name '*.java')
if [ "${1:-}" = "--no-tests" ]; then exit 0; fi
javac -Xlint:all -cp out/main -d out/test $(find src/test -name '*.java')
java -cp out/main:out/test dev.samridhi.orders.testkit.Runner \
  dev.samridhi.orders.StateMachineTests dev.samridhi.orders.InventoryTests dev.samridhi.orders.IdempotencyTests dev.samridhi.orders.ConcurrencyTests \
  dev.samridhi.orders.JsonTests dev.samridhi.orders.HttpApiTests

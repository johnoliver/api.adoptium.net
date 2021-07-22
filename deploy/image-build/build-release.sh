#!/bin/bash

set -eux

rm -r ./build-workspace || true
mkdir build-workspace

VERSION="3.0.0-SNAPSHOT"

BUILD_PATH="/home/api/build/api.adoptium.net"

docker build -t adopt-api-build .

docker create --name extract adopt-api-build
docker cp extract:"$BUILD_PATH/adoptopenjdk-frontend-parent/adoptopenjdk-api-v3-frontend/target/quarkus-app/" ./build-workspace/frontend
docker cp extract:"$BUILD_PATH/adoptopenjdk-updater-parent/adoptopenjdk-api-v3-updater/target/adoptopenjdk-api-v3-updater-${VERSION}-jar-with-dependencies.jar" ./build-workspace/updater.jar
mv ./build-workspace/frontend/quarkus-run.jar ./build-workspace/frontend/frontend.jar

docker rm extract


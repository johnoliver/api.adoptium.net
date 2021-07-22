#!/bin/bash

set -eux

rm -r build-workspace || true
mkdir build-workspace

cp -r ../image-build/build-workspace/* ./build-workspace

docker build --build-arg type=updater -t adopt-api-v3-updater .
docker build --build-arg type=frontend -t adopt-api-v3-frontend .

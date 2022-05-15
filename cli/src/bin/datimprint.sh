#!/usr/bin/env bash
# ~{project.name} ~{project.version} Launcher
# Copyright (c) ~{project.inceptionYear}-~{build.year} ~{project.organization.name}
#
# This shell script expects to find the executable JAR file
# in the same directory as the script.

set -o errexit
set -o pipefail
set -o nounset

EXE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java -jar ${EXE_DIR}/~{project.artifactId}-~{project.version}-exe.jar "$@"

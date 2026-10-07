#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo 'Usage: sh server/start.sh /absolute/path/application.properties' >&2
  exit 2
fi
case "$1" in /*.properties) ;; *) echo 'An absolute .properties configuration path is required.' >&2; exit 2 ;; esac
if [ ! -r "$1" ]; then
  echo 'Server configuration is missing or unreadable; refusing to use development credentials.' >&2
  exit 2
fi
release_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ ! -f "$release_root/backend/atoms-demo.jar" ]; then
  echo 'Production JAR missing; run this script from an extracted release bundle.' >&2
  exit 2
fi
# Spring reads this file as properties, never as executable shell code.
export ATOMS_CONFIG_IMPORT="file:$1"
cd "$release_root"
exec java -jar "$release_root/backend/atoms-demo.jar" --spring.profiles.active=server

#!/bin/sh
# Plays MTG Oracle from the newest snapshot `./gradlew :app:installLocal` made
# (app/dist/current names it). Not `gradlew run`: builds replace the class
# files under a running game, and the next class it loads is gone.
dist="$(dirname "$0")/dist"
if [ ! -f "$dist/current" ]; then
    echo "No snapshot yet. In $(dirname "$0") run: ./gradlew :app:installLocal" >&2
    exit 1
fi
exec sh "$dist/$(cat "$dist/current")/run.sh" "$@"

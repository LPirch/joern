#!/usr/bin/env sh

SCRIPT_ABS_PATH=$(readlink -f "$0")
SCRIPT_ABS_DIR=$(dirname $SCRIPT_ABS_PATH)

$SCRIPT_ABS_DIR/target/universal/stage/bin/c2cpg.sh $@

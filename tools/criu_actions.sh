#!/bin/bash

#
# Use like this: criu [dump|restore] --action-script <absolute_patch>/criu_actions.sh ..
#

case "$CRTOOLS_SCRIPT_ACTION" in
  pre-restore )
    echo "PRE-RESTORE: `date +%s.%N`"
    ;;
  post-restore )
    echo "POST-RESTORE: `date +%s.%N`"
    ;;
  pre-resume )
    echo "PRE-RESUME: `date +%s.%N`"
    ;;
  post-resume )
    echo "POST-RESUME: `date +%s.%N`"
    ;;
  pre-dump )
    echo "PRE-DUMP: `date +%s.%N`"
    ;;
  post-dump )
    echo "POST-DUMP: `date +%s.%N`"
    ;;
esac

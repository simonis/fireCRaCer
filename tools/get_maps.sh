#!/bin/bash

# Script to copy the /proc/maps, /proc/smaps and /proc/pagemap of all processes excluding kernel threads.

rm -rf /tmp/proc

# The following is required in order to make the patter matching in case statements working
shopt -s extglob

for f in /proc/*; do
  case "$f" in
    "/proc/"+([0-9]) )
      # For kernel threads /proc/<pid>/exe is a broken link so stat will return an error
      stat $f/exe > /dev/null 2>&1
      if [ $? -eq 0 ]; then
        # Regular process
        cp --parent $f/maps $f/smaps /tmp/
      fi
      ;;
  esac
done

#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

# Prerequisites:
# ==============
#
# Make /dev/kvm accessible:
# -------------------------
# sudo setfacl -m u:${USER}:rw /dev/kvm
#
# Create tap device:
# ------------------
# sudo ip tuntap add dev tap1 mode tap
# sudo ip addr add 172.16.0.1/24 dev tap1
# sudo ip link set tap1 up

FIRECRACKER=${FIRECRACKER:-"firecracker"}

KERNEL=${KERNEL:-"$MYPATH/deps/vmlinux"}
IMAGE=${IMAGE:-"$MYPATH/deps/rootfs.ext4"}
UFFD_HANDLER=${UFFD_HANDLER:-"$MYPATH/deps/uffd_handler"}

TAP_DEVICE=${TAP_DEVICE:-'tap0'}

while getopts 'lmns:r:i:ukh?' opt; do
  case "$opt" in
    l)
      LOGGING=1
      ;;
    m)
      METRICS=1
      ;;
    s)
      SNAPSHOT="$OPTARG"
      ;;
    r)
      RESTORE="$OPTARG"
      ;;
    u)
      nextopt=${!OPTIND}
      # existing or starting with dash?
      if [[ -n $nextopt && $nextopt != -* ]] ; then
        OPTIND=$((OPTIND + 1))
        USERFAULTFD=$nextopt
      else
        USERFAULTFD=1
      fi
      ;;
    n)
      NO_SERIAL="8250.nr_uarts=0"
      ;;
    i)
      RW_IMAGE="$OPTARG"
      ;;
    k)
      KILL=1
      ;;
    ?|h)
      echo "Usage: $(basename $0) [[-i <rw-image>] [-l] [-m] [-n]] [-s <snapshot-dir>] [-r <snapshot-dir> [-l] [-u [<log-file>]]]"
      echo " -i <rw-image>: a file or device which will be used as read/write overlay for the root file system."
      echo "                By default a ram disk of TEMPFS_SIZE will be used."
      echo " -l: enable Firecracker logging to LOG_PATH with LOG_LEVEL and LOG_SHOW_LEVEL/LOG_SHOW_ORIGIN"
      echo " -m: enable Firecracker metrics to METRICS_PATH"
      echo " -n: Disable serial devices (i.e 8250.nr_uarts=0)."
      echo "     This saves ~100ms boot time but will disable the boot console."
      echo ""
      echo " -s: snapshot Firecracker on tap device '$TAP_DEVICE' to <snapshot-dir>."
      echo "     If <snapshot-dir> doesn't exist, it will be created."
      echo " -r: restore Firecracker snapshot on tap device '$TAP_DEVICE' from <snapshot-dir>."
      echo " -k: send Firecracker guest on tap device '$TAP_DEVICE' CtrlAltDel message (i.e. shut it down)."
      echo " -u: run with a userfaultfd memory backend and redirect its output to <log-file>"
      echo "     (defaults to '/tmp/fireCRaCer-uffd-$TAP_DEVICE.log')."
      echo "     The userfaultfd is started from UFFD_HANDLER (defaults to '$UFFD_HANDLER')."
      echo "     Options can be passed to userfaultfd by setting UFFD_OPTS."
      exit 1
      ;;
  esac
done
shift "$(($OPTIND -1))"

if [[ ! -r /dev/kvm ]]; then
  echo "/dev/kvm not readable! This is required to run firecracker."
  echo "running: setfacl -m u:${USER}:rw /dev/kvm"
  sudo setfacl -m u:${USER}:rw /dev/kvm
fi

TAP_DEVICE_NR=`echo $TAP_DEVICE | grep -oP '^tap[0-9]{1,3}$' | grep -oP '[0-9]{1,3}$'`
if [[ "x$TAP_DEVICE_NR" == "x" ]]; then
  echo "TAP_DEVICE must have the form 'tap<num>' with <num> = 0..255 (was '$TAP_DEVICE')"
  exit 1
fi
TAP_DEVICE_NR_HEX=$( printf "%.2x" $TAP_DEVICE_NR )

if error=`! ip link show $TAP_DEVICE 2>&1`; then
  echo "tap device TAP_DEVICE=$TAP_DEVICE not configured ($error)"
  echo "Running:"
  echo "  sudo ip tuntap add dev $TAP_DEVICE mode tap"
  echo "  sudo ip addr add 172.16.$TAP_DEVICE_NR.1/24 dev $TAP_DEVICE"
  echo "  sudo ip link set $TAP_DEVICE up"
  sudo ip tuntap add dev $TAP_DEVICE mode tap
  sudo ip addr add 172.16.$TAP_DEVICE_NR.1/24 dev $TAP_DEVICE
  sudo ip link set $TAP_DEVICE up
fi
IP_SETTINGS=`ifconfig $TAP_DEVICE |
  awk '/inet.+netmask/ {
    gateway=$2
    split(gateway, ip, ".")
    netmask=$4
    printf("%s.%s.%s.%s::%s:%s::eth0:off\n", ip[1], ip[2], ip[3], ip[4] + 1, gateway, netmask)
  }'`
# echo $IP_SETTINGS
FC_SOCKET=${FC_SOCKET:-"/tmp/fireCRaCer-$TAP_DEVICE.socket"}

check_http_response() {
  actual="$1"
  expected="$2"
  message="$3"
  if [[ "x$actual" == "x$expected" ]]; then
    echo "$message: OK"
  else
    echo "$message: Error (HTTTP response was $ret)"
    echo "Exiting.."
    exit 1
  fi
}

if [[ -v KILL ]]; then
  echo "Killing firecracker instance running on tap device $TAP_DEVICE"
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output /dev/null \
             --unix-socket $FC_SOCKET \
             -X PUT 'http://localhost/actions' \
             -H  'Accept: application/json' \
             -H  'Content-Type: application/json' \
             -d '{ "action_type": "SendCtrlAltDel" }')
  check_http_response $ret  "204" "Kill"
  exit 0
fi

if [[ -v SNAPSHOT ]]; then
  if [ ! -d "$SNAPSHOT" ]; then
    mkdir -p $SNAPSHOT
  fi
  # Suspend
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output /dev/null \
             --unix-socket $FC_SOCKET \
             -X PATCH 'http://localhost/vm' \
             -H 'Accept: application/json' \
             -H 'Content-Type: application/json' \
             -d '{ "state": "Paused" }')
  check_http_response $ret  "204" "Suspend"
  # Checkpoint
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output /dev/null \
             --unix-socket $FC_SOCKET \
             -X PUT 'http://localhost/snapshot/create' \
             -H 'Accept: application/json' \
             -H 'Content-Type: application/json' \
             -d "{ \"snapshot_type\": \"Diff\", \
                   \"snapshot_path\": \"$SNAPSHOT/snapshot_file\", \
                   \"mem_file_path\": \"$SNAPSHOT/mem_file\", \
                   \"version\": \"1.0.0\" }")
  check_http_response $ret "204" "Snapshot"
  # Resume
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output /dev/null \
             --unix-socket $FC_SOCKET \
             -X PATCH 'http://localhost/vm' \
             -H 'Accept: application/json' \
             -H 'Content-Type: application/json' \
             -d '{ "state": "Resumed" }')
  check_http_response $ret  "204" "Resumed"
  exit 0
fi

if [[ -v LOGGING ]]; then
  LOG_PATH=${LOG_PATH:-"/tmp/fireCRaCer-$TAP_DEVICE.log"}
  LOG_LEVEL=${LOG_LEVEL:-"Info"}
  LOG_SHOW_LEVEL=${LOG_SHOW_LEVEL:-"true"}
  LOG_SHOW_ORIGIN=${LOG_SHOW_ORIGIN:-"true"}
  # The logs file has to be empty!
  truncate -s 0 $LOG_PATH
  if [[ -v RESTORE ]]; then
    LOGGER="--log-path $LOG_PATH --level $LOG_LEVEL"
    if [[ "$LOG_SHOW_LEVEL" == "true" ]]; then
      LOGGER="$LOGGER --show-level"
    fi
    if [[ "$LOG_SHOW_ORIGIN" == "true" ]]; then
      LOGGER="$LOGGER --show-log-origin"
    fi
  else
    LOGGER=$(cat <<EOF
  "logger": {
    "log_path": "$LOG_PATH",
    "level": "$LOG_LEVEL",
    "show_level": $LOG_SHOW_LEVEL,
    "show_log_origin": $LOG_SHOW_ORIGIN
  },
EOF
    )
  fi
fi

if [[ -v RESTORE ]]; then
  if [ ! -d "$RESTORE" ]; then
    echo "Error: can't access snapshot directory $RESTORE"
    exit 1
  fi
  if [[ -v USERFAULTFD ]]; then
    if [[ $USERFAULTFD = "1" ]]; then
      USERFAULTFD="/tmp/fireCRaCer-uffd-${TAP_DEVICE:-tap0}.log"
    fi
    if [ ! -f "$UFFD_HANDLER" ]; then
      echo "Error: can't access userfaultfd daemon at $UFFD_HANDLER"
      exit 1
    fi
    UFFD_SOCKET=${UFFD_SOCKET:-"/tmp/fireCRaCer-uffd-$TAP_DEVICE.socket"}
    rm -f $UFFD_SOCKET
    echo "Running: $UFFD_HANDLER $UFFD_OPTS $UFFD_SOCKET $RESTORE/mem_file > $USERFAULTFD 2>&1"
    $UFFD_HANDLER $UFFD_OPTS $UFFD_SOCKET $RESTORE/mem_file > $USERFAULTFD 2>&1 &
    # Kill uffd handler on exit
    trap 'kill $(jobs -p)' EXIT
    BACKEND_PATH=$UFFD_SOCKET
    BACKEND_TYPE="Uffd"
  else
    BACKEND_PATH="$RESTORE/mem_file"
    BACKEND_TYPE="File"
  fi
  rm -f $FC_SOCKET
  echo "Running: $FIRECRACKER --boot-timer --api-sock $FC_SOCKET $LOGGER"
  # Enable job control
  set -m
  # Have to redirect stdin because firecracker wants to set stdin to raw mode
  # and this will not work if job control is anbled (i.e. firecracker
  # will receive a SIGTTIN/SIGTTOU signal and block).
  $FIRECRACKER --boot-timer --api-sock $FC_SOCKET $LOGGER < /dev/null &
  sleep 1
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output /dev/null \
             --unix-socket $FC_SOCKET \
             -X PUT 'http://localhost/snapshot/load' \
             -H  'Accept: application/json' \
             -H  'Content-Type: application/json' \
             -d "{ \"snapshot_path\": \"$RESTORE/snapshot_file\", \
                   \"mem_backend\": { \
                     \"backend_path\": \"$BACKEND_PATH\", \
                     \"backend_type\": \"$BACKEND_TYPE\" \
                   }, \
                   \"enable_diff_snapshots\": true, \
                   \"resume_vm\": true }")
  check_http_response $ret  "204" "Restore"
  # Bring the firecracker process into the forground
  fg %$FIRE > /dev/null
  exit 0
fi

if [[ -v METRICS ]]; then
  METRICS_PATH=${METRICS_PATH:-"/tmp/fireCRaCer-$TAP_DEVICE.metric"}
  # The metrics file has to be empty!
  truncate -s 0 $METRICS_PATH
  METRICS=$(cat <<EOF
  "metrics": {
    "metrics_path": "$METRICS_PATH"
  },
EOF
  )
fi

if [[ -v RW_IMAGE ]]; then
  RW_IMAGE=$(cat <<EOF
    {
      "drive_id": "rootfs-rw",
      "path_on_host": "$RW_IMAGE",
      "is_root_device": false,
      "is_read_only": false
    },
EOF
  )
  OVERLAY_ROOT="vdb"
else
  OVERLAY_ROOT="ram"
fi

if [ -z "$TEMPFS_SIZE" ]; then
    # Default size of tempfs
    TEMPFS_SIZE="128m"
fi

# Disable i8042 device probing to save some time
# See: https://github.com/firecracker-microvm/firecracker/blob/main/docs/api_requests/actions.md
DISABLE_I8042="i8042.noaux i8042.nomux i8042.nopnp i8042.dumbkbd"
# See: https://github.com/firecracker-microvm/firecracker-demo/blob/main/start-firecracker.sh
FAST_ARGS="tsc=reliable ipv6.disable=1 nomodules randomize_kstack_offset=n norandmaps mitigations=off $NO_SERIAL"
MISC_ARGS="console=ttyS0 reboot=k panic=1 pci=off"
FS_ARGS="overlay_root=$OVERLAY_ROOT overlay_size=$TEMPFS_SIZE"
# Notice: /opt/tools/ro_init.sh has to be present in the root file system image
BOOT_ARGS="$MISC_ARGS $DISABLE_I8042 $FAST_ARGS $FS_ARGS ip=$IP_SETTINGS init=/opt/tools/ro_init.sh $BOOT_ARGS"

CONFIG_FILE=$(mktemp --tmpdir 'vmconfig.XXXXXX')
# Delete CONFIG_FILE on exit
trap '{ rm -f -- "$CONFIG_FILE"; }' EXIT

cat <<EOF > $CONFIG_FILE
{
  "boot-source": {
    "kernel_image_path": "$KERNEL",
    "boot_args": "$BOOT_ARGS"
  },
  "drives": [
    $RW_IMAGE
    {
      "drive_id": "rootfs-ro",
      "path_on_host": "$IMAGE",
      "is_root_device": true,
      "is_read_only": true
    }
  ],
  "network-interfaces": [
    {
      "iface_id": "eth0",
      "guest_mac": "AA:FC:00:00:00:$TAP_DEVICE_NR_HEX",
      "host_dev_name": "$TAP_DEVICE"
    }
  ],
  $LOGGER
  $METRICS
  "machine-config": {
    "vcpu_count": 2,
    "mem_size_mib": 1024,
    "track_dirty_pages" : true
  }
}
EOF

rm -f $FC_SOCKET
echo "Running: $FIRECRACKER --boot-timer --api-sock $FC_SOCKET --config-file $CONFIG_FILE"
$FIRECRACKER --boot-timer --api-sock $FC_SOCKET --config-file $CONFIG_FILE

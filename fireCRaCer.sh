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
#
# Enable routing from Firecracker to the Internet (with <network-interface>=eth0,enp0s31f6,..):
# sudo sh -c "echo 1 > /proc/sys/net/ipv4/ip_forward"
# sudo iptables -t nat -A POSTROUTING -o <network-interface> -j MASQUERADE
# sudo iptables -A FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
# sudo iptables -A FORWARD -i tap0 -o <network-interface> -j ACCEPT
#
# Delete network device (tap*, veth*, ..):
# sudo ip link delete tap1
#
# Delete network namespace:
# sudo ip netns delete fc0
#
# In order to make MMDS (https://github.com/firecracker-microvm/firecracker/blob/main/docs/mmds/mmds-user-guide.md)
# work in the guest, we have to do the following in the guest:
#  ip route add ${MMDS_IPV4_ADDR} dev ${MMDS_NET_IF}
# where ${MMDS_IPV4_ADDR} corresponds to `mmds-config:ipv4_address" (see below) and
# ${MMDS_NET_IF} corresponds to `mmds-config:network_interfaces` (see below).
#
# When using "socat" instead of "curl" from within the guest to access MMD we have to use the `crnl` option:
#   socat - TCP:${MMDS_IPV4_ADDR}:80,crnl
#     GET / HTTP/1.1
# or simply do, if "curl" is available:
#   curl -v -H "Accept: application/json" http://${MMDS_IPV4_ADDR}/

FIRECRACKER=${FIRECRACKER:-"firecracker"}

KERNEL=${KERNEL:-"$MYPATH/deps/vmlinux"}
IMAGE=${IMAGE:-"$MYPATH/deps/rootfs.ext4"}
UFFD_HANDLER=${UFFD_HANDLER:-"$MYPATH/deps/uffd_handler"}
BALLOON_SIZE=${BALLOON_SIZE:-"100"}
BALLOON_POLLING_INTERVAL=${BALLOON_POLLING_INTERVAL:-"1"}

TAP_DEVICE=0

while getopts 'lmbdqpt:s:r:n:i:ukh?' opt; do
  case "$opt" in
    l)
      LOGGING=1
      ;;
    m)
      METRICS=1
      ;;
    b)
      BALLOONING=1
      ;;
    q)
      BALLOON_QUERY=1
      ;;
    p)
      BALLOON_PATCH=1
      ;;
    s)
      SNAPSHOT="$OPTARG"
      ;;
    t)
      TAP_DEVICE="$OPTARG"
      ;;
    r)
      RESTORE="$OPTARG"
      ;;
    n)
      NAMESPACE="$OPTARG"
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
    d)
      NO_SERIAL="8250.nr_uarts=0"
      ;;
    i)
      RW_IMAGE="$OPTARG"
      ;;
    k)
      KILL=1
      ;;
    ?|h)
      echo "Usage: fireCRaCer.sh [-l] [-m] [-b] [-t <tap>] [-d] [-i <rw-image>]"
      echo "       fireCRaCer.sh -s <snapshot-dir> [-t <tap>]"
      echo "       fireCRaCer.sh -r <snapshot-dir> [-t <tap>] [-n <namespace>] [-l] [-u [<log-file>]]"
      echo "       fireCRaCer.sh -q [-t <tap>]"
      echo "       fireCRaCer.sh -p [-t <tap>]"
      echo "       fireCRaCer.sh -k [-t <tap>]"
      echo ""
      echo " -l: enable Firecracker logging to LOG_PATH (default '/tmp/fireCRaCer-tap<tap>[-fc<namespace>].log')"
      echo "     with LOG_LEVEL and LOG_SHOW_LEVEL/LOG_SHOW_ORIGIN"
      echo " -m: enable Firecracker metrics to METRICS_PATH"
      echo " -b: use ballooning device of size BALLOON_SIZE (defaults to '$BALLOON_SIZE'mb) and"
      echo "     polling interval BALLOON_POLLING_INTERVAL (defaults to '$BALLOON_POLLING_INTERVAL's)"
      echo " -t <tap>: connect Firecracker to tap device 'tap<tap>' with <tap> from 0..255 (default 'tap0')."
      echo "            If the tap device doesn't exist, it will be created."
      echo " -d: Disable serial devices (i.e 8250.nr_uarts=0)."
      echo "     This saves ~100ms boot time but will disable the boot console."
      echo " -i <rw-image>: a file or device which will be used as read/write overlay for the root file system."
      echo "                By default a ram disk of TEMPFS_SIZE will be used."
      echo ""
      echo " -s: snapshot Firecracker on tap device 'tap<tap>' (default 'tap0') to <snapshot-dir>."
      echo "     If <snapshot-dir> doesn't exist, it will be created."
      echo "     SNAPSHOT_TYPE (defaults to 'Diff') determines the snapshot type (i.e. 'Full' or 'Diff')."
      echo ""
      echo " -r: restore Firecracker snapshotted on tap device'tap<tap>' (default 'tap0') from <snapshot-dir>."
      echo " -u: run with a userfaultfd memory backend and redirect its output to <log-file>"
      echo "     (defaults to '/tmp/fireCRaCer-uffd-'tap<tap>'.log')."
      echo "     The userfaultfd is started from UFFD_HANDLER (defaults to '$UFFD_HANDLER')."
      echo "     Options can be passed to userfaultfd by setting UFFD_OPTS."
      echo " -n: restore the snapshot in the network namespace 'fc<namespace>' with <namespace>"
      echo "      from 0..255. If the namespace doesn't exist, it will be created."
      echo ""
      echo " -q: query the ballooning statistics for Firecracker on tap device 'tap<tap>' (default 'tap0')"
      echo ""
      echo " -p: update balloning device settings for Firecracker on tap device 'tap<tap>' (default 'tap0')"
      echo "     to BALLOON_SIZE (defaults to '$BALLOON_SIZE'mb) and BALLOON_POLLING_INTERVAL (defaults to '$BALLOON_POLLING_INTERVAL's)."
      echo ""
      echo " -k: send Firecracker on tap device 'tap<tap>' (default 'tap0')"
      echo "     a CtrlAltDel message (i.e. shut it down)."
      exit 1
      ;;
  esac
done
shift "$(($OPTIND -1))"

echo_and_exec() { echo "  $@" ; "$@" ; }

if [[ ! -r /dev/kvm ]]; then
  echo "/dev/kvm not readable! This is required to run firecracker."
  echo "Running:"
  echo_and_exec sudo setfacl -m u:${USER}:rw /dev/kvm
fi

TAP_DEVICE_NR=`echo $TAP_DEVICE | grep -oP '^[0-9]{1,3}$'`
if [[ "$TAP_DEVICE_NR" != "" && $TAP_DEVICE_NR -lt 256 ]]; then
  TAP_DEVICE_NR=$( printf "%d" $TAP_DEVICE_NR )
  TAP_DEVICE_NR_HEX=$( printf "%.2x" $TAP_DEVICE_NR )
  TAP_DEVICE="tap$TAP_DEVICE_NR"
else
  echo "TAP_DEVICE must be a number between 0 and 255 (was '$TAP_DEVICE')"
  exit 1
fi

if error=`! ip link show $TAP_DEVICE 2>&1`; then
  echo "tap device $TAP_DEVICE not configured ($error)"
  echo "Running:"
  echo_and_exec sudo ip tuntap add dev $TAP_DEVICE mode tap
  echo_and_exec sudo ip addr add 172.16.$TAP_DEVICE_NR.1/24 dev $TAP_DEVICE
  echo_and_exec sudo ip link set $TAP_DEVICE up
fi
IP_SETTINGS=`ifconfig $TAP_DEVICE |
  awk '/inet.+netmask/ {
    gateway=$2
    split(gateway, ip, ".")
    netmask=$4
    printf("%s.%s.%s.%s::%s:%s::eth0:off\n", ip[1], ip[2], ip[3], ip[4] + 1, gateway, netmask)
  }'`
# echo $IP_SETTINGS


# See: https://github.com/firecracker-microvm/firecracker/blob/main/docs/snapshotting/network-for-clones.md
setup_namespace() {
  if error=`! ip netns list | grep $NAMESPACE -q 2>&1`; then
    echo "network namespace $NAMESPACE not configured ($error)"
    echo "Running:"
    # namespaces
    echo_and_exec sudo ip netns add $NAMESPACE
    # veth
    echo_and_exec sudo ip netns exec $NAMESPACE ip link add veth$NAMESPACE_NR type veth peer name veth
    # Move 'veth$NAMESPACE_NR' into the global default namespace (i.e. the one of pid '1')
    echo_and_exec sudo ip netns exec $NAMESPACE ip link set veth$NAMESPACE_NR netns 1
    echo_and_exec sudo ip netns exec $NAMESPACE ip addr add 10.0.$NAMESPACE_NR.2/24 dev veth
    echo_and_exec sudo ip netns exec $NAMESPACE ip link set dev veth up
    echo_and_exec sudo ip addr add 10.0.$NAMESPACE_NR.1/24 dev veth$NAMESPACE_NR
    echo_and_exec sudo ip link set dev veth$NAMESPACE_NR up
    echo_and_exec sudo ip netns exec $NAMESPACE ip route add default via 10.0.$NAMESPACE_NR.1
  fi
  if error=`! sudo ip netns exec $NAMESPACE ip link show $TAP_DEVICE 2>&1`; then
    echo "tap device $TAP_DEVICE in namespace $NAMESPACE not configured ($error)"
    echo "Running:"
    # tap in namespace
    echo_and_exec sudo ip netns exec $NAMESPACE ip tuntap add name $TAP_DEVICE mode tap
    echo_and_exec sudo ip netns exec $NAMESPACE ip addr add 172.16.$TAP_DEVICE_NR.1/24 dev $TAP_DEVICE
    echo_and_exec sudo ip netns exec $NAMESPACE ip link set $TAP_DEVICE up
    # iptables
    echo_and_exec sudo ip netns exec $NAMESPACE iptables -t nat -A POSTROUTING -o veth -s 172.16.$TAP_DEVICE_NR.2 -j SNAT --to 172.17.$NAMESPACE_NR.2
    echo_and_exec sudo ip netns exec $NAMESPACE iptables -t nat -A PREROUTING -i veth -d 172.17.$NAMESPACE_NR.2 -j DNAT --to 172.16.$TAP_DEVICE_NR.2
    echo_and_exec sudo ip route add 172.17.$NAMESPACE_NR.2 via 10.0.$NAMESPACE_NR.2
  fi
}

if [[ -v NAMESPACE ]]; then
  NAMESPACE_NR=`echo $NAMESPACE | grep -oP '^[0-9]{1,3}$'`
  if [[ "$NAMESPACE_NR" != "" && $NAMESPACE_NR -lt 256 ]]; then
    NAMESPACE_NR=$( printf "%d" $NAMESPACE_NR )
    NAMESPACE="fc$NAMESPACE_NR"
  else
    echo "NAMESPACE must be a number between 0 and 255 (was '$NAMESPACE')"
    exit 1
  fi
  # For the kill command, we don't have to setup network namespaces.
  # We just need the correct, namespace-awere Firecracker socket file.
  if [[ ! -v KILL ]]; then
    setup_namespace
    # We do `sudo -v` here because later we will use `NAMESPACE_CMD`
    # to prefix commands which will be started in the background.
    sudo -v
  fi
  NAMESPACE_SUFFIX="-$NAMESPACE"
  NAMESPACE_CMD="sudo ip netns exec $NAMESPACE sudo -u $USER "
fi

FC_SOCKET=${FC_SOCKET:-"/tmp/fireCRaCer-${TAP_DEVICE}${NAMESPACE_SUFFIX}.socket"}
FC_VSOCK=${FC_VSOCK:-"/tmp/fireCRaCer-$TAP_DEVICE.vsock"}

check_http_response() {
  actual="$1"
  expected="$2"
  message="$3"
  exit_on_error="${4:-yes}"
  if [[ "x$actual" == "x$expected" ]]; then
    echo "$message: OK"
  else
    echo "$message: Error (HTTTP response was $ret)"
    if [[ "x$exit_on_error" == "xyes" ]]; then
      echo "Exiting.."
      exit 1
    fi
  fi
}

if [[ -v KILL ]]; then
  echo "Killing firecracker instance running on tap device $TAP_DEVICE $NAMESPACE"
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

if [[ -v BALLOON_QUERY ]]; then
  echo "Querying ballooning statistics for firecracker instance on tap device $TAP_DEVICE $NAMESPACE"
  TMP=$(mktemp)
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output $TMP \
             --unix-socket $FC_SOCKET \
             -X GET 'http://localhost/balloon/statistics' \
             -H  'Accept: application/json')
  check_http_response $ret  "200" "Querying ballooning statistics"
  command -v python3 >/dev/null 2>&1 && PYTHON_3=yes
  if [[ -v PYTHON_3 ]]; then
    cat $TMP | python3 -m json.tool
  else
    cat $TMP
  fi
  exit 0
fi

if [[ -v BALLOON_PATCH ]]; then
  echo "Patching ballooning device settings for firecracker instance on tap device $TAP_DEVICE $NAMESPACE"
  ret=$(curl --write-out '%{http_code}' \
             --silent \
             --output /dev/null \
             --unix-socket $FC_SOCKET \
             -X PATCH 'http://localhost/balloon' \
             -H  'Accept: application/json' \
             -H  'Content-Type: application/json' \
             -d "{ \"amount_mib\": $BALLOON_SIZE
                    }")
  check_http_response $ret  "204" "Patching balloon settings"
  exit 0
fi

if [[ -v SNAPSHOT ]]; then
  if [ ! -d "$SNAPSHOT" ]; then
    mkdir -p $SNAPSHOT
  fi
  SNAPSHOT_TYPE=${SNAPSHOT_TYPE:-"Diff"}
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
             -d "{ \"snapshot_type\": \"$SNAPSHOT_TYPE\", \
                   \"snapshot_path\": \"$SNAPSHOT/snapshot_file\", \
                   \"mem_file_path\": \"$SNAPSHOT/mem_file\" }")
  # Don't use an explicit snapshot version to run with every release of Firecracker
  #                \"version\": \"1.0.0\" }")
  check_http_response $ret "204" "Snapshot" "no"
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
  LOG_PATH=${LOG_PATH:-"/tmp/fireCRaCer-${TAP_DEVICE}${NAMESPACE_SUFFIX}.log"}
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

if [[ -v BALLOONING ]]; then
  BALLOON=$(cat <<EOF
  "balloon": {
      "amount_mib": $BALLOON_SIZE,
      "deflate_on_oom": true,
      "stats_polling_interval_s": $BALLOON_POLLING_INTERVAL
  },
EOF
  )
fi

if [[ -v RESTORE ]]; then
  if [ ! -d "$RESTORE" ]; then
    echo "Error: can't access snapshot directory $RESTORE"
    exit 1
  fi
  if [[ -v USERFAULTFD ]]; then
    if [[ $USERFAULTFD = "1" ]]; then
      USERFAULTFD="/tmp/fireCRaCer-uffd-${TAP_DEVICE}${NAMESPACE_SUFFIX}.log"
    fi
    if [ ! -f "$UFFD_HANDLER" ]; then
      echo "Error: can't access userfaultfd daemon at $UFFD_HANDLER"
      exit 1
    fi
    if [[ -e /dev/userfaultfd && ! -r /dev/userfaultfd ]]; then
      echo "/dev/userfaultfd not readable! This is required to run firecracker with userfaultfd."
      echo "Running:"
      echo_and_exec sudo setfacl -m u:${USER}:rw /dev/userfaultfd
    fi
    UFFD_SOCKET=${UFFD_SOCKET:-"/tmp/fireCRaCer-uffd-${TAP_DEVICE}${NAMESPACE_SUFFIX}.socket"}
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
  rm -f $FC_VSOCK

  {
    # We do the API call to the Firecracker VMM from a background
    # process such that we can keep the firecracker process connected
    # to the stdin/stdout of the shell (this was fixed in Firecracker
    # by https://github.com/firecracker-microvm/firecracker/pull/2879).
    #
    # If we don't do this and instead run the firecracker process in the
    # background we would have to redirect stdin because firecracker wants
    # to set stdin to raw mode and this will not work if job control is
    # enabled (i.e. firecracker will receive a SIGTTIN/SIGTTOU signal and block).
    #
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
  }&

  echo "Running: $NAMESPACE_CMD $FIRECRACKER --boot-timer --api-sock $FC_SOCKET $LOGGER"
  $NAMESPACE_CMD $FIRECRACKER --boot-timer --api-sock $FC_SOCKET $LOGGER

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

if [ ! -z "$FC_JAVA_OPTIONS" ]; then
  BOOT_ARGS="$BOOT_ARGS FC_JAVA_OPTIONS=\"$FC_JAVA_OPTIONS\""
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

VCPU_COUNT=${VCPU_COUNT:-'2'}
MEM_SIZE=${MEM_SIZE:-'1024'}

CONFIG_FILE=$(mktemp --tmpdir 'vmconfig.XXXXXX')
# Delete CONFIG_FILE on exit
trap '{ rm -f -- "$CONFIG_FILE"; }' EXIT

cat <<EOF > $CONFIG_FILE
{
  "boot-source": {
    "kernel_image_path": "$KERNEL",
    "boot_args": "`sed 's/\"/\\\"/g;' <<< $BOOT_ARGS`"
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
  "mmds-config": {
    "network_interfaces": ["eth0"],
    "ipv4_address": "169.254.169.254",
    "version": "V1"
  },
  "vsock": {
    "guest_cid": $(expr $TAP_DEVICE_NR + 3),
    "uds_path": "$FC_VSOCK"
  },
  $LOGGER
  $METRICS
  $BALLOON
  "machine-config": {
    "vcpu_count": $VCPU_COUNT,
    "mem_size_mib": $MEM_SIZE,
    "track_dirty_pages" : true
  }
}
EOF

rm -f $FC_SOCKET
rm -f $FC_VSOCK
echo "Running: $FIRECRACKER --boot-timer --api-sock $FC_SOCKET --config-file $CONFIG_FILE"
$FIRECRACKER --boot-timer --api-sock $FC_SOCKET --config-file $CONFIG_FILE

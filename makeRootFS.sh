#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

DOCKER_IMG="crac.test"
DOCKER_FILE="$MYPATH/Dockerfile.ubuntu22"
ROOTFS_FILE="$MYPATH/deps/rootfs.ext4"
ROOTFS_SIZE="384"
CRAC_JDK="https://github.com/CRaC/openjdk-builds/releases/download/17-crac%2B3/openjdk-17-crac+3_linux-x64.tar.gz"

while getopts 'd:i:j:s:oh' opt; do
  case "$opt" in
    d)
      DOCKER_FILE="$OPTARG"
      ;;
    i)
      ROOTFS_FILE="$OPTARG"
      ;;
    j)
      CRAC_JDK="$OPTARG"
      ;;
    s)
      ROOTFS_SIZE="$OPTARG"
      ;;
    o)
      OVERWRITE_JDK=1
      ;;
    ?|h)
      echo "Usage: $(basename $0) [-d <docker-file>] [-i <root-fs-image>] [-s <root-fs-size>] [-j <jdk>] [-o]"
      echo "  -d <docker-file>: the Docker file to use (defaults to $DOCKER_FILE)"
      echo "  -i <root-fs-image>: file in which the root fs image will be created (defaults to $ROOTFS_FILE)."
      echo "  -i <root-fs-size>: size of the root file system (defaults to $ROOTFS_SIZE)."
      echo "  -j <jdk>: either a directory or an URL for the JDK (defaults to $CRAC_JDK)."
      echo "            The <jdk> will be chached in $MYPATH/deps/jdk."
      echo "            A new <jdk> will only be installed in the image if there's no cached version."
      echo "            Use -o to overwrite an existing, cached version"
      echo "  -o remove a chached <jdk> in $MYPATH/deps/jdk."
      exit 1
      ;;
  esac
done
shift "$(($OPTIND -1))"

# If you get "read: read error: 0: Resource temporarily unavailable" do the following:
# (see https://stackoverflow.com/questions/19895185/bash-shell-read-error-0-resource-temporarily-unavailable)
# > bash
# > exit
# The following also seems to help as well and can be done from within this script.From the sam SO question:
# Clearly (resource temporarily unavailable) this is caused by programs that exits but leaves STDIN in nonblocking mode.
perl -MFcntl -e 'fcntl STDIN, F_SETFL, fcntl(STDIN, F_GETFL, 0) & ~O_NONBLOCK'

read -p "Do you want to create \"$ROOTFS_FILE\" from Docker file"$'\n'"\"$DOCKER_FILE\" via the docker image \"$DOCKER_IMG\" (y/n)? " CONT
if [ "$CONT" != "y" ]; then
  echo $'\n'
  exit;
fi

MOUNT_DIR=$(mktemp -d --tmpdir 'makeRootFS.XXXXXX')
# Delete MOUNT_DIR on exit
trap '{ sudo umount $MOUNT_DIR || rmdir "$MOUNT_DIR"; }' EXIT

# Get CRaC/jdk
if [[ -v OVERWRITE_JDK ]]; then
  rm -rf $MYPATH/deps/jdk
fi
if [[ ! -d "$MYPATH/deps/jdk" ]]; then
  if [[ "$CRAC_JDK" =~ ^http[s]://.+ ]]; then
    echo "Downloading JDK from $CRAC_JDK"
    wget -O $MYPATH/deps/jdk.tgz "$CRAC_JDK"
    mkdir $MYPATH/deps/jdk
    tar -C $MYPATH/deps/jdk --strip-components=1 -xzf $MYPATH/deps/jdk.tgz
    rm $MYPATH/deps/jdk.tgz
  else
    echo "Copying JDK from $CRAC_JDK"
    cp -rf "$CRAC_JDK" $MYPATH/deps/jdk
  fi
else
  echo "Using cached JDK from $MYPATH/deps/jdk"
fi

# dd if=/dev/zero of=$ROOTFS_FILE bs=1M count=1024
rm -rf $ROOTFS_FILE
truncate -s ${ROOTFS_SIZE}M $ROOTFS_FILE
mkfs.ext4 $ROOTFS_FILE
sudo mount $ROOTFS_FILE $MOUNT_DIR

docker build -t $DOCKER_IMG -f $DOCKER_FILE .
docker_id=$(docker run -it --rm --detach $DOCKER_IMG)
# Need to use tar when copying files from container in order to preserve uid:gid of original files
docker cp --archive $docker_id:/ - | sudo tar -xf - --same-owner -C $MOUNT_DIR
docker kill $docker_id

if [[ ! -f "$MYPATH/deps/uffd_handler" ]]; then
  docker run --rm \
         -v $MYPATH/deps:/output \
         -v "$MYPATH/tools/uffd":/usr/src/myapp \
         -w /usr/src/myapp \
         -e RUSTFLAGS='--cfg feature="linux4_14" --cfg linux4_14' \
         rust:1.52.1 \
         /bin/bash -c "
           apt-get update;
           apt-get install -y libclang-dev --no-install-recommends;
           cargo build --release --bin uffd_handler --target-dir /tmp/cargo_target_dir;
           cp /tmp/cargo_target_dir/release/uffd_handler /output;
           chown $(id -u):$(id -g) /output/uffd_handler"
fi

#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

DOCKER_IMG="crac.test"
DOCKER_FILE="$MYPATH/Dockerfile.ubuntu22"
ROOTFS_FILE="$MYPATH/deps/rootfs.ext4"
ROOTFS_SIZE="384"
CRAC_JDK="https://github.com/CRaC/openjdk-builds/releases/download/17-crac%2B3/openjdk-17-crac+3_linux-x64.tar.gz"
JATTACH_URL="https://github.com/jattach/jattach/releases/download/v2.1/jattach"

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
    a)
      JATTACH_URL="$OPTARG"
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
      echo "  -a <jattach>: either a directory or an URL for 'jattach' (defaults to $JATTACH_URL)."
      echo "                'jattach' will be chached in $MYPATH/deps/jattach."
      echo "                A new 'jattach' will only be installed in the image if there's no cached version."
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
# The following also seems to help as well and can be done from within this script.From the same SO question:
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

# Get jattach
if [[ ! -f "$MYPATH/deps/jattach" ]]; then
  if [[ "$JATTACH_URL" =~ ^http[s]://.+ ]]; then
    echo "Downloading 'jattach' from $JATTACH_URL"
    wget -O $MYPATH/deps/jattach "$JATTACH_URL"
    chmod a+x $MYPATH/deps/jattach
  else
    echo "Copying 'jattach' from $JATTACH_URL"
    cp -rf "$JATTACH_URL" $MYPATH/deps/jattach
  fi
else
  echo "Using cached 'jattach' from $MYPATH/deps/jattach"
fi

# Build SuspendResumeAgent
if [[ ! -f "$MYPATH/deps/SuspendResumeAgent" ]]; then
  echo "Building deps/SuspendResumeAgent.jar"
  mkdir -p $MYPATH/deps/SuspendResumeAgent
  $MYPATH/deps/jdk/bin/javac -d $MYPATH/deps/SuspendResumeAgent \
                             $MYPATH/tools/SuspendResumeAgent/src/java/io/simonis/SuspendResumeAgent.java \
                             $MYPATH/tools/SuspendResumeAgent/src/java/io/simonis/crac/*.java \
                             $MYPATH/tools/SuspendResumeAgent/src/java/io/simonis/crac/impl/*.java
  g++ -fPIC -shared -I $MYPATH/deps/jdk/include/ -I $MYPATH/deps/jdk/include/linux/ \
      -o $MYPATH/deps/SuspendResumeAgent/libSuspendResumeAgent.so \
      $MYPATH/tools/SuspendResumeAgent/src/cpp/SuspendResumeAgent.cpp
  $MYPATH/deps/jdk/bin/jar -vcfm $MYPATH/deps/SuspendResumeAgent.jar \
                           $MYPATH/tools/SuspendResumeAgent/src/java/manifest.mf \
                           -C $MYPATH/deps/SuspendResumeAgent .
  rm -rf $MYPATH/deps/SuspendResumeAgent
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

# Build uffd_handler
# We need to pull the right base OS (-bullseye for Ubuntu 20.04, -buster for 18.04 and -bookworm for 22.04)
# See https://askubuntu.com/questions/445487/what-debian-version-are-the-different-ubuntu-versions-based-on
if [[ ! -f "$MYPATH/deps/uffd_handler" ]]; then
  echo "Building deps/uffd_handler.jar"
  docker run --rm \
         -v $MYPATH/deps:/output \
         -v "$MYPATH/tools/uffd":/usr/src/myapp \
         -w /usr/src/myapp \
         -e RUSTFLAGS='--cfg feature="linux4_14" --cfg linux4_14' \
         rust:1.73.0-bullseye \
         /bin/bash -c "
           apt-get update;
           apt-get install -y libclang-dev --no-install-recommends;
           cargo build --release --bin uffd_handler --target-dir /tmp/cargo_target_dir;
           cp /tmp/cargo_target_dir/release/uffd_handler /output;
           chown $(id -u):$(id -g) /output/uffd_handler"
fi

# Build UffdVisualizer
if [[ ! -f "$MYPATH/deps/UffdVisualizer" ]]; then
  echo "Building deps/UffdVisualizer.jar"
  mkdir -p $MYPATH/deps/UffdVisualizer
  $MYPATH/deps/jdk/bin/javac --enable-preview --release 17 -d $MYPATH/deps/UffdVisualizer \
                             $MYPATH/tools/UffdVisualizer/src/io/simonis/UffdVisualizer.java
  unzip $MYPATH/tools/UffdVisualizer/deps/jlfgr-1_0.jar toolbarButtonGraphics/media/* -d $MYPATH/deps/UffdVisualizer
  $MYPATH/deps/jdk/bin/jar -vcfe $MYPATH/deps/UffdVisualizer.jar io.simonis.UffdVisualizer -C $MYPATH/deps/UffdVisualizer .
  rm -rf $MYPATH/deps/UffdVisualizer
fi

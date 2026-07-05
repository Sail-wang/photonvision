###
# Alternative ARM Runner installer to setup PhotonVision JAR
# for ARM based builds such as Raspberry Pi, Orange Pi, etc.
# This assumes that the image provided to arm-runner-action contains
# the servicefile needed to auto-launch PhotonVision.
###
NEW_JAR=$(realpath $(find . -name photonvision\*-linuxarm64.jar))
echo "Using jar: " $(basename $NEW_JAR)

DEST_PV_LOCATION=/opt/photonvision
sudo mkdir -p $DEST_PV_LOCATION
sudo cp $NEW_JAR ${DEST_PV_LOCATION}/photonvision.jar

# Install Hikvision MVS native libraries for ARM64
HIK_NATIVE_DIR="$(dirname "$0")/native/arm64"
if [ -d "$HIK_NATIVE_DIR" ]; then
    echo "Installing Hikvision native libraries..."
    sudo cat "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb.* > "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb
    sudo apt-get install -y "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb
    sudo rm "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb*
else
    echo "Hikvision native library directory not found, skipping ($HIK_NATIVE_DIR)"
fi

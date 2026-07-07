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

# Ensure LD_LIBRARY_PATH is set in the photonvision systemd service
PV_SERVICE_FILE=$(sudo find /etc/systemd /lib/systemd -name photonvision.service -type f 2>/dev/null | head -1)
if [ -n "$PV_SERVICE_FILE" ]; then
    echo "Updating service file: $PV_SERVICE_FILE"
    # Only add the line if it doesn't already exist
    if ! sudo grep -q 'Environment=LD_LIBRARY_PATH=/opt/MVS/lib/aarch64' "$PV_SERVICE_FILE"; then
        sudo sed -i '/^\[Service\]$/a Environment=LD_LIBRARY_PATH=/opt/MVS/lib/aarch64' "$PV_SERVICE_FILE"
        echo "Added LD_LIBRARY_PATH to service file."
    else
        echo "LD_LIBRARY_PATH already present in service file."
    fi
else
    echo "Warning: photonvision.service not found in /etc/systemd or /lib/systemd"
fi

# Install Hikvision MVS native libraries for ARM64
HIK_NATIVE_DIR="$(dirname "$0")/native/arm64"
if [ -d "$HIK_NATIVE_DIR" ]; then
    echo "Installing Hikvision native libraries..."
    sudo cat "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb.* > "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb
    sudo apt-get install -y "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb
    sudo rm "$HIK_NATIVE_DIR"/MVS-5.0.1_aarch64_20260512.deb*
    # Kill any MVS background processes that hold /dev references (e.g. logserver)
    sudo pkill -f '/opt/MVS/' 2>/dev/null || true
else
    echo "Hikvision native library directory not found, skipping ($HIK_NATIVE_DIR)"
fi

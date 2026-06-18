/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.camera.HikvisionCamera;

import static MvCameraControlWrapper.MvCameraControlDefines.*;

import MvCameraControlWrapper.MvCameraControl;
import MvCameraControlWrapper.MvCameraControlDefines;
import MvCameraControlWrapper.MvCameraControlDefines.MV_CC_DEVICE_INFO;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.camera.PVCameraInfo.PVHikvisionCameraInfo;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.frame.FrameProvider;
import org.photonvision.vision.frame.provider.HikvisionFrameProvider;
import org.photonvision.vision.processes.VisionSource;
import org.photonvision.vision.processes.VisionSourceSettables;

/**
 * VisionSource implementation for Hikvision/MVS industrial cameras (USB only).
 *
 * <p>Uses the Hikvision MVS SDK via {@code MvCameraControlWrapper.jar} + native library. Frames are
 * acquired via callback ({@code MV_CC_RegisterImageCallBackEx}) and converted to OpenCV Mats.
 *
 * <p>Supported on:
 *
 * <ul>
 *   <li>Windows x64 (MvCameraControl.dll)
 *   <li>Linux x86_64 (libMvCameraControl.so)
 *   <li>Linux ARM64 (libMvCameraControl.so) — same SDK as x86
 * </ul>
 */
public class HikvisionVisionSource extends VisionSource {
    private static final Logger logger = new Logger(HikvisionVisionSource.class, LogGroup.Camera);

    private final HikvisionSettables settables;
    private final HikvisionFrameProvider frameProvider;

    private MvCameraControlDefines.Handle cameraHandle;
    private boolean grabbing = false;
    private boolean initialized = false;

    public HikvisionVisionSource(CameraConfiguration configuration) {
        super(configuration);

        if (getCameraConfiguration().cameraQuirks == null) {
            getCameraConfiguration().cameraQuirks = QuirkyCamera.DefaultCamera;
        }

        // Handle created later in openCamera, settables/frameProvider created here
        this.settables = new HikvisionSettables(configuration, null); // handle set after open
        this.frameProvider = new HikvisionFrameProvider(settables);
    }

    /**
     * Initialize SDK. MV_CC_Initialize is handled by VisionSourceManager lazily; this method exists
     * for the case where a HikvisionVisionSource is used standalone. Repeated calls are harmless (MVS
     * SDK tolerates re-init).
     *
     * @return true if initialization succeeded
     */
    public boolean initialize() {
        if (initialized) return true;

        try {
            // MV_CC_Initialize is already called by
            // VisionSourceManager.ensureHikvisionSdkInitialized().
            // We try it here too for standalone usage; the SDK tolerates duplicate init
            // gracefully.
            try {
                int ret = MvCameraControl.MV_CC_Initialize();
                if (ret != MV_OK) {
                    logger.debug(
                            "MV_CC_Initialize returned: "
                                    + String.format("0x%x", ret)
                                    + " (may already be initialized)");
                }
            } catch (UnsatisfiedLinkError e) {
                logger.info(
                        "MV_CC_Initialize not available — assuming implicit SDK initialization (macOS?)");
            }

            initialized = true;
            return true;
        } catch (Exception e) {
            logger.error("Failed to initialize Hikvision SDK", e);
            return false;
        }
    }

    /**
     * Open the camera device by its serial number.
     *
     * @param serialNumber Camera serial number (from {@link PVHikvisionCameraInfo})
     * @return true if the camera was opened successfully
     */
    public boolean openCamera(String serialNumber) {
        if (!initialized && !initialize()) {
            return false;
        }

        try {
            // Enumerate USB devices
            var deviceList = MvCameraControl.MV_CC_EnumDevices(MV_USB_DEVICE | MV_VIR_USB_DEVICE);
            if (deviceList.isEmpty()) {
                logger.error("No Hikvision USB cameras found");
                return false;
            }

            // Find the camera with matching serial number
            MV_CC_DEVICE_INFO matchedDevice = null;
            for (var device : deviceList) {
                if ((device.transportLayerType & (MV_USB_DEVICE | MV_VIR_USB_DEVICE)) != 0
                        && device.usb3VInfo.serialNumber != null
                        && device.usb3VInfo.serialNumber.equals(serialNumber)) {
                    matchedDevice = device;
                    break;
                }
            }

            if (matchedDevice == null) {
                // If no exact match, try the first available USB camera
                for (var device : deviceList) {
                    if ((device.transportLayerType & (MV_USB_DEVICE | MV_VIR_USB_DEVICE)) != 0) {
                        matchedDevice = device;
                        logger.info(
                                "Using first available Hikvision USB camera: " + device.usb3VInfo.userDefinedName);
                        break;
                    }
                }
            }

            if (matchedDevice == null) {
                logger.error("No matching Hikvision USB camera found for serial: " + serialNumber);
                return false;
            }

            logger.info(
                    "Opening Hikvision camera: "
                            + matchedDevice.usb3VInfo.userDefinedName
                            + " (S/N: "
                            + matchedDevice.usb3VInfo.serialNumber
                            + ")");

            // Create handle and open device
            cameraHandle = MvCameraControl.MV_CC_CreateHandle(matchedDevice);
            int ret = MvCameraControl.MV_CC_OpenDevice(cameraHandle);
            if (ret != MV_OK) {
                logger.error("MV_CC_OpenDevice failed with code: " + String.format("0x%x", ret));
                return false;
            }

            settables.setHandle(cameraHandle);
            settables.readCameraLimits();

            // Trigger onCameraConnected so frameStaticProperties is calculated
            settables.onCameraConnected();

            return true;
        } catch (Exception e) {
            logger.error("Failed to open Hikvision camera", e);
            return false;
        }
    }

    /**
     * Start grabbing frames via callback.
     *
     * @return true if grabbing started successfully
     */
    public boolean startGrabbing() {
        if (cameraHandle == null) {
            logger.error("Camera not opened — call openCamera() first");
            return false;
        }

        if (grabbing) return true;

        try {
            // Set trigger mode to off (continuous capture)
            MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, "TriggerMode", "Off");
            MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, "AcquisitionMode", "Continuous");

            // Give the frame provider access to the handle for polling
            frameProvider.setHandle(cameraHandle);

            // Start grabbing
            int ret = MvCameraControl.MV_CC_StartGrabbing(cameraHandle);
            if (ret != MV_OK) {
                logger.error("MV_CC_StartGrabbing failed with code: " + String.format("0x%x", ret));
                return false;
            }

            grabbing = true;
            logger.info("Hikvision camera grabbing started");
            return true;
        } catch (Exception e) {
            logger.error("Failed to start grabbing", e);
            return false;
        }
    }

    /** Stop grabbing and close the camera. */
    public void stopAndClose() {
        try {
            if (cameraHandle != null) {
                if (grabbing) {
                    MvCameraControl.MV_CC_StopGrabbing(cameraHandle);
                    grabbing = false;
                }
                MvCameraControl.MV_CC_CloseDevice(cameraHandle);
                MvCameraControl.MV_CC_DestroyHandle(cameraHandle);
                cameraHandle = null;
            }
        } catch (Exception e) {
            logger.error("Error stopping/closing Hikvision camera", e);
        }
    }

    /**
     * Finalize SDK. Call on shutdown. Tries {@code MV_CC_Finalize} — if unavailable (macOS), silently
     * ignores.
     */
    public void finalizeSDK() {
        stopAndClose();
        try {
            try {
                MvCameraControl.MV_CC_Finalize();
            } catch (UnsatisfiedLinkError e) {
                logger.info("MV_CC_Finalize not available — implicit finalization (macOS?)");
            }
        } catch (Exception e) {
            logger.error("Error finalizing Hikvision SDK", e);
        }
        initialized = false;
    }

    @Override
    public FrameProvider getFrameProvider() {
        return frameProvider;
    }

    @Override
    public VisionSourceSettables getSettables() {
        return settables;
    }

    @Override
    public boolean isVendorCamera() {
        return false;
    }

    @Override
    public boolean hasLEDs() {
        return false;
    }

    @Override
    public void remakeSettables() {
        // Settables don't need remaking for Hikvision cameras
    }

    @Override
    public void release() {
        stopAndClose();
        frameProvider.release();
    }
}

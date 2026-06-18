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
import MvCameraControlWrapper.MvCameraControlDefines.MVCC_INTVALUE;
import edu.wpi.first.cscore.VideoMode;
import edu.wpi.first.util.PixelFormat;
import java.util.HashMap;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.camera.PVCameraInfo.PVHikvisionCameraInfo;
import org.photonvision.vision.processes.VisionSourceSettables;

/**
 * Settables for Hikvision/MVS industrial cameras. Communicates with the camera via GenICam string
 * node names through MV_CC_SetIntValue, MV_CC_SetFloatValue, MV_CC_SetEnumValueByString.
 */
public class HikvisionSettables extends VisionSourceSettables {
    private static final Logger logger = new Logger(HikvisionSettables.class, LogGroup.Camera);

    // ── GenICam node name constants ──────────────────────────────
    private static final String NODE_EXPOSURE_TIME = "ExposureTime";
    private static final String NODE_EXPOSURE_AUTO = "ExposureAuto";
    private static final String NODE_AUTO_EXPOSURE_TIME_LOWER_LIMIT = "AutoExposureTimeLowerLimit";
    private static final String NODE_AUTO_EXPOSURE_TIME_UPPER_LIMIT = "AutoExposureTimeUpperLimit";
    private static final String NODE_GAIN = "Gain";
    private static final String NODE_GAIN_AUTO = "GainAuto";
    private static final String NODE_AUTO_GAIN_LOWER_LIMIT = "AutoGainLowerLimit";
    private static final String NODE_AUTO_GAIN_UPPER_LIMIT = "AutoGainUpperLimit";
    private static final String NODE_BRIGHTNESS = "Brightness";
    private static final String NODE_BALANCE_WHITE_AUTO = "BalanceWhiteAuto";
    private static final String NODE_BALANCE_RATIO_SELECTOR = "BalanceRatioSelector";
    private static final String NODE_BALANCE_RATIO = "BalanceRatio";
    private static final String NODE_WIDTH = "Width";
    private static final String NODE_OFFSET_X = "OffsetX";
    private static final String NODE_OFFSET_Y = "OffsetY";
    private static final String NODE_HEIGHT = "Height";
    private static final String NODE_PIXEL_FORMAT = "PixelFormat";

    // ── Common video modes ───────────────────────────────────────
    private static final VideoMode[] HIKVISION_MONO_VIDEO_MODES = {
        new VideoMode(PixelFormat.kGray, 1440, 1080, 120),
        new VideoMode(PixelFormat.kGray, 1280, 960, 120),
        new VideoMode(PixelFormat.kGray, 640, 480, 120)
    };

    private static final VideoMode[] HIKVISION_COLOR_VIDEO_MODES =
            new VideoMode[] {
                new VideoMode(PixelFormat.kBGR, 1440, 1080, 120),
                new VideoMode(PixelFormat.kBGR, 1280, 960, 120),
                new VideoMode(PixelFormat.kBGR, 640, 480, 120)
            };

    private MvCameraControlDefines.Handle cameraHandle;
    private VideoMode currentVideoMode = HIKVISION_MONO_VIDEO_MODES[0];
    private long minExposureRaw = 15;
    private long maxExposureRaw = 9999813;
    private long lastExposureRaw = -1;
    private long maxWidth = 1440;
    private long maxHeight = 1080;
    private float minGain = 0;
    private float maxGain = 17;
    private float lastGain = -1;

    public HikvisionSettables(
            CameraConfiguration configuration, MvCameraControlDefines.Handle handle) {
        super(configuration);
        this.cameraHandle = handle;
        for (int i = 0; i < HIKVISION_MONO_VIDEO_MODES.length; i++) {
            this.videoModes.put(i, HIKVISION_MONO_VIDEO_MODES[i]);
        }
        if (configuration.matchedCameraInfo instanceof PVHikvisionCameraInfo hikInfo
                && hikInfo.modelName.matches(".*-\\\\d+[A-Z]+C.*")) {
            for (int i = HIKVISION_MONO_VIDEO_MODES.length;
                    i < HIKVISION_MONO_VIDEO_MODES.length + HIKVISION_COLOR_VIDEO_MODES.length;
                    i++) {
                this.videoModes.put(i, HIKVISION_COLOR_VIDEO_MODES[i]);
            }
        }
        if (handle != null) readCameraLimits();
    }

    public void setHandle(MvCameraControlDefines.Handle handle) {
        this.cameraHandle = handle;
        if (handle != null) readCameraLimits();
    }

    /** Read exposure and gain limits from the camera. */
    public void readCameraLimits() {
        if (cameraHandle == null) return;
        try {
            var intVal = new MVCC_INTVALUE();
            if (MvCameraControl.MV_CC_GetIntValue(cameraHandle, NODE_EXPOSURE_TIME, intVal) == MV_OK) {
                minExposureRaw = intVal.min;
                maxExposureRaw = intVal.max;
            }
        } catch (Exception e) {
            /* use SDK-documented defaults */ }
        try {
            var intVal = new MVCC_INTVALUE();
            if (MvCameraControl.MV_CC_GetIntValue(cameraHandle, NODE_GAIN, intVal) == MV_OK) {
                minGain = intVal.min;
                maxGain = intVal.max;
            }
        } catch (Exception e) {
            /* use SDK-documented defaults */ }
        MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_OFFSET_X, 0);
        MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_OFFSET_Y, 0);
        try {
            var intVal = new MVCC_INTVALUE();

            if (MvCameraControl.MV_CC_GetIntValue(cameraHandle, NODE_WIDTH, intVal) == MV_OK) {
                maxWidth = intVal.max;
            }
        } catch (Exception e) {
            /* use SDK-documented defaults */ }
        try {
            var intVal = new MVCC_INTVALUE();
            if (MvCameraControl.MV_CC_GetIntValue(cameraHandle, NODE_HEIGHT, intVal) == MV_OK) {
                maxHeight = intVal.max;
            }
        } catch (Exception e) {
            /* use SDK-documented defaults */ }
    }

    /**
     * Called once when the camera is first connected. Overrides the base to trigger {@link
     * #calculateFrameStaticProps()} so the pipeline has valid properties.
     */
    @Override
    public void onCameraConnected() {
        super.onCameraConnected();
        calculateFrameStaticProps();
    }

    // ── Exposure ─────────────────────────────────────────────────

    @Override
    public void setExposureRaw(double exposureRaw) {
        long clamped = Math.max(minExposureRaw, Math.min(maxExposureRaw, (long) exposureRaw));
        try {
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_EXPOSURE_TIME, clamped);
            lastExposureRaw = clamped;
        } catch (Exception e) {
            logger.debug("Error setting exposure: " + e.getMessage());
        }
    }

    @Override
    public double getMinExposureRaw() {
        return (double) minExposureRaw;
    }

    @Override
    public double getMaxExposureRaw() {
        return (double) maxExposureRaw;
    }

    @Override
    public void setAutoExposure(boolean cameraAutoExposure) {
        try {
            if (cameraAutoExposure) {
                // When enabling auto exposure, constrain the auto range
                setAutoExposureLimits(15, 5840);
                MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_EXPOSURE_AUTO, "Continuous");
            } else {
                MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_EXPOSURE_AUTO, "Off");
                // Restore last manual exposure if we had one
                if (lastExposureRaw > 0) {
                    setExposureRaw(lastExposureRaw);
                }
            }
        } catch (Exception e) {
            logger.debug("Error setting auto exposure: " + e.getMessage());
        }
    }

    /**
     * Constrain the auto-exposure range so the algorithm doesn't pick unreasonable values.
     *
     * @param lowerUs Lower limit in microseconds (SDK range: [15, 5840])
     * @param upperUs Upper limit in microseconds (SDK range: [15, 9999813])
     */
    public void setAutoExposureLimits(long lowerUs, long upperUs) {
        try {
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_AUTO_EXPOSURE_TIME_LOWER_LIMIT, lowerUs);
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_AUTO_EXPOSURE_TIME_UPPER_LIMIT, upperUs);
        } catch (Exception e) {
            logger.debug("Error setting auto exposure limits: " + e.getMessage());
        }
    }

    // ── White Balance ────────────────────────────────────────────

    @Override
    public void setAutoWhiteBalance(boolean autowb) {
        try {
            MvCameraControl.MV_CC_SetEnumValueByString(
                    cameraHandle, NODE_BALANCE_WHITE_AUTO, autowb ? "Continuous" : "Off");
        } catch (Exception e) {
            logger.debug("Error setting auto WB: " + e.getMessage());
        }
    }

    @Override
    public void setWhiteBalanceTemp(double temp) {
        // Hikvision uses BalanceRatio (Integer, range [1, 16376]), not color
        // temperature.
        // BalanceRatio represents ratio × 1024, so 1024 = 1.0×.
        // Warmer (lower K) → increase Red, decrease Blue.
        // Cooler (higher K) → increase Blue, decrease Red.
        double clampedTemp = Math.max(2000, Math.min(10000, temp));
        // At 6500K: both ratios are ~1024 (neutral). At 2000K: Red≈3328, Blue≈315.
        long redRatio = Math.round(1024.0 * 6500.0 / clampedTemp);
        long blueRatio = Math.round(1024.0 * clampedTemp / 6500.0);
        redRatio = Math.max(1, Math.min(16376, redRatio));
        blueRatio = Math.max(1, Math.min(16376, blueRatio));
        try {
            MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_BALANCE_RATIO_SELECTOR, "Red");
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_BALANCE_RATIO, redRatio);
            MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_BALANCE_RATIO_SELECTOR, "Blue");
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_BALANCE_RATIO, blueRatio);
        } catch (Exception e) {
            logger.debug("Error setting WB temp: " + e.getMessage());
        }
    }

    @Override
    public double getMinWhiteBalanceTemp() {
        return 2000;
    }

    @Override
    public double getMaxWhiteBalanceTemp() {
        return 10000;
    }

    // ── Gain / Brightness ────────────────────────────────────────

    @Override
    public void setGain(int gain) {
        float clamped = Math.max(minGain, Math.min(maxGain, (float) gain));
        try {
            MvCameraControl.MV_CC_SetFloatValue(cameraHandle, NODE_GAIN, clamped);
            lastGain = clamped;
        } catch (Exception e) {
            logger.debug("Error setting gain: " + e.getMessage());
        }
    }

    /** Enable or disable auto gain (AGC). When disabling, restores the last manual gain value. */
    public void setAutoGain(boolean autoGain) {
        try {
            if (autoGain) {
                MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_GAIN_AUTO, "Continuous");
                setAutoGainLimits((float) minGain, (float) maxGain);
            } else {
                MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_GAIN_AUTO, "Off");
                if (lastGain > 0) {
                    MvCameraControl.MV_CC_SetFloatValue(cameraHandle, NODE_GAIN, lastGain);
                }
            }
        } catch (Exception e) {
            logger.debug("Error setting auto gain: " + e.getMessage());
        }
    }

    /**
     * Constrain the auto-gain range.
     *
     * @param lowerDb Lower limit in dB (SDK range: [0, 16.98])
     * @param upperDb Upper limit in dB (SDK range: [0, 16.98])
     */
    public void setAutoGainLimits(float lowerDb, float upperDb) {
        try {
            MvCameraControl.MV_CC_SetFloatValue(cameraHandle, NODE_AUTO_GAIN_LOWER_LIMIT, lowerDb);
            MvCameraControl.MV_CC_SetFloatValue(cameraHandle, NODE_AUTO_GAIN_UPPER_LIMIT, upperDb);
        } catch (Exception e) {
            logger.debug("Error setting auto gain limits: " + e.getMessage());
        }
    }

    @Override
    public void setBrightness(int brightness) {
        try {
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_BRIGHTNESS, brightness);
        } catch (Exception e) {
            logger.debug("Error setting brightness: " + e.getMessage());
        }
    }

    // ── Video Mode ───────────────────────────────────────────────

    @Override
    public VideoMode getCurrentVideoMode() {
        return currentVideoMode;
    }

    @Override
    public void setVideoModeIndex(int index) {
        var mode = videoModes.get(index);
        if (mode != null) {
            setVideoMode(mode);
        } else {
            logger.warn("Requested video mode index " + index + " not found, ignoring");
        }
    }

    /**
     * Set camera resolution and pixel format via GenICam.
     *
     * <p>Width and Height are int64 with step=4. PixelFormat is set by enum string:
     *
     * <ul>
     *   <li>{@code PixelFormat.kBGR} → {@code "BGR8Packed"} (0x02180015)
     *   <li>{@code PixelFormat.kGray} → {@code "Mono8"} (0x01080001)
     * </ul>
     */
    @Override
    protected void setVideoModeInternal(VideoMode videoMode) {
        try {
            assert videoMode.width <= maxWidth && videoMode.height <= maxHeight;
            assert videoMode.width % 4 == 0 && videoMode.height % 4 == 0;
            MvCameraControl.MV_CC_StopGrabbing(cameraHandle);
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_OFFSET_X, 0);
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_OFFSET_Y, 0);
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_WIDTH, videoMode.width);
            MvCameraControl.MV_CC_SetIntValue(cameraHandle, NODE_HEIGHT, videoMode.height);
            MvCameraControl.MV_CC_SetIntValue(
                    cameraHandle, NODE_OFFSET_X, (long) ((maxWidth - videoMode.width) / 2));
            MvCameraControl.MV_CC_SetIntValue(
                    cameraHandle, NODE_OFFSET_Y, (long) ((maxHeight - videoMode.height) / 2));

            String pf = videoMode.pixelFormat == PixelFormat.kGray ? "Mono8" : "BGR8Packed";
            MvCameraControl.MV_CC_SetEnumValueByString(cameraHandle, NODE_PIXEL_FORMAT, pf);
            this.currentVideoMode = videoMode;
            calculateFrameStaticProps();
            logger.info(
                    "x"
                            + videoMode.width
                            + "y"
                            + videoMode.height
                            + " xf "
                            + ((maxWidth - videoMode.width) / 2)
                            + " yf "
                            + ((maxHeight - videoMode.height) / 2));
        } catch (Exception e) {
            logger.debug("Error setting video mode: " + e.getMessage());
        } finally {
            MvCameraControl.MV_CC_StartGrabbing(cameraHandle);
        }
    }

    @Override
    public HashMap<Integer, VideoMode> getAllVideoModes() {
        return videoModes;
    }
}

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

package org.photonvision.vision.frame.provider;

import static MvCameraControlWrapper.MvCameraControlDefines.MV_OK;

import MvCameraControlWrapper.MvCameraControl;
import MvCameraControlWrapper.MvCameraControlDefines;
import MvCameraControlWrapper.MvCameraControlDefines.MV_FRAME_OUT;
import MvCameraControlWrapper.MvCameraControlDefines.MvGvspPixelType;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.math.MathUtils;
import org.photonvision.vision.camera.HikvisionCamera.HikvisionSettables;
import org.photonvision.vision.opencv.CVMat;

/**
 * Frame provider for Hikvision/MVS industrial cameras. Extends {@link CpuImageProcessor} so
 * rotation, thresholding, and Frame construction follow the standard pipeline. Uses {@code
 * MV_CC_GetImageBuffer} with {@code MV_FRAME_OUT} per the official MVS SDK Java sample.
 */
public class HikvisionFrameProvider extends CpuImageProcessor {
    private static final Logger logger = new Logger(HikvisionFrameProvider.class, LogGroup.Camera);

    private final HikvisionSettables settables;
    private MvCameraControlDefines.Handle cameraHandle;
    private final MV_FRAME_OUT stFrameOut = new MV_FRAME_OUT();
    private boolean firstFrameLogged = false;

    public HikvisionFrameProvider(HikvisionSettables settables) {
        this.settables = settables;
        this.cameraPropertiesCached = true;
    }

    public void setHandle(MvCameraControlDefines.Handle handle) {
        this.cameraHandle = handle;
    }

    @Override
    protected CapturedFrame getInputMat() {
        if (cameraHandle == null) return null;

        int ret = MvCameraControl.MV_CC_GetImageBuffer(cameraHandle, stFrameOut, 100);

        if (ret != MV_OK) {
            return null;
        }

        int width = stFrameOut.mvFrameOutInfo.ExtendWidth;
        int height = stFrameOut.mvFrameOutInfo.ExtendHeight;
        int frameLen = stFrameOut.mvFrameOutInfo.frameLen;
        MvGvspPixelType pixelType = stFrameOut.mvFrameOutInfo.pixelType;
        byte[] data = stFrameOut.buffer;

        if (width <= 0 || height <= 0 || frameLen <= 0 || data == null || data.length < frameLen) {
            MvCameraControl.MV_CC_FreeImageBuffer(cameraHandle, stFrameOut);
            return null;
        }

        if (!firstFrameLogged) {
            logger.info(
                    "First Hikvision frame: "
                            + width
                            + "x"
                            + height
                            + " len="
                            + frameLen
                            + " pixelType="
                            + pixelType
                            + " props="
                            + (settables.getFrameStaticProperties() != null
                                    ? settables.getFrameStaticProperties().imageWidth
                                            + "x"
                                            + settables.getFrameStaticProperties().imageHeight
                                    : "null"));
            firstFrameLogged = true;
        }

        try {
            CVMat colorMat;

            switch (pixelType) {
                case PixelType_Gvsp_Mono8:
                    // Mono8 -> convert to BGR for pipeline compatibility
                    Mat grayMat = new Mat(height, width, CvType.CV_8UC1);
                    grayMat.put(0, 0, data, 0, frameLen);
                    Mat bgrMat = new Mat();
                    Imgproc.cvtColor(grayMat, bgrMat, Imgproc.COLOR_GRAY2BGR);
                    colorMat = new CVMat(bgrMat);
                    grayMat.release();
                    break;
                case PixelType_Gvsp_BGR8_Packed:
                    Mat bgr = new Mat(height, width, CvType.CV_8UC3);
                    bgr.put(0, 0, data, 0, frameLen);
                    colorMat = new CVMat(bgr);
                    bgr.release();
                    break;
                default:
                    logger.error("Unsupported pixel type: " + pixelType);
                    colorMat = new CVMat();
                    break;
            }

            MvCameraControl.MV_CC_FreeImageBuffer(cameraHandle, stFrameOut);

            return new CapturedFrame(
                    colorMat, settables.getFrameStaticProperties(), MathUtils.wpiNanoTime());
        } catch (Exception e) {
            logger.error("Error converting Hikvision frame: " + e.getMessage());
            MvCameraControl.MV_CC_FreeImageBuffer(cameraHandle, stFrameOut);
            return null;
        }
    }

    @Override
    public String getName() {
        return "HikvisionFrameProvider";
    }

    @Override
    public boolean isConnected() {
        return cameraHandle != null;
    }

    @Override
    public boolean checkCameraConnected() {
        return cameraHandle != null;
    }

    @Override
    public void release() {
        cameraHandle = null;
    }
}

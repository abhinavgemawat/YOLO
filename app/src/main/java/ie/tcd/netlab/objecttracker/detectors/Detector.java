package ie.tcd.netlab.objecttracker.detectors;

import android.graphics.Bitmap;
import android.media.Image;

import java.util.List;

import ie.tcd.netlab.objecttracker.helpers.Recognition;

public abstract class Detector {

    public abstract Detections recognizeImage(Image image, int rotation); // take Image as input
    public Detections recognize(byte[] yuv, int image_w, int image_h, int rotation, Bitmap b) {
        // just a stub
        return new Detections();
    }

    public Detections frameworkMaxAreaRectangle(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        // just a stub
        return new Detections();
    }


    public Detections frameworkMaxAreaRectBD(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        // just a stub
        return new Detections();
    }

    public Detections nineWrapper(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        // just a stub
        return new Detections();
    }

    public Detections frameworkQuadrant(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        // just a stub
        return new Detections();
    }

    public List<Recognition> onDetections(Image image, int rotation, List<Recognition> results){
        return results;
    }
}

package ie.tcd.netlab.objecttracker.detectors;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.Image;
import android.util.Size;
import android.content.Context;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import ie.tcd.netlab.objecttracker.helpers.Recognition;
import ie.tcd.netlab.objecttracker.helpers.Transform;
import ie.tcd.netlab.objecttracker.testing.Logger;

/** An object detector that uses TF and a YOLO model to detect objects. */
public class DetectorYoloTiny extends Detector {

    private static final String YOLO_MODEL_FILE = "file:///android_asset/graph-tiny-yolo-voc.pb";
    private static final int YOLO_INPUT_SIZE = 352;
    private static final String YOLO_INPUT_NAME = "input";
    private static final String YOLO_OUTPUT_NAMES = "output";

    private static final float MINIMUM_CONFIDENCE_YOLO = 0.25f;
    private static final int YOLO_BLOCK_SIZE = 32;
    private static final int MAX_RESULTS = 5;
    private static final int NUM_CLASSES = 20;
    private static final int NUM_BOXES_PER_BLOCK = 5;

    private static final double[] ANCHORS = {
            1.08, 1.19,
            3.42, 4.41,
            6.63, 11.38,
            9.42, 5.11,
            16.62, 10.52
    };

    private static final String[] LABELS = {
            "aeroplane",
            "bicycle",
            "bird",
            "boat",
            "bottle",
            "bus",
            "car",
            "cat",
            "chair",
            "cow",
            "diningtable",
            "dog",
            "horse",
            "motorbike",
            "person",
            "pottedplant",
            "sheep",
            "sofa",
            "train",
            "tvmonitor"
    };

    // Config values.
    private final String inputName;
    private final String[] outputNames;
    private final int inputSize;
    private final int blockSize;
    private final TensorFlowInferenceInterface inferenceInterface;

    /** Initializes a native TensorFlow session for classifying images. */
    public DetectorYoloTiny(final Context context) {
        this.inputName = YOLO_INPUT_NAME;
        this.outputNames = YOLO_OUTPUT_NAMES.split(",");
        this.blockSize = YOLO_BLOCK_SIZE;
        this.inputSize = YOLO_INPUT_SIZE; // size of yolo network input
        this.inferenceInterface = new TensorFlowInferenceInterface(context.getAssets(), YOLO_MODEL_FILE);
    }

    public Detections recognizeImage(Image image, int rotation) {

        Detections detects = new Detections();
        List<Recognition> recognitions = new ArrayList<>();
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            // unsupported image format
            return detects;
        }

        Logger.tick("toRGB");
        // convert from YUV to RGB bitmap
        Bitmap rgbFrameBitmap = Transform.convertYUVtoRGB(image);
        Logger.add(" toRGB: "+Logger.tock("toRGB"));

        Logger.tick("rotate");
        // rotate image to align with camera view, and scale to yolo input size
        int image_w = image.getWidth(), image_h = image.getHeight(); // size of input image
        Matrix frameToCropTransform = Transform.getTransformationMatrix(
                        image_w, image_h,
                        this.inputSize, this.inputSize,
                        rotation, false);
        Matrix cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
        Bitmap bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        Logger.add(" rotate/crop: "+Logger.tock("rotate"));

        Logger.tick("yoloTF");
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        int[] intValues = new int[this.inputSize * this.inputSize];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(),
                bitmap.getHeight());

        float[] floatValues = new float[this.inputSize * this.inputSize * 3];
        for (int i = 0; i < intValues.length; ++i) {
            floatValues[i * 3] = ((intValues[i] >> 16) & 0xFF) / 255.0f;
            floatValues[i * 3 + 1] = ((intValues[i] >> 8) & 0xFF) / 255.0f;
            floatValues[i * 3 + 2] = (intValues[i] & 0xFF) / 255.0f;
        }

        // Copy the input data into TensorFlow.
        inferenceInterface.feed(inputName, floatValues, 1, inputSize, inputSize, 3);

        // Run the inference call.
        inferenceInterface.run(outputNames, false);

        // Copy the output Tensor back into the output array.
        final int gridWidth = bitmap.getWidth() / blockSize;
        final int gridHeight = bitmap.getHeight() / blockSize;
        final float[] output =
                new float[gridWidth * gridHeight * (NUM_CLASSES + 5) * NUM_BOXES_PER_BLOCK];
        inferenceInterface.fetch(outputNames[0], output);

        // Find the best detections.
        final PriorityQueue<Recognition> pq =
                new PriorityQueue<>(
                        1,
                        new Comparator<Recognition>() {
                            @Override
                            public int compare(final Recognition lhs, final Recognition rhs) {
                                // Intentionally reversed to put high confidence at the head of the queue.
                                return Float.compare(rhs.confidence, lhs.confidence);
                            }
                        });

        for (int y = 0; y < gridHeight; ++y) {
            for (int x = 0; x < gridWidth; ++x) {
                for (int b = 0; b < NUM_BOXES_PER_BLOCK; ++b) {
                    final int offset =
                            (gridWidth * (NUM_BOXES_PER_BLOCK * (NUM_CLASSES + 5))) * y
                                    + (NUM_BOXES_PER_BLOCK * (NUM_CLASSES + 5)) * x
                                    + (NUM_CLASSES + 5) * b;

                    final float xPos = (x + expit(output[offset])) * blockSize;
                    final float yPos = (y + expit(output[offset + 1])) * blockSize;

                    final float w = (float) (Math.exp(output[offset + 2]) * ANCHORS[2 * b]) * blockSize;
                    final float h = (float) (Math.exp(output[offset + 3]) * ANCHORS[2 * b + 1]) * blockSize;

                    final RectF rect =
                            new RectF(Math.max(0, xPos - w / 2), Math.max(0, yPos - h / 2),
                                    Math.min(bitmap.getWidth() - 1, xPos + w / 2),
                                    Math.min(bitmap.getHeight() - 1, yPos + h / 2));

                    cropToFrameTransform.mapRect(rect); // map boxes back to original image coords
                    final float confidence = expit(output[offset + 4]);

                    int detectedClass = -1;
                    float maxClass = 0;

                    final float[] classes = new float[NUM_CLASSES];
                    for (int c = 0; c < NUM_CLASSES; ++c) {
                        classes[c] = output[offset + 5 + c];
                    }
                    softmax(classes);

                    for (int c = 0; c < NUM_CLASSES; ++c) {
                        if (classes[c] > maxClass) {
                            detectedClass = c;
                            maxClass = classes[c];
                        }
                    }

                    final float confidenceInClass = maxClass * confidence;
                    if (confidenceInClass > MINIMUM_CONFIDENCE_YOLO) {
                        pq.add(new Recognition(LABELS[detectedClass], confidenceInClass, rect, new Size(image_w,image_h)));
                    }
                }
            }
        }

        for (int i = 0; i < Math.min(pq.size(), MAX_RESULTS); ++i) {
            recognitions.add(pq.poll());
        }
        Logger.add(" yoloTF: "+Logger.tock("yoloTF"));

        detects.results=recognitions;
        return detects;
    }

    private float expit(final float x) {
        return (float) (1. / (1. + Math.exp(-x)));
    }

    private void softmax(final float[] vals) {
        float max = Float.NEGATIVE_INFINITY;
        for (final float val : vals) {
            max = Math.max(max, val);
        }
        float sum = 0.0f;
        for (int i = 0; i < vals.length; ++i) {
            vals[i] = (float) Math.exp(vals[i] - max);
            sum += vals[i];
        }
        for (int i = 0; i < vals.length; ++i) {
            vals[i] = vals[i] / sum;
        }
    }
}


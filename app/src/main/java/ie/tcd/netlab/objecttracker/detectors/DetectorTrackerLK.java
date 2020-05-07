package ie.tcd.netlab.objecttracker.detectors;

import android.graphics.ImageFormat;
import android.graphics.RectF;
import android.util.Pair;
import android.util.Size;
import android.media.Image;
import android.media.Image.Plane;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import ie.tcd.netlab.objecttracker.helpers.Recognition;
import ie.tcd.netlab.objecttracker.tracking.ObjectTracker;

public class DetectorTrackerLK extends Detector {
    // Pyramidal Lucas Kanade object tracker (uses opencv to do the hard work!)

    // Maximum percentage of a box that can be overlapped by another box at detection time. Otherwise
    // the lower scored box (new or old) will be removed.
    private static final float MAX_OVERLAP = 0.2f;
    private static final float MIN_SIZE = 16.0f;
    // Allow replacement of the tracked box with new results if
    // correlation has dropped below this level.
    private static final float MARGINAL_CORRELATION = 0.75f;
    // Consider object to be lost if correlation falls below this threshold.
    private static final float MIN_CORRELATION = 0.3f;

    private long currTimestamp = 0; // counts number of calls

    private ObjectTracker objectTracker;
    private final List<Pair<Float, RectF>> screenRects = new LinkedList<>();

    private static class TrackedRecognition {
        ObjectTracker.TrackedObject trackedObject;
        RectF location;
        float detectionConfidence;
        String title;
    }

    private final List<TrackedRecognition> trackedObjects = new LinkedList<>();
    private boolean initialized = false;

    public DetectorTrackerLK() { }

    public Detections recognizeImage(Image image, int rotation) {
        // called when have a new frame to process
        Detections detects = new Detections();
        List<Recognition> results = new ArrayList<>();

        if (image.getFormat() != ImageFormat.YUV_420_888) {
            // wrong image format
            return detects;
        }

        // TO DO: need to rotate image, and also reset tracking when
        // rotation causes size of tracker to change
        int w = image.getWidth(), h=image.getHeight();
        ++currTimestamp;
        if (objectTracker == null) {
            if (!initialized) {
                ObjectTracker.clearInstance();

                objectTracker = ObjectTracker.getInstance(w, h,
                        getRowStride(image), true);
                initialized = true;

                if (objectTracker == null) {
                    System.out.println("Object tracking support not found. ");
                    return detects;
                }
            } else {
                return detects;
            }
        }

        objectTracker.nextFrame(getLuminance(image), null,
                currTimestamp, null, true);

        // Clean up any objects not worth tracking any more.
        final LinkedList<TrackedRecognition> copyList = new LinkedList<>(trackedObjects);
        for (final TrackedRecognition recognition : copyList) {
            final ObjectTracker.TrackedObject trackedObject = recognition.trackedObject;
            final float correlation = trackedObject.getCurrentCorrelation();
            if (correlation < MIN_CORRELATION) {
                trackedObjects.remove(recognition);
                //DetectorActivity.trackingFailure = true;
            }
        }

        for (TrackedRecognition recognition : trackedObjects) {
            results.add(new Recognition(recognition.title, recognition.detectionConfidence,
                    recognition.location, new Size(w,h)));
        }
        detects.results=results;
        return detects;
    }

    @Override
    public List<Recognition> onDetections(Image image, int rotation, List<Recognition> results) {
        // called when have new detections to process e.g. after call to yolo
        List<Recognition> updated_results = new ArrayList<>();

        if (image.getFormat() != ImageFormat.YUV_420_888) {
            // wrong image format
            return updated_results;
        }

        // TO DO: need to rotate image, and also reset tracking when
        // rotation causes size of tracker to change

        final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<>();
        screenRects.clear();
        for (final Recognition result : results) {
            if (result.getLocation() == null) {
                continue;
            }
            final RectF detectionFrameRect = new RectF(result.getLocation());
            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                continue;
            }
            screenRects.add(new Pair<>(result.getConfidence(), detectionFrameRect));
            rectsToTrack.add(new Pair<>(result.getConfidence(), result));
        }

        if (rectsToTrack.isEmpty()) {
            return updated_results;
        }

        if (objectTracker == null) {
            // first call to detector
            trackedObjects.clear();
            for (final Pair<Float, Recognition> potential : rectsToTrack) {
                final TrackedRecognition trackedRecognition = new TrackedRecognition();
                trackedRecognition.detectionConfidence = potential.first;
                trackedRecognition.location = new RectF(potential.second.getLocation());
                trackedRecognition.trackedObject = null;
                trackedRecognition.title = potential.second.getTitle();
                trackedObjects.add(trackedRecognition);
            }
        } else {
            for (final Pair<Float, Recognition> potential : rectsToTrack) {
                handleDetection(getLuminance(image), potential);
            }
        }

        for (TrackedRecognition recognition : trackedObjects) {
            updated_results.add(new Recognition(recognition.title, recognition.detectionConfidence,
                    recognition.location, new Size(image.getWidth(),image.getHeight())));
        }
        return updated_results;
    }

    private byte[] getLuminance(Image image) {
        final Plane[] planes = image.getPlanes();
        byte[][] yuvBytes = new byte[3][];
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
        int yRowStride = planes[0].getRowStride();
        final int uvRowStride = planes[1].getRowStride();
        final int uvPixelStride = planes[1].getPixelStride();
        return yuvBytes[0];
    }

    private int getRowStride(Image image) {
        return image.getPlanes()[0].getRowStride();
    }

    private void handleDetection(final byte[] frameCopy, final Pair<Float, Recognition> potential) {

        final ObjectTracker.TrackedObject potentialObject =
                objectTracker.trackObject(potential.second.getLocation(), currTimestamp, frameCopy);

        final float potentialCorrelation = potentialObject.getCurrentCorrelation();

        if (potentialCorrelation < MARGINAL_CORRELATION) {
            potentialObject.stopTracking();
            return;
        }

        final List<TrackedRecognition> removeList = new LinkedList<>();

        float maxIntersect = 0.0f;

        // This is the current tracked object whose color we will take. If left null we'll take the
        // first one from the color queue.
        TrackedRecognition recogToReplace = null;

        // Look for intersections that will be overridden by this object or an intersection that would
        // prevent this one from being placed.
        for (final TrackedRecognition trackedRecognition : trackedObjects) {
            final RectF a = trackedRecognition.trackedObject.getTrackedPositionInPreviewFrame();
            final RectF b = potentialObject.getTrackedPositionInPreviewFrame();
            final RectF intersection = new RectF();
            final boolean intersects = intersection.setIntersect(a, b);

            final float intersectArea = intersection.width() * intersection.height();
            final float totalArea = a.width() * a.height() + b.width() * b.height() - intersectArea;
            final float intersectOverUnion = intersectArea / totalArea;

            // If there is an intersection with this currently tracked box above the maximum overlap
            // percentage allowed, either the new recognition needs to be dismissed or the old
            // recognition needs to be removed and possibly replaced with the new one.
            if (intersects && intersectOverUnion > MAX_OVERLAP) {
                if (potential.first < trackedRecognition.detectionConfidence
                        && trackedRecognition.trackedObject.getCurrentCorrelation() > MARGINAL_CORRELATION) {
                    // If track for the existing object is still going strong and the detection score was
                    // good, reject this new object.
                    potentialObject.stopTracking();
                    return;
                } else {
                    removeList.add(trackedRecognition);

                    // Let the previously tracked object with max intersection amount donate its color to
                    // the new object.
                    if (intersectOverUnion > maxIntersect) {
                        maxIntersect = intersectOverUnion;
                        recogToReplace = trackedRecognition;
                    }
                }
            }
        }

        // If we're already tracking the max object and no intersections were found to bump off,
        // pick the worst current tracked object to remove, if it's also worse than this candidate
        // object.
        if (removeList.isEmpty()) {
            for (final TrackedRecognition candidate : trackedObjects) {
                if (candidate.detectionConfidence < potential.first) {
                    if (recogToReplace == null
                            || candidate.detectionConfidence < recogToReplace.detectionConfidence) {
                        // Save it so that we use this color for the new object.
                        recogToReplace = candidate;
                    }
                }
            }
            if (recogToReplace != null) {
                removeList.add(recogToReplace);
            }
        }

        // Remove everything that got intersected.
        for (final TrackedRecognition trackedRecognition : removeList) {
            trackedRecognition.trackedObject.stopTracking();
            trackedObjects.remove(trackedRecognition);
        }

        if (recogToReplace == null) {
            potentialObject.stopTracking();
            return;
        }

        // Finally safe to say we can track this object.
        final TrackedRecognition trackedRecognition = new TrackedRecognition();
        trackedRecognition.detectionConfidence = potential.first;
        trackedRecognition.trackedObject = potentialObject;
        trackedRecognition.title = potential.second.getTitle();
        trackedObjects.add(trackedRecognition);
    }
}
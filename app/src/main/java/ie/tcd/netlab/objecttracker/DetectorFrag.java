package ie.tcd.netlab.objecttracker;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.support.annotation.NonNull;
import android.util.Size;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.content.Context;
import android.os.Process;

import java.util.Locale;

import ie.tcd.netlab.objecttracker.detectors.Detector;
import ie.tcd.netlab.objecttracker.detectors.Detections;
import ie.tcd.netlab.objecttracker.detectors.DetectorTrackerLK;
import ie.tcd.netlab.objecttracker.detectors.DetectorYoloHTTP;
import ie.tcd.netlab.objecttracker.detectors.DetectorYoloTiny;
import ie.tcd.netlab.objecttracker.helpers.CameraPlusOverlayFrag;
import ie.tcd.netlab.objecttracker.helpers.Recognition;
import ie.tcd.netlab.objecttracker.testing.Logger;
import ie.tcd.netlab.objecttracker.testing.ConfigViaSocket;

// TODO:
// test object tracking (ported from tensorflow.  code compiles but not yet run)

public class DetectorFrag extends CameraPlusOverlayFrag {
    // Implements the main user interface, displaying the camera output plus overlayed boxes
    // identifying objects in the image

    private static final String DEFAULT_DETECTOR = "TinyYoloTF";
    //https://developer.android.com/reference/android/os/Process.html -  from -20 for highest scheduling priority to 19 for lowest scheduling priority. 10 is default for background
    private static final String DEFAULT_NEURAL_PRIORITY="10";
    private static final String DEFAULT_TRACKER = "TrackingOff";
    private static final Size DEFAULT_PREVIEW_SIZE = new Size(640, 480);
    private static final String DEFAULT_JPEG_QUALITY = "50";
    private static final String DEFAULT_SERVER="lily.scss.tcd.ie:60007";
    // NB: edit res/values/strings.xml to change server settings list
    private static final boolean DEFAULT_USEUDP=false;
    private static final boolean DEFAULT_CONFIGSOCKET=false;
    private static final boolean DEBUGGING = false;  // generate extra debug output ?


    private Detector detector;
    private Detector tracker;
    private Detections detects = new Detections(); // list of boxes to display over image, updated asynchronously by detector
    private boolean computingDetection = false; // flags when detector processing is underway
    private Long prev_time;
    private float avg_camera_interframe_time=0f;
    private float avg_detection_time=0f;
    private final Size camera_preview_size = DEFAULT_PREVIEW_SIZE;
    private ConfigViaSocket configViaSocket = null;
    private static int neural_priority;
    private Context context=null;
    private View view;
    SharedPreferences sharedPref;

    /******************************************************************************/
    // event handlers
    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        this.view = view;
        context = view.getContext();
        Debug.println("onViewCreated");
        sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
        sharedPref.registerOnSharedPreferenceChangeListener(configListener);
        // onResume event will now be triggered
        // see https://developer.android.com/guide/components/activities/activity-lifecycle
    }

    private OnSharedPreferenceChangeListener configListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            Debug.println("onSharedPreferenceChanged()");
            resumeDetectorFrag();
        }
    };

    @Override
    public void onDestroyView() {
        Debug.println("onDestroyView()");
        context = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        // called on startup and when UI wakes up after a pause, which includes after a change to the settings screen and
        // then back to main activity screen (which is why we read the preferences here, in case they have
        // changed). This might also be called when screen wakes up after it has been asleep.
        Debug.println("onResume()");
        super.onResume();
        resumeDetectorFrag();
    }

    public void resumeDetectorFrag() {
        // setup the detector and camera (we choose the camera settings to match the detector chosen).
        String detector_choice = sharedPref.getString("detector_mode", DEFAULT_DETECTOR);
        neural_priority =  Integer.valueOf(sharedPref.getString("neural_priority", DEFAULT_NEURAL_PRIORITY));
        String tracker_choice = sharedPref.getString("tracker_mode", DEFAULT_TRACKER);
        String yoloServer = sharedPref.getString("server", DEFAULT_SERVER);
        int jpegQuality = Integer.valueOf(sharedPref.getString("jpeg_quality", DEFAULT_JPEG_QUALITY));
        boolean useUDP = sharedPref.getBoolean("udp", DEFAULT_USEUDP);
        boolean useSOCKET = sharedPref.getBoolean("config_socket", DEFAULT_CONFIGSOCKET);
        boolean useCamera = sharedPref.getBoolean("use_camera", true);

        switch (detector_choice) {
            case "YoloHTTP":
                detector = new DetectorYoloHTTP(context,yoloServer,jpegQuality,useUDP);
                break;
            case "TinyYoloTF":
                detector = new DetectorYoloTiny(context);
                break;
            default:
                Logger.addln("\nWARN Invalid choice of detector");
            case "DetectorOff":
                detector = null;
        }

        switch (tracker_choice) {
            case "TrackingOff":
                tracker = null;
                break;
            case "LukasKanade":
                tracker = new DetectorTrackerLK();
                break;
            default:
                tracker = null;
                Logger.addln("\nWARN Invalid choice of tracker");
        }

        if (!useSOCKET && configViaSocket != null) {
            configViaSocket.onDestroy(); configViaSocket = null;
        } else if (useSOCKET  && configViaSocket == null) {
            configViaSocket = new ConfigViaSocket(getActivity(), new ConfigViaSocketRecognise());
        }

        // initialise the camera if not already done.  NB we need view to exist to allow overlay to be configured
        Debug.println("getCameraFrag: "+getCameraFrag());
        if (getCameraFrag()==null) {
            Debug.println("calling createCameraFrag()");
            createCameraFrag(view, camera_preview_size, ImageFormat.YUV_420_888);
        }

        Debug.println("useCamera: "+useCamera+" cameraInUse: "+getCameraFrag().cameraEnabled());
        if ((!useCamera) && getCameraFrag().cameraEnabled()) {
            Debug.println("disableCamera()");
            getCameraFrag().disableCamera();
        } else if ((useCamera) && !getCameraFrag().cameraEnabled()) {
            Debug.println("enableCamera()");
            getCameraFrag().enableCamera();
        }

        if (detects!=null) detects.results.clear(); // reset existing list of object detections

        // update screen info
        if (getView()!=null) {
            String configViaSocketIP="";
            if (useSOCKET) configViaSocketIP="\n"+configViaSocket.getIpAddress() + ":" + configViaSocket.getPort();
            TextView legend = getView().findViewById(R.id.legend);
            legend.setText(String.format(Locale.ENGLISH,
                    "%s (%s)\n%s\nJPG Quality:%s%%%s", detector_choice, neural_priority,
                    tracker_choice, jpegQuality,configViaSocketIP));
        }
    }

    @Override
    public void processImage(final Image image) {
        // we have a new image from camera ...

        // measure the time between calls to this routine (the raw camera frame
        // rate supported by our setup)
        if (prev_time != null) {
            //Logger.addln(" f: " + Long.toString(Logger.now() - prev_time)+" ms");// stop timer (started below at end of processImage())
            avg_camera_interframe_time = (1-0.2f)*avg_camera_interframe_time + 0.2f*(Logger.now() - prev_time);
        }

        if (computingDetection) { //still busy with previous image
            if (tracker != null) {
                Logger.tick("tracker");
                detects = tracker.recognizeImage(image, getCameraFrag().getImageToViewRotation());
                Logger.add(" t: "+Logger.tock("tracker"));
                refreshOverlay(); // update overlay
            }
            image.close();
            prev_time = Logger.now(); // restart fps timer
            return;
        }

        // carry out detection in a background thread so that don't block here
        Logger.add("cam: "+(int)avg_camera_interframe_time+" ");
        computingDetection = true;
        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(neural_priority);

                        long starttime = System.currentTimeMillis();
                        if (detector != null) {
                            detects = detector.recognizeImage(image,getCameraFrag().getImageToViewRotation());
                            Logger.add(detects.client_timings.toString());
                        }
                        if (tracker != null) {
                            Logger.tick("tracker2");
                            detects.results = tracker.onDetections(image, getCameraFrag().getImageToViewRotation(),
                                    detects.results);
                            Logger.add(" t2: "+Logger.tock("tracker2"));
                        }
                        if (detector != null || tracker != null)
                            Logger.addln(" ms");
                        image.close(); // release image
                        computingDetection = false; // tell app that we can do another detection now
                        refreshOverlay(); // update overlay
                        long finishtime=System.currentTimeMillis();
                        if ((detects!=null) && (detects.results!=null) && (detects.results.size()>0)) { // ignore yoloHTTP responses where server is busy and has reset connection
                            avg_detection_time = (1 - 0.2f) * avg_detection_time + 0.2f * (finishtime - starttime);
                        }
                    }
                });
        prev_time = Logger.now(); // restart fps timer
    }

    // used for testing
    class ConfigViaSocketRecognise implements ConfigViaSocket.RecogniseByteArray {
        public Detections recognise(final byte[] yuv, int w, int h, int rotation, Bitmap b) {
            // take YUV byte array as input for processing.  used for testing.
            //return detector.recognize(yuv, w, h, rotation, b);
            //return detector.frameworkMaxAreaRectangle(yuv, w, h, rotation, b);
            //return detector.frameworkMaxAreaRectBD(yuv, w, h, rotation, b);
            //return detector.frameworkQuadrant(yuv, w, h, rotation, b);
            //return detector.nineWrapper(yuv, w, h, rotation, b);
            return detector.nineWrapper(yuv, w, h, rotation, b);
        }
    }

    @Override
    public void updateOverlay(final Canvas canvas) {
        // update detection boxes overlayed on camera image

        if ((detects==null)||(detects.results==null) || (detects.results.size()==0)) return; // nothing to display

        // set colour etc of overlay boxes
        Paint boxPaint = new Paint();
        boxPaint.setColor(Color.RED); boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5.0f); boxPaint.setStrokeMiter(100);
        // boxPaint.setStrokeCap(Paint.Cap.ROUND); boxPaint.setStrokeJoin(Paint.Join.ROUND);

        // setup format of text labels for overlay boxes
        Paint textPaint = new Paint();
        float textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 18.0f, getResources().getDisplayMetrics());
        textPaint.setTextSize(textSizePx); textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL); textPaint.setAntiAlias(false);
        textPaint.setAlpha(255);

        // get mapping from image dimensions to display dimensions
        Size image_size = detects.results.get(0).image_size;
        Matrix frameToCanvasMatrix = getCameraFrag().getFrameToScreen(image_size);

        // now iterate through detection results and draw a labelled box for each
        for (final Recognition result : detects.results) {
            // position and size of box enclosing detected object
            // NB: we must take a copy here since call to frameToCanvasMatrix.mapRect()
            // below will overwrite it !
            RectF location = new RectF(result.location);

            // canvas size need not be the same as the size of the preview image passed
            // to processImage(), so need to rescale boxes as needed here
            frameToCanvasMatrix.mapRect(location);


            // draw box
            final float cornerSize = Math.min(location.width(), location.height()) / 8.0f;
            //canvas.drawRoundRect(location, cornerSize, cornerSize, boxPaint);
            canvas.drawRect(location,boxPaint);


            // display label, rotated to stay aligned with handset screen
            canvas.save();
            canvas.rotate((float)-getCameraFrag().getViewOrientation(),location.left + cornerSize, location.bottom);
            canvas.drawText(String.format(Locale.ENGLISH,
                    "%s %.2f", result.title, result.confidence),
                    location.left + cornerSize, location.bottom, textPaint);
            canvas.restore();
        }

        // update screen info
        if (getView()!=null) {
            TextView legend = getView().findViewById(R.id.stats);
            legend.setText(String.format(Locale.ENGLISH,
                    "%.1f fps(%.0f ms)",
                    1000.0f / (1 + avg_detection_time), avg_detection_time));
            legend.setTextColor(Color.WHITE);
        }
    }

    /***************************************************************************************/
    // debugging
    private static class Debug {
        static void println(String s) {
            if (DEBUGGING) System.out.println("DetectorFrag: "+s);
        }
    }
}

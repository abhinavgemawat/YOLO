package ie.tcd.netlab.objecttracker.helpers;

import android.content.Context;
import android.graphics.Canvas;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/*
// calls to obtain camera characteristics ..
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import java.lang.Integer;*/

import ie.tcd.netlab.objecttracker.R;
import ie.tcd.netlab.objecttracker.helpers.CameraFragment;
import ie.tcd.netlab.objecttracker.helpers.OverlayView;
import ie.tcd.netlab.objecttracker.testing.Logger;

public class CameraPlusOverlayFrag extends Fragment {

    // CameraFragment opens camera and displays output in
    // "container" within the activity_main.xml view in UI.  It also provides callbacks
    // whenever the size of the image changes, when the UI is first created and when
    // a new image is available from the camera.

    // CameraPlusOverlay extends this by displaying an overlay pane on top of the
    // camera image.  It adds a callback to redraw this overlay and a call to refresh
    // the overlay.  It also provides a thread that can be used to execute longer
    // overlay updates.

    private CameraFragment camera_frag=null; // object displaying camera output and overlay on phone UI
    private OverlayView overlay=null;
    private HandlerThread handlerThread=null; // background thread for running detections ..
    private Handler handler=null;
    private static final int XML_CONTAINER = R.id.container; // id of UI object in activity_main within which to display camera output plus overlay
    private static final int XML_OVERLAY_VIEW = R.id.tracking_overlay; // id of interface component used to display overlay
    private static final int XML_CAMERA_FRAG = R.layout.camera_fragment; // bundled camera plus overlay UI objects

    /******************************************************************************/
    // event handlers
    @Override
    public View onCreateView(
            @NonNull  final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        return inflater.inflate(R.layout.main_fragment, container, false);
    }

    // must override this to call createCamera() with appropriate parameters
    //public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {}

    @Override
    public void onPause() {
        // called when screen goes to sleep
        onPauseHook();
        stop_thread();
        super.onPause();
    }

    /******************************************************************************/
    // callbacks ...

    public void onPauseHook(){}
    public void updateOverlay(final Canvas canvas){}
    public void processImage(Image image){}

    /******************************************************************************/
    // actions
    public void refreshOverlay() {
        // force overlay to be redrawn
        overlay.postInvalidate();
    }

    public CameraFragment getCameraFrag() {return camera_frag;} // the camera fragment

    public void createCameraFrag(@NonNull final View view, Size desiredPreviewSize, int imageFormat) {
        // select camera
        final CameraManager manager = (CameraManager) view.getContext().getSystemService(Context.CAMERA_SERVICE);
        if (manager==null) {
            Logger.addln("createCameraFrag(): couldn't get camera manager!");
            return;
        }
        try {
            String cameraId = manager.getCameraIdList()[0];
            // create UI interface (called a fragment) showing camera output
            camera_frag = new CameraFragment(
                    new CameraFragment.ConnectionCallback() {
                        @Override
                        // onViewCreated() is called when camera UI is created
                        public void onViewCreated() {
                            // overlayView is the overlay on top of the camera image, its not
                            // available until UI has been created, so we use this callback to initialise it
                            overlay = view.findViewById(XML_OVERLAY_VIEW);
                            overlay.addCallback(
                                    new OverlayView.DrawCallback() {
                                        @Override
                                        // when overlay display is updated e.g. by a call
                                        // to overlay.postInvalidate() then drawCallback()
                                        // is executed to redraw any boxes
                                        public void drawCallback(final Canvas canvas) {
                                            updateOverlay(canvas);
                                        }
                                    });
                        }

                        @Override
                        // handleImage() is called when there is a new image from the camera
                        public void handleImage(Image image) {
                            processImage(image);
                        }

                        @Override
                        public void onSurfaceTextureDestroyed() {
                            camera_frag = null; // release the CameraFragment, we will create another when needed
                        }
                    },
                    XML_CAMERA_FRAG, // UI location to display camera preview
                    cameraId,        // Default to using the back camera
                    desiredPreviewSize,
                    imageFormat);

            // set where camera image is displayed in UI (i.e. in "container" of main_activity)
            if (getFragmentManager()!=null) {
                getFragmentManager()
                        .beginTransaction()
                        .replace(XML_CONTAINER, camera_frag)
                        .commit();
            } else {
                Logger.addln("getFragmentManager(): couldn't get fragment manager!");
            }
        } catch (CameraAccessException e) {
            //e.printStackTrace();
            Logger.addln("\nWARN Camera Access Exception: "+e.getMessage());
        }

    }


        /*
        // calls to obtain camera characteristics ..
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
            try {
        cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

       CameraCharacteristics c=null;
        try {
            c = manager.getCameraCharacteristics(cameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Range<Integer>[] res = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        System.out.println("camera :"); System.out.print(res.length);
        for (Range<Integer> r : res) {
            System.out.print("["+r.getLower()+" "+r.getUpper()+"]");
        }
        System.out.println();
        StreamConfigurationMap configs = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int[] fmts = configs.getOutputFormats();
        for (int fmt: fmts) {
            System.out.print(Integer.toHexString(fmt)+" ");
        }
        System.out.println();
       */
    /******************************************************************************/

    // provide a separate thread (useful for yolo detections etc that take a while)
    private synchronized void start_thread() {
        handlerThread = new HandlerThread("overlay_helper");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private synchronized void stop_thread() {
        if (handlerThread!=null) {
            handlerThread.quitSafely();
            handlerThread=null;
        }
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handlerThread==null || handlerThread.getState()==null || handlerThread.getState()== Thread.State.TERMINATED) {
            start_thread();
        }
        if (handler != null) {
            handler.post(r);
        }
    }
}

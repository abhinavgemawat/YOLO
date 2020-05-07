package ie.tcd.netlab.objecttracker.helpers;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment ;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.OrientationEventListener;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import ie.tcd.netlab.objecttracker.R;
import ie.tcd.netlab.objecttracker.helpers.AutoFitTextureView;
import ie.tcd.netlab.objecttracker.testing.Logger;

@SuppressLint("ValidFragment")
public class CameraFragment extends Fragment {

    // CameraFragment opens camera and displays output in
    // "texture" within the camera_fragment.xml view in UI.  It also provides callbacks
    // whenever the size of the image changes, when the UI is first created and when
    // a new image is available from the camera.

    // sequence of events on startup is quite complicated:
    // 1. onViewCreated().  points textureview to UI view and calls cameraFragCallbacks.onViewCreated()
    // 2. onResume(). links surfaceTextureListener() with textureview. starts thread.
    // if textureview not available yet a this point, then on event:
    // onSurfaceTextureAvailable. surfaceTextureListener() will call openCamera()
    // if textureview was available then onResume() calls openCamera()
    // call to openCamera() will eventually trigger event:
    // 3. onOpened() (camera opened).  needs textureview to exist.  it calls createCameraPreviewSession()
    //    which directs camera output to textureview and also to an imagereader.  the textureview is
    //    displayed on the screen, the imagereader grabs frames for processing.

    private static final int MAX_IMAGES = 32; // size of camera image buffer
    private static final int XML_TEXTUREVIEW = R.id.texture; // id of interface component used to display camera output
    private static final boolean DEBUGGING = false;  // generate extra debug output ?

    private Size previewSize;           // camera image size
    private int viewOrientation;        // handset orientation
    private int imageToViewRotation;    // rotation needed to align image with viewing angle
    private int viewToImageRotation;
    private final int image_format;     // image format to be used
    private final Size inputSize;       // desired camera image size e.g to match with DNN input size

    private final String cameraId;
    private final int layout;
    private AutoFitTextureView textureView=null;
    private CameraCaptureSession captureSession=null;
    private CameraDevice cameraDevice=null;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private ImageReader previewReader=null;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private boolean openingCamera=false;
    private boolean enabledCamera=false;
    private Context context;

    /***************************************************************************************/
    // Events
    @SuppressLint("ValidFragment")
    // create new fragment
    public CameraFragment(
            final ConnectionCallback callback,
            final int layout,
            final String camera_id,
            final Size inputSize,
            final int image_format) {
        this.cameraFragCallbacks = callback;
        this.layout = layout;
        this.inputSize = inputSize;
        this.image_format = image_format;
        this.cameraId = camera_id;
    }

    @Override
    public void onResume() {
        // called on startup and when UI wakes up after a pause
        Debug.println("onResume()");
        super.onResume();
        resumeCamera();
    }

    public void enableCamera() {
        enabledCamera=true;
        if (textureView!=null) resumeCamera();
        // if textureview is null then view hasn't been created yet, we're too early in
        // camera start up process and can't call resumeCamera just yet.  but
        // onResume() will be called automatically after onViewCreated() fires
    }

    public void disableCamera() {
        enabledCamera=false;
        pauseCamera();
    }

    public boolean cameraEnabled() {
        return enabledCamera;
    }

    public void resumeCamera() {
        // openingCamera flags that if onSurfaceTextureAvailable() listener is now called then should open camera ...
        // NB: this flag is needed because onSurfaceTextureAvailable() listener can also be called
        // after onPause() event (not sure why, but it definitely happens), in which case we do
        // *not* want it to (re)open the camera.
        openingCamera = enabledCamera; //true;

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here.  Otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener.
        Debug.println("onResume() textureView available: "+textureView.isAvailable()+", enabledCamera: "+enabledCamera);
        if (textureView.isAvailable() && enabledCamera) {
            Debug.println("onResume() openCamera");
            openCamera();
        }
    }

    @Override
    public void onPause() {
        Debug.println("onPause()");
        pauseCamera();
        super.onPause();
    }

    public void pauseCamera() {
        closeCamera();
        openingCamera=false; // tell onSurfaceTextureAvailable() to not open the camera if/when called.
    }

    @Override
    // called when creation of UI display of camera image starts ...
    public View onCreateView(
            @NonNull final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(layout, container, false);
    }

    // called when creation of UI display of camera image is complete i.e. display becomes active
    // @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        context = view.getContext();
        textureView = view.findViewById(XML_TEXTUREVIEW);
        textureView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(final SurfaceTexture texture, final int width, final int height) {
                        // called:
                        // 1. after onResume() event (so also when CameraFragment object is first created)
                        // 2. after closeCamera() when the surface displaying the camera image is released from
                        // camera input stream (on closeCamera() we see an onSurfaceTextureDestroyed() event followed by
                        // an onSurfaceTextureAvailable() event), hence need for openingCamera
                        // check as otherwise we would undo the closeCamera() call.
                        Debug.println("onSurfaceTextureAvailable(): "+openingCamera);
                        if (openingCamera) openCamera();
                        // make callback e.g.for overlay setup
                        cameraFragCallbacks.onViewCreated();
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                        // make callback e.g.for overlay setup
                        Debug.println("onSurfaceTextureSizeChanged()");
                        cameraFragCallbacks.onViewCreated();
                    }

                    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                        //Debug.println("onSurfaceTextureUpdated()");
                    }

                    public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                        // called when:
                        // 1. UI view/surface showing camera image is no longer available
                        // i.e. when handset screen is now being used for something else e.g. user has swiped
                        // on UI (although swiping to a preference screen doesn't seem to cause a call here).
                        // 2. after the onClosed() camera event resulting from a call to closeCamera(), so
                        // below will result in a second call to closeCamera(), but that's ok as closeCamera()
                        // checks whether cameraDevice etc are null and so can be safely called multiple times.
                        // NB: Once onSurfaceTextureDestroyed() event has happened it seems that surfacetexture
                        // is gone for good i.e. it is not re-created on an onResume() event.  So camerafrag is
                        // useless after this and should also be destroyed.  We flag this via callback.
                        Debug.println("onSurfaceTextureDestroyed()");
                        closeCamera();
                        cameraFragCallbacks.onSurfaceTextureDestroyed();
                        return true;
                    }
                });
        // onResume event will now be triggered
    }

    /***************************************************************************************/
    // Callbacks
    public interface ConnectionCallback {
        void onViewCreated();
        void handleImage(Image image);
        void onSurfaceTextureDestroyed();
    }

    private final ConnectionCallback cameraFragCallbacks;

    /***************************************************************************************/
    // Sense Handset Orientation

    // Sense handset orientation, needed to map between preview images and handset display.
    // getWindowManager().getDefaultDisplay().getRotation() and getCameraOrientation() don't seem to work reliably,
    // at least not on my Huawei phone.
    private void initMeasureOrientation() {
        OrientationEventListener measureOrientation = new OrientationEventListener(getContext()) {
            public void onOrientationChanged(int orientation) {
                // orientation is 0 degrees when the device is oriented in its "natural position",
                // 90 degrees when its left side is at the top, 180 degrees when it is upside down,
                // and 270 degrees when its right side is to the top.
                // https://developer.android.com/reference/android/view/OrientationEventListener.
                // The following assumes "natural position" is landscape with left side at top

                if (orientation == ORIENTATION_UNKNOWN) { // when handset is flat
                    viewOrientation = 0;
                } else {
                    viewOrientation = orientation;
                }

                // snap to multiples of 90 degrees
                if (viewOrientation > 315 || viewOrientation < 45) {
                    viewOrientation = 0;
                    viewToImageRotation = 90; // portrait
                    imageToViewRotation = 90;
                } else if (viewOrientation > 45 && viewOrientation <= 135) {
                    viewOrientation = 90;
                    viewToImageRotation = 90; // landscape, rotated right
                    imageToViewRotation = 180;
                } else if (viewOrientation > 135 && viewOrientation <= 225) {
                    viewOrientation = 180;
                    viewToImageRotation = 90; // portrait, upside down
                    imageToViewRotation = -90;
                } else if (viewOrientation > 225 && viewOrientation <= 315) {
                    viewOrientation = -90;
                    viewToImageRotation = 90; // landscape, rotated left
                    imageToViewRotation = 0;
                }
                //Debug.println("orientation: "+orig+" "+viewOrientation+" "+imageToViewRotation);
            }
        };
        measureOrientation.enable(); // fire up the sensor now that the above callback has been setup
    }

    public int getImageToViewRotation() {
        return imageToViewRotation;
    }

    public int getViewToImageRotation() {
        return viewToImageRotation;
    }

    public int getViewOrientation() {
        return viewOrientation;
    }

    /***************************************************************************************/
    /*
     * Given sizes supported by the camera, choose the smallest one whose
     * width and height are at least as large as the minimum of both, or an exact match if possible.
     */
    private Size chooseClosestSize(@NonNull final CameraManager manager, String cameraId,
                                   Size desiredSize) {

        final CameraCharacteristics characteristics;
        try {
            characteristics = manager.getCameraCharacteristics(cameraId);
        } catch (final CameraAccessException e) {
            //e.printStackTrace();
            Logger.addln("\nWARN Camera Access Exception: "+e.getMessage());
            return null;
        }
        final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map==null) {
            Logger.addln("\nWARN chooseClosestSize((): couldn't get StreamConfigurationMap\n");
            return null;
        }
        Size[] choices = map.getOutputSizes(SurfaceTexture.class);
        final int width = desiredSize.getWidth();
        final int height = desiredSize.getHeight();
        final int minSize = Math.min(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            } else if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            }
        }

        if (exactSizeFound) {
            return desiredSize;
        } else if (bigEnough.size() > 0) { // Pick the smallest of those, assuming we found any
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    // helper for mapping from image co-ordinates to the screen ...
    public Matrix getFrameToScreen(Size image_size) {
        int rotation = getViewToImageRotation();
        boolean rotated = (rotation % 180 == 90) || (rotation % 180 == -90);
        int frameWidth = image_size.getWidth(), frameHeight = image_size.getHeight();
        int canvasWidth = textureView.getWidth();
        int canvasHeight = textureView.getHeight();
        //final float multiplier = canvas.getHeight() / (float) (rotated ? frameWidth : frameHeight);
        float multiplier =
                Math.min(canvasHeight / (float) (rotated ? frameWidth : frameHeight),
                        canvasWidth / (float) (rotated ? frameHeight : frameWidth));
        //Debug.println("mult: "+multiplier+" r: "+rotation+ " ow:"+canvasWidth+" oh:"
        //        +canvasHeight+" cw: "+" w: "+image_size.getWidth()+" h: "+image_size.getHeight());
        return Transform.getTransformationMatrix(
                frameWidth, frameHeight,
                (int) (multiplier * (rotated ? frameHeight : frameWidth)),
                (int) (multiplier * (rotated ? frameWidth : frameHeight)),
                rotation, false);
    }

    /***************************************************************************************/
    // Open/close camera

    public boolean cameraActive() {
        return cameraDevice != null && textureView != null && textureView.isAvailable();
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        Debug.println("openCamera(), called by: "+Thread.currentThread().getStackTrace()[4].getMethodName());

        if (cameraActive()) {
            // shouldn't happen
            Debug.println("openCamera(): camera is already active, returning");
            return;
        }
        // Assumes TextureView is already available
        if (!hasPermission()) {
            requestPermission();
            return; // will re-enter openCamera() via callback once we have permission
        }

        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager==null) {
            Logger.addln("\nWARN: openCamera() couldn't get CameraManager\n");
            return;
        }
        previewSize = chooseClosestSize(manager, cameraId, inputSize);
        if (previewSize==null) { //error
            return;
        }

        // We adjust the width and size of TextureView to the size of preview we picked,
        // taking account of configured (in Manifest.xml) orientation
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }

        initMeasureOrientation(); // fire up instrumentation to measure handset orientation

        // now try to take lock on camera ...
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                //throw new RuntimeException("Time out waiting to lock camera opening.");
                Logger.addln("\nWARN Time out waiting to lock camera opening");
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
            Debug.println("openCamera(): manager.openCamera() called");
        } catch (final CameraAccessException e) {
            //e.printStackTrace();
            Logger.addln("\nWARN Camera Access Exception: "+e.getMessage());
        } catch (final InterruptedException e) {
            //throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
            Logger.addln("\nWARN Interrupted while trying to lock camera opening");
        }
    }

    // called when CameraDevice changes its state.
    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull final CameraDevice cd) {
                    // Called when the camera is opened. We start camera preview here.
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    Debug.println("camera callback onOpened()");
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull final CameraDevice cd) {
                    Debug.println("camera callback onDisconnected()");
                    closeCamera();
                }

                @Override
                public void onError(@NonNull final CameraDevice cd, final int error) {
                    Debug.println("camera callback OnError()");
                    closeCamera();
                }

                @Override
                public void onClosed(@NonNull CameraDevice camera) {
                    Debug.println("camera callback onClosed()");
                    super.onClosed(camera);
                }
            };

    private void closeCamera() {
        Debug.println("closeCamera()");
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                try {
                    captureSession.stopRepeating();
                    captureSession.abortCaptures();
                    captureSession.close();
                    Debug.println("closeCamera(): close captureSession");
                } catch (Exception e) {
                    e.printStackTrace();
                    // do nothing, we're closing anyway
                }
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
                Debug.println("closeCamera(): close cameraDevice");
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
                Debug.println("closeCamera(): close previewReader");
            }
        } catch (final InterruptedException e) {
            //throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
            Logger.addln("\nWARN Interrupted while trying to lock camera closing.");
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /***************************************************************************************/
    // Setup image capture session for camera
    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(@NonNull final CameraCaptureSession session, @NonNull final CaptureRequest request,
                                                @NonNull final CaptureResult partialResult) {}

                @Override
                public void onCaptureCompleted(@NonNull final CameraCaptureSession session, @NonNull final CaptureRequest request,
                                               @NonNull final TotalCaptureResult result) {
                }
            };

    private void createCameraPreviewSession() {
        Debug.println("createCameraPreviewSession()");
        try {
            final SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            final Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // Create the reader for the preview frames.
            previewReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), image_format, MAX_IMAGES);

            previewReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) { Image image = reader.acquireLatestImage();
                                 if (image != null) {
                                    cameraFragCallbacks.handleImage(image);
                                 }
                        }
                    },
                    null);

            previewRequestBuilder.addTarget(previewReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull final CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest, captureCallback, null);
                            } catch (final CameraAccessException e) {
                                //e.printStackTrace();
                                Logger.addln("\nWARN Camera Access Exception: "+e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull final CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            //e.printStackTrace();
            Logger.addln("\nWARN Camera Access Exception: "+e.getMessage());
        }
    }

    /***************************************************************************************/
    // Permissions
    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showToast("Camera permission is required");
            }
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            showToast("Requested permission is required");
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode==1) { // call is from cameraFrag, see above.
                openCamera();
            }
        }
    }

    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }
    /***************************************************************************************/
    // debugging
    private static class Debug {
        static void println(String s) {
            if (DEBUGGING) System.out.println("CameraFragment: "+s);
        }
    }
}

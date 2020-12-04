package com.travelunion.flutter_vision;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.os.Build;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.DisplayOrientedMeteringPointFactory;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.travelunion.flutter_vision.detectors.BarcodeDetectionProcessor;
import com.travelunion.flutter_vision.detectors.FaceContourDetectionProcessor;
import com.travelunion.flutter_vision.detectors.TextDetectionProcessor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.platform.PlatformView;

public class CameraView implements PlatformView, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, TextDetectionProcessor.Result, FaceContourDetectionProcessor.Result, BarcodeDetectionProcessor.Result {

    private final MethodChannel methodChannel;
    private EventChannel.EventSink eventSink;
    PreviewView mPreviewView;
    private Executor executor = Executors.newSingleThreadExecutor();
    Camera camera;
    int flashMode = ImageCapture.FLASH_MODE_AUTO;
    ImageCapture imageCapture;
    int cameraId = 0;
    int lensFacing = CameraSelector.LENS_FACING_BACK;
    FlutterPlugin.FlutterPluginBinding flutterPluginBinding;
    FlutterVisionPlugin plugin;
    Context context;
    Rational aspectRatio = new Rational(16,9);
    ProcessCameraProvider cameraProvider;
    int CAMERA_REQUEST_ID = 513469796;
    boolean torchMode = false;
    ImageAnalysis imageAnalysis;
    FaceContourDetectionProcessor faceDetector;
    TextDetectionProcessor textRecognizer;
    BarcodeDetectionProcessor barcodeDetector;


    CameraView(Context context, BinaryMessenger messenger, int id, FlutterPlugin.FlutterPluginBinding flutterPluginBinding, FlutterVisionPlugin plugin) {
        methodChannel = new MethodChannel(messenger, Constants.methodChannelId + "_0");
        new EventChannel(messenger, Constants.methodChannelId + "/events").setStreamHandler(this);
        this.cameraId = id;
        this.context = context;
        this.plugin = plugin;
        this.flutterPluginBinding = flutterPluginBinding;
        methodChannel.setMethodCallHandler(this);
        mPreviewView = new PreviewView(context);
        mPreviewView.setImportantForAccessibility(0);
        mPreviewView.setMinimumHeight(100);
        mPreviewView.setMinimumWidth(100);
        mPreviewView.setContentDescription("Description Here");
    }

    private void startCamera(final Context context, MethodChannel.Result result, final FlutterVisionPlugin plugin) {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    if(cameraProvider!=null)
                        return;
                    cameraProvider = cameraProviderFuture.get();

                    bindPreview(cameraProvider, result, plugin);
                } catch (ExecutionException | InterruptedException e) {
                    // No errors need to be handled for this Future.
                    // This should never be reached.
                    result.error("initialize", "Failed to initialize camera: " + e.getLocalizedMessage(), null);
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @SuppressLint({"ClickableViewAccessibility", "RestrictedApi"})
    void bindPreview(@NonNull ProcessCameraProvider cameraProvider, MethodChannel.Result result, FlutterVisionPlugin plugin) {
        int width = Resources.getSystem().getDisplayMetrics().widthPixels;

        Preview.Builder previewBuilder = new Preview.Builder();
        @SuppressLint("RestrictedApi")
        Preview preview = previewBuilder
                .setTargetResolution(new Size(width, (int) (width*16.0/9.0)))
                .build();

        final CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing == CameraSelector.LENS_FACING_BACK ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT)
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        ImageCapture.Builder builder = new ImageCapture.Builder();

        imageCapture = builder
                .setTargetResolution(new Size(1080,1920))
                .setTargetRotation(plugin.activityPluginBinding.getActivity().getWindowManager().getDefaultDisplay().getRotation())
                .build();

        preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());
        imageCapture.setFlashMode(flashMode);

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(((LifecycleOwner) plugin.activityPluginBinding.getActivity()), cameraSelector, preview, imageAnalysis, imageCapture);

        camera.getCameraControl().enableTorch(torchMode);

        Map<String, Object> reply = new HashMap<>();
        reply.put("width", mPreviewView.getWidth());
        reply.put("height", mPreviewView.getHeight());

        result.success(reply);

        /*mPreviewView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int height = mPreviewView.getHeight();
                int width = mPreviewView.getWidth();
                float x = motionEvent.getX();
                float y = motionEvent.getY();
                MeteringPoint meteringPoint = new DisplayOrientedMeteringPointFactory(mPreviewView.getDisplay(), camera.getCameraInfo(), width, height).createPoint(x, y);
                FocusMeteringAction action = new FocusMeteringAction.Builder(meteringPoint).build();
                cameraControl.startFocusAndMetering(action);
                return false;
            }
        });*/
    }

    void captureImage(final MethodChannel.Result result){
        imageCapture.setFlashMode(flashMode);

        float x = (float) (mPreviewView.getHeight() * 0.25);
        float y = (float) (mPreviewView.getWidth() * 0.5);

        MeteringPoint meteringPoint = new DisplayOrientedMeteringPointFactory(mPreviewView.getDisplay(), camera.getCameraInfo(), mPreviewView.getWidth(), mPreviewView.getHeight()).createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(meteringPoint).build();
        final CameraControl cameraControl = camera.getCameraControl();
        cameraControl.startFocusAndMetering(action);

        imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull final ImageProxy image) {
                playClickSound();
                plugin.activityPluginBinding.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        @SuppressLint("UnsafeExperimentalUsageError")

                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        bitmapImage.compress(Bitmap.CompressFormat.JPEG, 90, out);

                        result.success(out.toByteArray());
                        image.close();
                    }
                });
                super.onCaptureSuccess(image);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                super.onError(exception);
            }
        });
    }

    void playClickSound(){
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        switch( audio.getRingerMode() ){
            case AudioManager.RINGER_MODE_NORMAL:
                MediaActionSound sound = new MediaActionSound();
                sound.play(MediaActionSound.SHUTTER_CLICK);
                break;
            case AudioManager.RINGER_MODE_SILENT:
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                break;
        }
    }

    private void setLensFacing(String lensFacing){
        this.lensFacing = Utils.getLensFacingFromString(lensFacing);
    }

    @Override
    public void onMethodCall(MethodCall call, @NonNull MethodChannel.Result result) {
        switch ((String)(call.method)) {
            case MethodNames.capture:
                captureImage(result);
                break;
            case MethodNames.initialize:
                setLensFacing((String)call.argument("lensFacing"));
                if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    plugin.activityPluginBinding.getActivity().requestPermissions(
                            new String[]{Manifest.permission.CAMERA},
                            513469796);
                    plugin.activityPluginBinding.addRequestPermissionsResultListener(new PluginRegistry.RequestPermissionsResultListener() {
                        @Override
                        public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                            if(requestCode==CAMERA_REQUEST_ID && grantResults[0]==PackageManager.PERMISSION_GRANTED) {
                                startCamera(context, result, plugin);
                            } else {
                                result.error("initialize", "Failed to initialize camera due to permissions not being granted.", null);
                            }
                            return false;
                        }
                    });
                } else {
                    result.error("initialize", "Failed to initialize because Android M is required to operate.", null);
                }
                break;
            case MethodNames.setAspectRatio:
                try {
                    aspectRatio = new Rational((int)(call.argument("num")), (int)(call.argument("denom")));
                    result.success(true);
                }catch (Exception e){
                    result.error("-2","Invalid Aspect Ratio","Invalid Aspect Ratio");
                }
                break;
            case MethodNames.addFaceDetector:
                faceDetector = new FaceContourDetectionProcessor(this);
                imageAnalysis.setAnalyzer(executor, faceDetector);
                result.success(true);
                break;
            case MethodNames.closeFaceDetector:
                if(faceDetector != null) {
                    faceDetector.stop();
                    imageAnalysis.clearAnalyzer();
                }
                result.success(true);
                break;
            case MethodNames.addTextRegonizer:
                textRecognizer = new TextDetectionProcessor(this);
                imageAnalysis.setAnalyzer(executor, textRecognizer);
                result.success(true);
                break;
            case MethodNames.closeTextRegonizer:
                if(textRecognizer != null) {
                    textRecognizer.stop();
                    imageAnalysis.clearAnalyzer();
                }
                result.success(true);
                break;
            case MethodNames.addBarcodeDetector:
                barcodeDetector = new BarcodeDetectionProcessor(this);
                imageAnalysis.setAnalyzer(executor, barcodeDetector);
                result.success(true);
                break;
            case MethodNames.closeBarcodeDetector:
                if(barcodeDetector != null) {
                    barcodeDetector.stop();
                    imageAnalysis.clearAnalyzer();
                }
                result.success(true);
                break;
            default:
                result.notImplemented();
        }
    }

    @Override
    public View getView() {
        return mPreviewView;
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void dispose() {
        cameraProvider.unbindAll();
        cameraProvider.shutdown();
        camera = null;
        imageCapture = null;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        Log.d("CameraView", "Events assigned");
        this.eventSink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        Log.d("CameraView", "Events unassigned");
        this.eventSink = null;
    }

    @Override
    public void onTextResult(Map<String, Object> result) {
        if(this.eventSink != null) {
            this.eventSink.success(result);
        }
    }

    @Override
    public void onTextError(Exception e) {
        if(this.eventSink != null) {
            this.eventSink.error("TextRecognizer", e.getLocalizedMessage(), null);
        }
    }

    @Override
    public void onFaceResult(Map<String, Object> result) {
        if(this.eventSink != null) {
            this.eventSink.success(result);
        }
    }

    @Override
    public void onFaceError(Exception e) {
        if(this.eventSink != null) {
            this.eventSink.error("FaceDetector", e.getLocalizedMessage(), null);
        }
    }

    @Override
    public void onBarcodeResult(Map<String, Object> result) {
        if(this.eventSink != null) {
            this.eventSink.success(result);
        }
    }

    @Override
    public void onBarcodeError(Exception e) {
        if(this.eventSink != null) {
            this.eventSink.error("BarcodeDetector", e.getLocalizedMessage(), null);
        }
    }
}
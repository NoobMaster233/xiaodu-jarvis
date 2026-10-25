package local.xiaodu.speech;

import android.content.Context;
import android.graphics.*;
import android.hardware.Camera;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One requested still image, in RAM only. Opens no microphone or activity. */
public final class CameraCapture {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    public static final class Failure extends Exception {
        final int status;final String code;
        Failure(int status,String code){this.status=status;this.code=code;}
    }
    public static byte[] photo(Context context) throws Exception {
        requireCameraEnabled(context);
        if(Camera.getNumberOfCameras()<1)throw new Failure(404,"camera_unavailable");
        if(!BUSY.compareAndSet(false,true))throw new Failure(409,"camera_busy");
        Session pending=null;
        try{
            pending=new Session(context,0);pending.thread.start();
            pending.handler=new Handler(pending.thread.getLooper());
        }catch(RuntimeException|OutOfMemoryError failed){
            if(pending!=null)pending.thread.quitSafely();BUSY.set(false);throw new Failure(503,"camera_start_failed");
        }
        final Session session=pending;
        if(!session.handler.post(session::open)){session.thread.quitSafely();BUSY.set(false);throw new Failure(503,"camera_start_failed");}
        try{return session.result.get(12,TimeUnit.SECONDS);}
        catch(TimeoutException timeout){session.cancelled=true;session.handler.post(()->session.finish(null,new Failure(503,"camera_timeout")));throw new Failure(503,"camera_timeout");}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Failure)throw (Failure)cause;throw new Failure(503,"camera_capture_failed");}
        catch(InterruptedException interrupted){session.cancelled=true;session.handler.post(()->session.finish(null,new Failure(503,"camera_interrupted")));Thread.currentThread().interrupt();throw new Failure(503,"camera_interrupted");}
    }
    private static void requireCameraEnabled(Context context) throws Failure {
        android.app.admin.DevicePolicyManager policy=(android.app.admin.DevicePolicyManager)context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if(policy==null)throw new Failure(503,"camera_policy_unavailable");
        if(policy.getCameraDisabled(null))throw new Failure(409,"camera_disabled");
    }
    private static final class Session {
        final Context context;final int id;
        final HandlerThread thread=new HandlerThread("xiaodu-single-photo");
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        Handler handler;Camera camera;SurfaceTexture texture;
        boolean finished,taken;volatile boolean cancelled;int frames,rotation;
        Session(Context context,int id){this.context=context;this.id=id;thread.setDaemon(true);}
        void open(){
            try{
                if(cancelled){finish(null,new Failure(503,"camera_cancelled"));return;}
                requireCameraEnabled(context);
                camera=Camera.open(id);
                if(cancelled){finish(null,new Failure(503,"camera_cancelled"));return;}
                camera.setErrorCallback((code,value)->finish(null,new Failure(503,"camera_device_error")));
                Camera.Parameters p=camera.getParameters();
                Camera.Size picture=pictureSize(p.getSupportedPictureSizes());p.setPictureSize(picture.width,picture.height);
                Camera.Size preview=previewSize(p.getSupportedPreviewSizes(),picture);p.setPreviewSize(preview.width,preview.height);
                p.setPictureFormat(ImageFormat.JPEG);p.setJpegQuality(90);p.setRotation(0);
                List<String> focus=p.getSupportedFocusModes();
                if(focus!=null&&focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                List<String> flash=p.getSupportedFlashModes();if(flash!=null&&flash.contains(Camera.Parameters.FLASH_MODE_OFF))p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                camera.setParameters(p);try{camera.enableShutterSound(false);}catch(RuntimeException ignored){}
                Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(id,info);
                int display=((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation()*90;
                rotation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(info.orientation+display)%360:(info.orientation-display+360)%360;
                texture=new SurfaceTexture(0);texture.setDefaultBufferSize(preview.width,preview.height);camera.setPreviewTexture(texture);
                camera.setPreviewCallback((data,value)->{
                    if(finished||taken)return;
                    if(++frames>=4){taken=true;camera.setPreviewCallback(null);
                        try{camera.takePicture(null,null,(jpeg,source)->complete(jpeg));}
                        catch(Exception failed){finish(null,new Failure(503,"camera_capture_failed"));}
                    }
                });
                camera.startPreview();
            }catch(SecurityException denied){finish(null,new Failure(503,"camera_permission_denied"));}
            catch(Exception failed){finish(null,new Failure(503,"camera_unavailable"));}
            catch(OutOfMemoryError low){finish(null,new Failure(503,"camera_memory_low"));}
        }
        void complete(byte[] jpeg){
            Bitmap original=null,upright=null;
            try{
                if(cancelled){finish(null,new Failure(503,"camera_cancelled"));return;}
                requireCameraEnabled(context);
                // Decode/re-encode once: correct orientation without mirror and remove EXIF.
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>2000000)throw new IOException("invalid_dimensions");
                original=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(original==null)throw new IOException("invalid_jpeg");
                Matrix matrix=new Matrix();matrix.postRotate(rotation);
                upright=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),matrix,true);
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                if(!upright.compress(Bitmap.CompressFormat.JPEG,88,out)||out.size()>3*1024*1024)throw new IOException("invalid_image_size");
                finish(out.toByteArray(),null);
            }catch(Exception failed){finish(null,new Failure(503,"camera_image_failed"));}
            catch(OutOfMemoryError low){finish(null,new Failure(503,"camera_memory_low"));}
            finally{if(upright!=null&&upright!=original)upright.recycle();if(original!=null)original.recycle();}
        }
        void finish(byte[] jpeg,Failure failure){
            if(finished)return;finished=true;
            try{if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();}catch(Exception ignored){}camera.release();camera=null;}}
            catch(Exception failed){failure=new Failure(503,"camera_release_failed");}
            finally{
                if(texture!=null){try{texture.release();}catch(Exception ignored){}texture=null;}
                BUSY.set(false);thread.quitSafely();
                if(failure==null)result.complete(jpeg);else result.completeExceptionally(failure);
            }
        }
    }
    private static Camera.Size pictureSize(List<Camera.Size> sizes) throws Failure {
        Camera.Size best=null;
        if(sizes==null)throw new Failure(503,"camera_size_unavailable");
        for(Camera.Size s:sizes){long area=(long)s.width*s.height;
            if(area<=2000000&&(best==null||area>(long)best.width*best.height))best=s;
        }
        if(best==null)throw new Failure(503,"camera_size_unavailable");return best;
    }
    private static Camera.Size previewSize(List<Camera.Size> sizes,Camera.Size picture) throws Failure {
        Camera.Size best=null;double score=Double.MAX_VALUE;
        if(sizes==null)throw new Failure(503,"camera_size_unavailable");
        for(Camera.Size s:sizes){double v=Math.abs((double)s.width/s.height-(double)picture.width/picture.height)*1000;
            long area=(long)s.width*s.height;v+=area>640*480?10000+area:-(double)area/(640*480);
            if(v<score){score=v;best=s;}
        }
        if(best==null)throw new Failure(503,"camera_size_unavailable");return best;
    }
}

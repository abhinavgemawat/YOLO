package ie.tcd.netlab.objecttracker.detectors;
//import ie.tcd.netlab.objecttracker.detectors.Solution;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.app.ActivityCompat;
import android.graphics.RectF;
import android.media.Image;
import android.os.Build;
import android.util.Size;
import android.widget.Toast;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

import java.nio.ByteBuffer;
import java.net.Socket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.io.IOException;
import java.util.Iterator;

import ie.tcd.netlab.objecttracker.helpers.Recognition;
import ie.tcd.netlab.objecttracker.helpers.Transform;
import ie.tcd.netlab.objecttracker.testing.Logger;

public class DetectorYoloHTTP extends Detector {

    private final int jpegQuality;
    private InetAddress IP;
    ByteBuffer IPbuf;
    private final String server;
    private final int port;
    private final boolean useUDP;
    private int udpsockfd=-1;
    private Socket tcpsock;
    BufferedOutputStream out;
    BufferedReader in;
    private final static int LISTSIZE=1000; // if change this then also change value in udp_socket_jni.c
    ByteBuffer recvbuf, image_bytes, req_buf;
    private final static int MSS=1472;          // max UDP payload (assuming 1500B packets)
    private static final boolean DEBUGGING = false;  // generate extra debug output ?

    public Bitmap imgOutput;
    public Bitmap scaledBitmap;

    static {
        System.loadLibrary("udpsocket");
    }
    private native int socket(ByteBuffer addr, int port);
    private native void closesocket(int fd);
    private native String sendto(int fd, ByteBuffer sendbuf, int offset, int len, int MSS);
    private native String sendmmsg(int fd, ByteBuffer req, int req_len, ByteBuffer img, int img_len, int MSS);
    private native int recv(int fd, ByteBuffer recvbuf, int len, int MSS);
    //private native void keepalive();

    public DetectorYoloHTTP(@NonNull Context context, String server, int jpegQuality, boolean useUDP) {
        String parts[] = server.split(":");
        this.server=parts[0]; this.port=Integer.valueOf(parts[1]); //server details
        this.IP=null; // this will force DNS resolution of server name in background thread below
        // (since it may take a while and anyway DNS on the UI thread is banned by android).
        this.jpegQuality = jpegQuality;
        this.useUDP = useUDP;
        this.tcpsock = null;
        // can't open sockets here as may not yet have internet permission
        // only open them once, so that tcp syn-synack handshake is not repeated for every image
        if (!hasPermission(context)) { // need internet access to use YoloHTTP
            requestPermission((Activity) context);
        }
        // allocate byte buffers used to pass data to jni C
        recvbuf = ByteBuffer.allocateDirect(MSS*LISTSIZE);
        IPbuf = ByteBuffer.allocateDirect(4); // size of an IPv4 address
        image_bytes=ByteBuffer.allocateDirect(MSS*LISTSIZE);
        req_buf=ByteBuffer.allocateDirect(MSS);

    }


    protected void finalize() {
        if (udpsockfd >0) {
            closesocket(udpsockfd);
        }
        if (this.tcpsock != null) {
            try {
                this.tcpsock.close();
            } catch(Exception e) {
                Logger.addln("\nWARN Problem closing TCP socket ("+e.getMessage()+")");
            }
        }
    }


    public Detections recognizeImage(Image image, int rotation) {
        // take Image as input, convert to byte array and then call recognise()

        Bitmap rgbFrameBitmap = Transform.convertYUVtoRGB(image);


        if (image.getFormat() != ImageFormat.YUV_420_888) {
            // unsupported image format
            Logger.addln("\nWARN YoloHTTP.recognizeImage() unsupported image format");
            return new Detections();
        }
        //return recognize(Transform.yuvBytes(image), image.getWidth(),image.getHeight(), rotation, rgbFrameBitmap);
        //return frameworkMaxAreaRectangle(Transform.yuvBytes(image), image.getWidth(),image.getHeight(), rotation, rgbFrameBitmap);
        //return frameworkNineBoxes(Transform.yuvBytes(image),  image.getWidth(),image.getHeight(),rotation, rgbFrameBitmap,image.getHeight()/3,image.getWidth()/3,image.getHeight()/3,image.getWidth()/3);
        //return frameworkQuadrant(Transform.yuvBytes(image), image.getWidth(),image.getHeight(), rotation, rgbFrameBitmap);
        return frameworkMaxAreaRectBD(Transform.yuvBytes(image), image.getWidth(),image.getHeight(), rotation, rgbFrameBitmap);

    }

    //saving image to phone code - https://stackoverflow.com/questions/15662258/how-to-save-a-bitmap-on-internal-storage
    private static void SaveImage(Bitmap finalBitmap) {
        File path = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File file = new File(path, "DemoPicture.jpg");

        String fname = "Image" +".jpg";

        try {
            path.mkdirs();
            FileOutputStream out = new FileOutputStream(file);
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    public RectF maximalRectangle(int[][] matrix) {
        if (matrix.length <= 0)
            return null;
        int n = matrix.length;
        int m = matrix[0].length;
        int[][] dp = new int[n][m];
        int x1=0, y1=0, x2=0, y2=0;
        int maxArea = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (i == 0)
                    dp[i][j] = matrix[i][j] == 0 ? 1 : 0;
                else
                    dp[i][j] = matrix[i][j] == 0 ? (dp[i-1][j] + 1) : 0;
                int min = dp[i][j];
                for (int k = j; k >= 0; k--) {
                    if (min == 0) break;
                    if (dp[i][k] < min) min = dp[i][k];
                    if(maxArea < min * (j - k + 1)) {
                        maxArea = min * (j - k + 1);
                        x2 = i; y2 = j;
                        x1= i-min+1; y1 = k;
                    }
                }
            }
        }
        RectF rectF=new RectF(y1,x1,y2,x2);
        System.out.printf("[%d, %d] [%d, %d]",y1, x1, y2, x2);

        return rectF;

    }

    public RectF maximalRectangle1(int[][] matrix) {
        if (matrix.length <= 0)
            return null;
        int n = matrix.length;
        int m = matrix[0].length;
        int[][] dp = new int[n][m];
        int x1=0, y1=0, x2=0, y2=0;
        int maxArea = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (i == 0)
                    dp[i][j] = matrix[i][j] == 1 ? 1 : 0;
                else
                    dp[i][j] = matrix[i][j] == 1 ? (dp[i-1][j] + 1) : 0;
                int min = dp[i][j];
                for (int k = j; k >= 0; k--) {
                    if (min == 0) break;
                    if (dp[i][k] < min) min = dp[i][k];
                    if(maxArea < min * (j - k + 1)) {
                        maxArea = min * (j - k + 1);
                        x2 = i; y2 = j;
                        x1= i-min+1; y1 = k;
                    }
                }
            }
        }
        RectF rectF=new RectF(y1,x1,y2,x2);
        System.out.printf("[%d, %d] [%d, %d]",y1, x1, y2, x2);

        return rectF;

    }

    public Detections add_times(Detections a, Detections b){
        Detections c =new Detections();
        Iterator<String> keys=a.client_timings.keys();
        while(keys.hasNext()){
            String key = keys.next();
            int add = 0;
            try {
                add = a.client_timings.getInt(key);
                add = add + b.client_timings.getInt(key);
                c.client_timings.put(key, add);

            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
        Iterator<String> keys1 = a.server_timings.keys();
        while(keys1.hasNext()){
            String key1 = keys1.next();
            int add = 0;
            try {
                add = a.server_timings.getInt(key1);
                add = add + b.server_timings.getInt(key1);
                c.server_timings.put(key1, add);

            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
        c.results.addAll(a.results);
        c.results.addAll(b.results);
        return c;

    }


    public Detections frameworkNineBoxes(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image,int dst_w,int dst_h,int start_x,int start_y){

        Detections ultimate = new Detections();

//        Detections now1=splitAndSendNew(yuv,image_w,image_h,rotation,image,(int) new_rect1.width(),(int) new_rect1.height(), (int) new_rect1.left,(int) new_rect1.top);
//        Bitmap resized1 = Bitmap.createBitmap(image, (int) new_rect1.left, (int) new_rect1.top, (int) new_rect1.width(), (int) new_rect1.height());

        if(start_x>0 && start_y>0){
            Detections one = splitAndSendNew(yuv,image_w,image_h,rotation,image,start_x,start_y, 0,0);
            ultimate.results.addAll(one.results);
        }
        if(dst_w>0 && start_y>0){
            Detections two = splitAndSendNew(yuv,image_w,image_h,rotation,image,dst_w,start_y,start_x,0);
            ultimate.results.addAll(two.results);}
        if(image_w-start_x-dst_w>0 && start_y>0){
            Detections three = splitAndSendNew(yuv,image_w,image_h,rotation,image,image_w-start_x-dst_w,start_y, start_x+dst_w,0);
            ultimate.results.addAll(three.results);}
        if(start_x>0 && dst_h>0){
            Detections four = splitAndSendNew(yuv,image_w,image_h,rotation,image,start_x,dst_h, 0,start_y);
            ultimate.results.addAll(four.results);}
        if(image_w-start_x-dst_w>0 && dst_h>0){
            Detections five = splitAndSendNew(yuv,image_w,image_h,rotation,image,image_w-start_x-dst_w,dst_h, start_x+dst_w,start_y);
            ultimate.results.addAll(five.results);}
        if(start_x>0 && image_h-start_y-dst_h>0){
            Detections six = splitAndSendNew(yuv,image_w,image_h,rotation,image,start_x,image_h-start_y-dst_h, 0,start_y+dst_h);
            ultimate.results.addAll(six.results);}
        if(dst_w>0 && image_h-start_y-dst_h>0){
            Detections seven = splitAndSendNew(yuv,image_w,image_h,rotation,image,dst_w,image_h-start_y-dst_h, start_x,start_y+dst_h);
            ultimate.results.addAll(seven.results);}
        if(image_w-start_x-dst_w>0 && image_h-start_y-dst_h>0){
            Detections eight = splitAndSendNew(yuv,image_w,image_h,rotation,image,image_w-start_x-dst_w,image_h-start_y-dst_h, start_x+dst_w,start_y+dst_h);
            ultimate.results.addAll(eight.results);}

        return ultimate;

    }

    public Detections nineWrapper(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        int width=image_w;
        int height=image_h;

        int dst_w=width;
        int dst_h=height;
        int start_x=0;
        int start_y=0;

        //Detections now123 = splitAndSendNew(yuv,image_w,image_h,rotation,image,dst_w,dst_h,start_x,start_y);
//        if (image_h>0)
//            return now123;
        //Bitmap full = Bitmap.createBitmap(image, 0, 0, image_w, image_h);

        Detections now123 = recognize(Transform.convertRGBtoYUV(image), image.getWidth(), image.getHeight(), rotation, image);
        // Detections finale = new Detections();

        int[][] image_array = new int[image_h][image_w];

        int[][] rec_array = new int[image_h][image_w];

        for(Recognition result:now123.results){
            RectF rectF = new RectF(result.location);
            for(int x=(int)rectF.top;x<rectF.top + rectF.height();x++){
                for(int y=(int)rectF.left;y<rectF.left + rectF.width();y++){
                    image_array[x][y]=1;
                }
            }
        }

        RectF new_rect1=maximalRectangle1(image_array);
        if(new_rect1.width()>0 && new_rect1.height()>0){
            Detections ultimate= frameworkNineBoxes(yuv, image_w, image_h, rotation, image, (int) new_rect1.width(), (int) new_rect1.height(), (int) new_rect1.left,(int) new_rect1.top );
            now123.results.addAll(ultimate.results);
        }


        return now123;
    }



    public Detections frameworkMaxAreaRectBD(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        int width=image_w;
        int height=image_h;

        int dst_w=width;
        int dst_h=height;
        int start_x=0;
        int start_y=0;

        //  Detections now123 = splitAndSendNew(yuv,image_w,image_h,rotation,image,dst_w,dst_h,start_x,start_y);
//        if (image_h>0)
//            return now123;
        //Bitmap full = Bitmap.createBitmap(image, 0, 0, image_w, image_h);

        Detections now123 = recognize(Transform.convertRGBtoYUV(image), image.getWidth(), image.getHeight(), rotation, image);
        // Detections finale = new Detections();

        int[][] image_array = new int[image_h][image_w];

        int[][] rec_array = new int[image_h][image_w];

        for(Recognition result:now123.results){
            RectF rectF = new RectF(result.location);
            for(int x=(int)rectF.top;x<rectF.top + rectF.height();x++){
                for(int y=(int)rectF.left;y<rectF.left + rectF.width();y++){
                    image_array[x][y]=1;
                }
            }
        }

        RectF new_rect1=maximalRectangle(image_array);
        if(new_rect1.width()>0 && new_rect1.height()>0){


            Detections now1=splitAndSendNew(yuv,image_w,image_h,rotation,image,(int) new_rect1.width(),(int) new_rect1.height(), (int) new_rect1.left,(int) new_rect1.top);
            Bitmap resized1 = Bitmap.createBitmap(image, (int) new_rect1.left, (int) new_rect1.top, (int) new_rect1.width(), (int) new_rect1.height());
            //// Detections now1 = recognize(Transform.convertRGBtoYUV(resized1), resized1.getWidth(), resized1.getHeight(), rotation, resized1);

            for(int x=(int)new_rect1.top;x<new_rect1.top + new_rect1.height();x++){
                for(int y=(int)new_rect1.left;y<new_rect1.left + new_rect1.width();y++){
                    rec_array[x][y]=1;
                    //image_array[x][y] = 0;
                }
            }
            SaveImage(resized1);
            //now123.results.addAll(now1.results);
            now123 = add_times(now123, now1);

        }


        RectF new_rect4 = maximalRectangle(rec_array);
        if(new_rect4.width()>0 && new_rect4.height()>0){
            Detections now2=splitAndSendNew(yuv,image_w,image_h,rotation,image,(int) new_rect4.width(),(int) new_rect4.height(), (int) new_rect4.left,(int) new_rect4.top);
            Bitmap resized2 = Bitmap.createBitmap(image, (int) new_rect4.left, (int) new_rect4.top, (int) new_rect4.width(), (int) new_rect4.height());
            //Detections now2 = recognize(Transform.convertRGBtoYUV(resized2), resized2.getWidth(), resized2.getHeight(), rotation, resized2);

            for(int x=(int)new_rect4.top;x<new_rect4.top + new_rect4.height();x++){
                for(int y=(int)new_rect4.left;y<new_rect4.left + new_rect4.width();y++){
                    rec_array[x][y]=1;
                }
            }

            //now123.results.addAll(now2.results);
            now123 = add_times(now123, now2);

        }




        RectF new_rect5 = maximalRectangle(rec_array);
        if(new_rect5.width()>0 && new_rect5.height()>0){
            Detections now3 = splitAndSendNew(yuv,image_w,image_h,rotation,image,(int) new_rect5.width(),(int) new_rect5.height(), (int) new_rect5.left,(int) new_rect5.top);
            Bitmap resized3 = Bitmap.createBitmap(image, (int) new_rect5.left, (int) new_rect5.top, (int) new_rect5.width(), (int) new_rect5.height());
            //Detections now3 = recognize(Transform.convertRGBtoYUV(resized3), resized3.getWidth(), resized3.getHeight(), rotation, resized3);
            for(int x=(int)new_rect5.top;x<new_rect5.top + new_rect5.height();x++){
                for(int y=(int)new_rect5.left;y<new_rect5.left + new_rect5.width();y++){
                    rec_array[x][y]=1;
                }
            }
            //now123.results.addAll(now3.results);
            now123 = add_times(now123, now3);

        }

        if(image_h>0)
            return now123;



        RectF new_rect3 = maximalRectangle(rec_array);
        if(new_rect3.width()>0 && new_rect3.height()>0){
            Detections now4 = splitAndSendNew(yuv,image_w,image_h,rotation,image,(int) new_rect3.width(),(int) new_rect3.height(), (int) new_rect3.left,(int) new_rect3.top);
            Bitmap resized4 = Bitmap.createBitmap(image, (int) new_rect3.left, (int) new_rect3.top, (int) new_rect3.width(), (int) new_rect3.height());
            //Detections now4 = recognize(Transform.convertRGBtoYUV(resized4), resized4.getWidth(), resized4.getHeight(), rotation, resized4);

            for(int x=(int)new_rect3.top;x<new_rect3.top + new_rect3.height();x++){
                for(int y=(int)new_rect3.left;y<new_rect3.left + new_rect3.width();y++){
                    rec_array[x][y]=1;
                }
            }

            //now123.results.addAll(now4.results);
            now123 = add_times(now123, now4);

        }

        return now123;

    }



    public Detections frameworkMaxAreaRectangle(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){


//        Detections save = splitAndSend(yuv,image_w,image_h,rotation,image,dst_w,dst_h,start_x,start_y);
//        Detections now=splitAndSend(yuv,image_w,image_h,rotation,image,dst_w,dst_h,start_x,start_y);
//        if(image_h>0)
//        return now;

        Detections now = recognize(Transform.convertRGBtoYUV(image), image_w, image_h, rotation, image);
//        if(image_h>0)
//          return now;


        int[][] image_array = new int[image_h][image_w];

        for(Recognition result:now.results){
            RectF rectF = new RectF(result.location);
            for(int x=(int)rectF.top;x<rectF.top + rectF.height();x++){
                for(int y=(int)rectF.left;y<rectF.left + rectF.width();y++){
                    image_array[x][y]=1;
                }
            }
            // Detections repeat = splitAndSend1(yuv,image_w,image_h,rotation,image,(int) rectF.height(),(int) rectF.width(), 480 - (int) rectF.bottom,(int) rectF.left);
            //save.results.addAll(splitAndSend1(yuv,image_w,image_h,rotation,image,(int) rectF.height(),(int) rectF.width(), 480 - (int) rectF.bottom,(int) rectF.left).results);
            //return repeat;
            //return repeat;
        }
        //now.results.addAll(save.results);

        RectF new_rect=maximalRectangle(image_array);
        Bitmap resized = Bitmap.createBitmap(image, (int) new_rect.left, (int) new_rect.top, (int) new_rect.width(), (int) new_rect.height());
//        SaveImagess(resized);
//        SaveImagesss(image);



        // Detections now1=splitAndSend(yuv,image_w,image_h,rotation,image,(int) new_rect.height(),(int) new_rect.width(), image_h - (int) new_rect.bottom,(int) new_rect.left);
        Detections now1 = recognize(Transform.convertRGBtoYUV(resized), resized.getWidth(), resized.getHeight(), rotation, resized);
        now = add_times(now, now1);
        // now.results.addAll(now1.results);

        for(int x=(int)new_rect.top;x<new_rect.top + new_rect.height();x++){
            for(int y=(int)new_rect.left;y<new_rect.left + new_rect.width();y++){
                image_array[x][y]=1;
            }
        }
        RectF new_rect1=maximalRectangle(image_array);


        if(new_rect1.height()>5 && new_rect1.width()>5){
            Bitmap resized1 = Bitmap.createBitmap(image, (int) new_rect1.left, (int) new_rect1.top, (int) new_rect1.width(), (int) new_rect1.height());

//            Detections now12=splitAndSend(yuv,image_w,image_h,rotation,image,(int) new_rect.height(),(int) new_rect.width(), image_h - (int) new_rect.bottom,(int) new_rect.left);
            Detections now12 = recognize(Transform.convertRGBtoYUV(resized1), resized1.getWidth(), resized1.getHeight(), rotation, resized1);
            //now.results.addAll(now12.results);
            now = add_times(now, now12);

//            SaveImage(resized1);
        }

        //now.results.addAll(now1.results);




        return  now;
    }

    public Detections splitAndSendNew(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image,int dst_w,int dst_h,int start_x,int start_y){



        Bitmap resized = Bitmap.createBitmap(image, start_x, start_y, dst_w, dst_h);
//        SaveImages(resized);

        if (resized.getHeight() < 0 || resized.getWidth() < 0)
            return null;

        Detections now = recognize(Transform.convertRGBtoYUV(resized),resized.getWidth(), resized.getHeight(), rotation, resized);

        float ratio=(float) dst_h/(float) image_w;
        float ratio1=(float) dst_w/(float) image_h;
        float l = Math.max(ratio,ratio1);



        for(Recognition result : now.results){
            RectF rectF = new RectF(result.location);
            RectF rect = new RectF(rectF.left+start_x,
                    rectF.top+start_y,
                    rectF.right+start_x,
                    rectF.bottom+start_y);

            System.out.println("....."+ start_x + "...." + (rectF.left + start_x) );
            System.out.println("....."+ start_y + "...." + (rectF.top + start_y) );
            System.out.println("....."+ (start_x + dst_w) + "...." + rectF.right );
            System.out.println("....."+ (start_y + dst_h) + "...." + rectF.bottom );





            //viewToFrameTransform.mapRect(rect); // map boxes back to original image co

            result.location.set(rect);

        }
        return now;

    }



    public Detections splitAndSend(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image,int dst_w,int dst_h,int start_x,int start_y){


        int width = dst_h;
        int height = dst_w;
        int x = start_y;
        int y = image_h-(start_x+dst_w);



        Bitmap resized = Bitmap.createBitmap(image, x, y, width, height);//bottom left

        Detections now = recognize(Transform.convertRGBtoYUV(resized),resized.getWidth(), resized.getHeight(), rotation, resized);


        Matrix frameToViewTransform = Transform.getTransformationMatrix(
                dst_h, dst_w,
                dst_w, dst_h,
                rotation, false);
        Matrix viewToFrameTransform = new Matrix();
        frameToViewTransform.invert(viewToFrameTransform);
        float ratio=(float) dst_h/(float) image_w;
        float ratio1=(float) dst_w/(float) image_h;
        float l = Math.max(ratio,ratio1);


        for(Recognition result : now.results){
            RectF rectF = new RectF(result.location);
            RectF rect = new RectF(rectF.left*l+start_x*l,
                    rectF.top*l+start_y*l,
                    rectF.right*l+start_x*l,
                    rectF.bottom*l+start_y*l);

            viewToFrameTransform.mapRect(rect); // map boxes back to original image co

            result.location.set(rect);

        }
        return now;

    }



    public Detections frameworkQuadrant(byte[] yuv, int image_w, int image_h, int rotation, Bitmap image){
        Bitmap resized = Bitmap.createBitmap(image, image_w/2, image_h/2, image_w/2, image_h/2);//bottom right
        Bitmap resized1 = Bitmap.createBitmap(image, image_w/2, 0 ,image_w/2, image_h/2);//top right
        Bitmap resized3 = Bitmap.createBitmap(image, 0, 0, image_w/2, image_h/2);//top left
        Bitmap resized2 = Bitmap.createBitmap(image, 0, image_h/2, image_w/2, image_h/2);//bottom left

//        SaveImage(resized);
//        SaveImages(resized1);
//        SaveImagess(resized2);
//        SaveImagesss(resized3);
//        SaveImagessss(image);

        Detections now = recognize(Transform.convertRGBtoYUV(image),image.getWidth(), image.getHeight(), rotation, image);

        //top left
        Detections now4 = recognize(Transform.convertRGBtoYUV(resized3),resized3.getWidth(), resized3.getHeight(), rotation, resized3);
        for(Recognition result : now4.results){
            RectF rectF = new RectF(result.location);
            RectF rect = new RectF(rectF.left,
                    rectF.top,
                    rectF.right,
                    rectF.bottom);

            ////viewToFrameTransform.mapRect(rect); // map boxes back to original image co
            result.location.set(rect);
            //now.results.addAll(now4.results);
            now = add_times(now, now4);

        }


        //top right
        Detections now1 = recognize(Transform.convertRGBtoYUV(resized1),resized1.getWidth(), resized1.getHeight(), rotation, resized1);

        for(Recognition result1 : now1.results){

            RectF rectF = new RectF(result1.location);
            //viewToFrameTransform.mapRect(rectF);
            RectF rect = new RectF(rectF.left + image_w/2,
                    rectF.top,
                    rectF.right + image_w/2,
                    rectF.bottom);

            // map boxes back to original image co
            result1.location.set(rect);
            //now.results.addAll(now1.results);
            now = add_times(now, now1);

        }


        //bottom left
        Detections now2 = recognize(Transform.convertRGBtoYUV(resized2),resized2.getWidth(), resized2.getHeight(), rotation, resized2);

        for(Recognition result2 : now2.results){

            RectF rectF = new RectF(result2.location);
            //viewToFrameTransform.mapRect(rectF);
            RectF rect = new RectF(rectF.left,
                    rectF.top+image_h/2,
                    rectF.right,
                    rectF.bottom+image_h/2);
            //viewToFrameTransform.mapRect(rect);
            // map boxes back to original image co


            result2.location.set(rect);
            //now.results.addAll(now2.results);
            now = add_times(now, now2);

        }


        //bottom right
        Detections now3 = recognize(Transform.convertRGBtoYUV(resized),resized.getWidth(), resized.getHeight(), rotation, resized);

        for(Recognition result3 : now3.results){

            RectF rectF = new RectF(result3.location);
            //viewToFrameTransform.mapRect(rectF);
            RectF rect = new RectF(rectF.left+image_w/2,
                    rectF.top+image_h/2,
                    rectF.right+image_w/2,
                    rectF.bottom+image_h/2);
            //viewToFrameTransform.mapRect(rect);
            // map boxes back to original image co


            result3.location.set(rect);
            //now.results.addAll(now3.results);
            now = add_times(now, now3);

        }

        return now;
    }



    @Override
    public Detections recognize(byte[] yuv, int image_w, int image_h, int rotation, Bitmap b) {
        // takes yuv byte array as input
        System.out.println(image_h + "......ye height hai");
        System.out.println(image_w + "......ye width hai");
        Detections detects = new Detections();


        Logger.tick("d");
        Logger.tick("yuvtoJPG");
        int isYUV;
        image_bytes.clear();
        if (jpegQuality>0) {
            // we do rotation server-side, android client too slow (takes around 10ms in both java
            // and c on Huawei P9, while jpeg compression takes around 8ms).
            try {
                image_bytes.put(Transform.YUVtoJPEG(yuv, image_w, image_h, jpegQuality));
                isYUV = 0;
            } catch (Exception e) {
                // most likely encoded image is too big for image_bytes buffer
                Logger.addln("WARN: Problem encoding jpg: "+e.getMessage());
                return detects; // bail
            }
        } else {
            // send image uncompressed
            image_bytes.put(yuv);
            isYUV=1;
        }
        detects.addTiming("yuvtoJPG",Logger.tockLong("yuvtoJPG"));

        int dst_w=image_w, dst_h=image_h;
        if ((rotation%180 == 90) || (rotation%180 == -90)) {
            dst_w = image_h; dst_h = image_w;
        }
        Matrix frameToViewTransform = Transform.getTransformationMatrix(
                image_w, image_h,
                dst_w, dst_h,
                rotation, false);
        // used to map received response rectangles back to handset view
        Matrix viewToFrameTransform = new Matrix();
        frameToViewTransform.invert(viewToFrameTransform);

        if (IP==null) {
            // resolve server name to IP address
            try {
                InetAddress names[] = InetAddress.getAllByName(server);
                StringBuilder n = new StringBuilder();
                for (InetAddress name : names) {
                    n.append(name);
                    if (name instanceof Inet4Address) {IP = name; break;}
                }
                Logger.addln("\nResolved server to: "+IP);
                if (IP == null) {
                    Logger.addln("\nWARN Problem resolving server: "+n);
                    return detects;
                }

            } catch (IOException e) {
                Logger.addln("\nWARNProblem resolving server "+server+" :"+e.getMessage());
                return detects;
            }
        }

        String req = "POST /api/edge_app2?r=" + rotation
                + "&isYUV=" + isYUV + "&w="+ image_w + "&h="+image_h
                + " HTTP/1.1\r\nContent-Length: " + image_bytes.position() + "\r\n\r\n";
        StringBuilder response = new StringBuilder();
        if (useUDP) {
            try {
                Logger.tick("url2");
                // open connection (if not already open) and send request+image
                if (udpsockfd <0) {
                    // put the server IP address into a byte buffer to make it easy to pass to jni C
                    IPbuf.position(0);
                    IPbuf.put(IP.getAddress());
                    udpsockfd=socket(IPbuf,port);
                    Debug.println("sock_fd="+udpsockfd);
                }
                Debug.println("data len=("+req.length()+","+image_bytes.position()+")");
                Logger.tick("url2a");
                // copy request to byte buffer so easy to pass to jni C
                req_buf.clear();
                req_buf.put(req.getBytes(),0,req.length());
                String str = sendmmsg(udpsockfd, req_buf, req.length(), image_bytes, image_bytes.position(), MSS);
                Debug.println("s: "+str);
                //Logger.add("s: "+str);
                detects.addTiming("url2a",Logger.tockLong("url2a"));
                detects.addTiming("url2",Logger.tockLong("url2"));
                int count=1+(req.length()+image_bytes.position())/(MSS-2);
                detects.addTiming("pkt count", count*1000);

                // read the response ...
                Logger.tick("url3");
                // need to receive on same socket as used for sending or firewall blocks reception
                int resplen = recv(udpsockfd, recvbuf, MSS*LISTSIZE, MSS);
                if (resplen<0) {
                    Logger.addln("\nWARN UDP recv error: errno="+resplen);
                } else if (resplen==0) {
                    Logger.addln("\nWARN UDP timeout");
                } else {
                    response.append(new String(recvbuf.array(), recvbuf.arrayOffset(), resplen));
                }
                if (response.length()<=10) {
                    Debug.println(" received " + response.length());
                }
                detects.addTiming("url3",Logger.tockLong("url3"));
                Logger.addln(detects.client_timings.toString());
                //String pieces[] = response.split("\n");
                //response = pieces[pieces.length-1];  // ignore all the headers (shouldn't be any !)
            } catch(Exception e) {
                Logger.addln("\nWARN Problem with UDP on "+IP+":"+port+" ("+e.getMessage()+")");
            }
        } else { // use TCP
            try {
                // open connection and send request+image
                Logger.tick("url2");
                if (tcpsock == null) {
                    tcpsock = new Socket(IP, port);
                    out = new BufferedOutputStream(tcpsock.getOutputStream());
                    in = new BufferedReader(new InputStreamReader(tcpsock.getInputStream()));
                }
                try {
                    out.write(req.getBytes());
                    out.write(image_bytes.array(),image_bytes.arrayOffset(),image_bytes.position());
                    out.flush();
                } catch(IOException ee) {
                    // legacy server closes TCP connection after each response, in which case
                    // we reopen it here.
                    Logger.addln("Retrying TCP: "+ee.getMessage());
                    tcpsock.close();
                    tcpsock = new Socket(IP, port);
                    out = new BufferedOutputStream(tcpsock.getOutputStream());
                    in = new BufferedReader(new InputStreamReader(tcpsock.getInputStream()));
                    out.write(req.getBytes());
                    out.write(image_bytes.array());
                    out.flush();
                }
                detects.addTiming("url2",Logger.tockLong("url2"));

                Logger.tick("url3");
                // read the response ...
                // read the headers, we ignore them all !
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() == 0) break; // end of headers, stop
                }
                // now read to end of response
                response.append(in.readLine());
                detects.addTiming("url3",Logger.tockLong("url3"));
            } catch(Exception e) {
                Logger.addln("\nWARN Problem connecting TCP to "+IP+":"+port+" ("+e.getMessage()+")");
                try {
                    tcpsock.close();
                } catch(Exception ee) {};
                tcpsock = null; // reset connection
            }
        }
        if (response.length()==0 || response.toString().equals("null")) {
            Logger.add(" empty response");
            Logger.add(": "+Logger.tock("d"));
            return detects; // server has dropped connection
        }
        // now parse the response as json ...
        try {
            // testing
            //response = "{"server_timings":{"size":91.2,"r":0.4,"jpg":8.4,"rot":34.1,"yolo":48.3,"tot":0},"results":[{"title":"diningtable","confidence":0.737176,"x":343,"y":415,"w":135,"h":296},{"title":"chair","confidence":0.641756,"x":338,"y":265,"w":75,"h":57},{"title":"chair","confidence":0.565877,"x":442,"y":420,"w":84,"h":421}]}
            //              [{"title":"diningtable","confidence":0.737176,"x":343,"y":415,"w":135,"h":296},{"title":"chair","confidence":0.641756,"x":338,"y":265,"w":75,"h":57},{"title":"chair","confidence":0.565877,"x":442,"y":420,"w":84,"h":421}]
            //              cam: 39 {"yuvtoJPG":8,"url2":15,"url3":128,"d":152}"
            JSONObject json_resp = new JSONObject(response.toString());
            JSONArray json = json_resp.getJSONArray("results");
            int i; JSONObject obj;
            for (i = 0; i < json.length(); i++) {
                obj = json.getJSONObject(i);
                String title = obj.getString("title");
                Float confidence = (float) obj.getDouble("confidence");
                Float x = (float) obj.getInt("x");
                Float y = (float) obj.getInt("y");
                Float w = (float) obj.getInt("w");
                Float h = (float) obj.getInt("h");
                RectF location = new RectF(
                        Math.max(0, x - w / 2),  // left
                        Math.max(0, y - h / 2),  // top
                        Math.min(dst_w - 1, x + w / 2),  //right
                        Math.min(dst_h - 1, y + h / 2));  // bottom
                viewToFrameTransform.mapRect(location); // map boxes back to original image co
                Recognition result = new Recognition(title, confidence, location, new Size(image_w, image_h));
                detects.results.add(result);
            }
            detects.server_timings = json_resp.getJSONObject("server_timings");
        } catch(Exception e) {
            Logger.addln("\nWARN Problem reading JSON:  "+response+" ("+e.getMessage()+")");
        }
        detects.addTiming("d",Logger.tockLong("d"));
        return detects;
    }

    /***************************************************************************************/
    private boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission(final Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity,Manifest.permission.INTERNET)) {
                // send message to user ...
                activity.runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(activity,
                                        "Internet permission is required to use YoloHTTP",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
            }
            ActivityCompat.requestPermissions(activity,new String[]{Manifest.permission.INTERNET},
                    2);
            // will enter onRequestPermissionsResult() callback in class cameraFragment following
            // user response to permissions request (bit messy that its hidden inside that class,
            // should probabyl tidy it up).

        }
    }

    /***************************************************************************************/
    // debugging
    private static class Debug {
        static void println(String s) {
            if (DEBUGGING) System.out.println("YoloHTTP: "+s);
        }
    }
}

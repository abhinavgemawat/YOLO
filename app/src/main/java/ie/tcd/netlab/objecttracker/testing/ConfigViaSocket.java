package ie.tcd.netlab.objecttracker.testing;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;

import android.os.Environment;
import android.support.v4.app.FragmentActivity;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.json.JSONArray;
import org.json.JSONObject;

import ie.tcd.netlab.objecttracker.helpers.Recognition;
import ie.tcd.netlab.objecttracker.helpers.Transform;
import ie.tcd.netlab.objecttracker.detectors.Detections;

public class ConfigViaSocket {

    private static final int socketServerPORT = 8080;
    private ServerSocket serverSocket;
    private FragmentActivity activity;
    private static final int MAXLEN=1024000;  // 1MB, maximum size of JPEG image
    private static final int MAXYUV=2048000;  // 2MB, max size of uncompressed YUV image
    private static final boolean DEBUGGING = false;  // generate extra debug output ?

    // object detector passed by caller
    public interface RecogniseByteArray {
        Detections recognise(byte[] b, int w, int h, int rotation,Bitmap b1);

    }
    RecogniseByteArray d;

    public ConfigViaSocket(FragmentActivity activity, RecogniseByteArray d){ // {
        this.activity = activity;
        this.d = d;
        Thread socketServerThread = new Thread(new SocketServerThread());
        Logger.addln("ConfigViaSocket Server running at "+getIpAddress() + ":" + getPort());
        socketServerThread.start();
    }

    public int getPort() {
        return socketServerPORT;
    }

    public String getIpAddress() {
        String ip = "";
        int count = 0;

        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface
                    .getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces
                        .nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface
                        .getInetAddresses();

                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress
                            .nextElement();

                    if (inetAddress.isSiteLocalAddress()) {
                        count++;
                        ip += inetAddress.getHostAddress();
                    }
                }

            }

            if (count==0) {ip += "(no wireless connection) "; }

        } catch (SocketException e) {
            Logger.addln("\nWARN ConfigViaSocket: "+e.getMessage());
            ip += "\nWARN ConfigViaSocket: " + e.toString() + "\n";
        }
        return ip;
    }

    public void onDestroy() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
                Logger.addln("ConfigViaSocketStopped");
            } catch (IOException e) {
                Logger.addln("\nWARN ConfigViaSocket: "+e.toString());
            }
        }
    }

    private static void SaveImage(Bitmap finalBitmap) {
        File path = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File file = new File(path, "DemDemPicture.jpg");

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

    private class SocketServerThread extends Thread {

        private Socket clientSocket;
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(activity).edit();

        private String readLine(InputStream in) {
            // Read next line
            String input=null;
            int res;
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            try {
                while ((res = in.read()) > 0) {
                    if (res == 10) break; //end of line, 10=LF
                    line.write((byte) res);
                }
                if (line.size()>0) input = line.toString("ASCII");
            } catch (Exception e) {
                Logger.addln("WARN: "+e.toString());
                return null;
            }
            return input;
        }

        class yuv_image {
            ByteBuffer yuv;
            int height;
            int width;
            Bitmap rgb;

        }
        private yuv_image readJPEG(int image_size,InputStream in,PrintStream out) {
            if (image_size > MAXLEN) {
                String err="WARN: JPEG image size " + image_size + " is too large (max " + MAXLEN + ")";
                Logger.addln(err);
                out.println(err);
                return null;
            }
            byte[] bb = new byte[MAXLEN];
            int offset = 0, res;
            try {
                while (offset < image_size
                        && (res = in.read(bb, offset, MAXLEN - offset)) >= 0) {
                    offset += res;
                }
            } catch(Exception e) {
                Logger.addln("\nERR readJPEG: " + e.toString());
                out.println("ERR: "+e.getMessage()); out.flush();
                return null;
            }
            if (offset < image_size) {
                String err="WARN: JPEG image too few bytes read (got "+offset+", expected " + image_size + ")";
                Logger.addln(err);
                out.println(err);
                return null;
            }
            Debug.println("read: " + offset);

            Bitmap b = BitmapFactory.decodeByteArray(bb, 0, image_size);
            //SaveImage(b);
            // decodes jpeg to RGB bitmap
            yuv_image yuv = new yuv_image();
            yuv.yuv=ByteBuffer.allocateDirect(MAXYUV);
            yuv.yuv.put(Transform.convertRGBtoYUV(b));
            yuv.width=b.getWidth();
            yuv.height=b.getHeight();
            yuv.rgb = b;
            return yuv;
        }

        @Override
        public void run() {
            try {
                // create ServerSocket using specified port
                serverSocket = new ServerSocket(socketServerPORT);

                while (true) {
                    clientSocket = serverSocket.accept(); //waits for an incoming request and return socket object
                    // Client established connection.
                    // Create input and output streams
                    InputStream in = clientSocket.getInputStream(); // we need to work with the raw input stream
                    PrintStream out = new PrintStream(clientSocket.getOutputStream());

                    String input = readLine(in);
                    while (input!=null) {
                        Debug.println("ConfigViaSocket received: " + input);
                        String[] parts = input.split(" ");
                        processcmd:
                        try {
                            if (parts[0].equals("SET")) {
                                // updating app preference settings
                                if (parts[1].equals("udp")) {
                                    editor.putBoolean(parts[1], Boolean.valueOf(parts[2]));
                                } else if (parts[1].equals("use_camera")) {
                                    editor.putBoolean(parts[1], Boolean.valueOf(parts[2]));
                                } else {
                                    editor.putString(parts[1], parts[2]);
                                }
                                editor.commit(); // this will cause configListener() in DetectorFrag to be called to update settings
                                out.println("ok");
                            } else if (parts[0].equals("JPG")) {
                                // upload a jpeg image for object detection
                                int image_size = Integer.valueOf(parts[1]);
                                yuv_image yuv = readJPEG(image_size,in,out);
                                if (yuv==null) {
                                    break processcmd;
                                }
                                out.println("ok");
                                // now do object detections
                                Detections detects = d.recognise(yuv.yuv.array(), yuv.width, yuv.height, 0, yuv.rgb);
                                JSONObject response = new JSONObject();
                                JSONArray detections = new JSONArray();
                                for (Recognition result : detects.results) {
                                    //System.out.println(result.toJSON());
                                    detections.put(result.toJSON());
                                }
                                response.put("results", detections);
                                response.put("client_timings",detects.client_timings);
                                response.put("server_timings",detects.server_timings);
                                Debug.println(response.toString());
                                out.println(response.toString());
                                //Logger.addln(detects.client_timings.toString());
                            } else if (parts[0].equals("JPGS")) {
                                // load multiple JPEG images and process as a batch
                                int num_images = Integer.valueOf(parts[1]);
                                Logger.addln("JPGS "+num_images);
                                int i;
                                ArrayList<yuv_image> yuvlist = new ArrayList<>();
                                for (i = 0; i < num_images; i++) {
                                    Logger.addln("reading JPEG header ...");
                                    String jpeg_header = readLine(in) ;
                                    Logger.addln(jpeg_header);
                                    String[] jpeg_parts = jpeg_header.split(" ");
                                    int image_size = Integer.valueOf(jpeg_parts[1]);
                                    yuv_image temp = readJPEG(image_size,in,out);
                                    Logger.addln("done");
                                    if (temp == null) {
                                        break processcmd;
                                    }
                                    yuvlist.add(temp);
                                    Logger.addln("reading image "+i+" size "+image_size+"("+temp.height+","+temp.width+")");
                                    out.println("ok");
                                }
                                ArrayList<Detections> responselist = new ArrayList<>();
                                for (i = 0; i < num_images; i++) {
                                    Logger.addln("doing detection " + i);
                                    Detections detects = d.recognise(yuvlist.get(i).yuv.array(), yuvlist.get(i).width, yuvlist.get(i).height, 0, yuvlist.get(i).rgb);
                                    Logger.addln("done");
                                    responselist.add(detects);
                                }
                                for (i = 0; i < num_images; i++) {
                                    JSONObject response = new JSONObject();
                                    JSONArray detections = new JSONArray();
                                    Detections detects = responselist.get(i);
                                    for (Recognition result : detects.results) {
                                        //System.out.println(result.toJSON());
                                        detections.put(result.toJSON());
                                    }

                                    response.put("results", detections);
                                    response.put("client_timings",detects.client_timings);
                                    response.put("server_timings",detects.server_timings);
                                    Debug.println(response.toString());
                                    out.println(response.toString());
                                }
                                out.println("ok");
                            }
                        } catch (Exception e) {
                            Logger.addln("\nWARN ConfigViaSocket: " + e.toString());
                            out.println("ERR: "+e.getMessage()); out.flush();
                            e.printStackTrace();
                            break;
                        }
                        out.flush();
                        input = readLine(in);
                    }
                    clientSocket.close();
                }
            } catch (IOException e) {
                Logger.addln("\nWARN ConfigViaSocket: "+e.toString());
            }
        }
    }
    /***************************************************************************************/
    // debugging
    private static class Debug {
        static void println(String s) {
            if (DEBUGGING) System.out.println("ConfigViaSocket: "+s);
        }
    }
}

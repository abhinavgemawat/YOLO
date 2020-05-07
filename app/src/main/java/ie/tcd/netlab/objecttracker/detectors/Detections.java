package ie.tcd.netlab.objecttracker.detectors;


import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ie.tcd.netlab.objecttracker.helpers.Recognition;
import ie.tcd.netlab.objecttracker.testing.Logger;

public class Detections {
    public List<Recognition> results = new ArrayList<>();  // a list of bounding boxes and labels
    public JSONObject client_timings = new JSONObject(); // client-side timing performance measurements
    public JSONObject server_timings = new JSONObject(); // server-side timing performance measurements

    public void addTiming(String name, long t) {
        try {
            this.client_timings.put(name, t/1000.0);
        } catch (Exception e) {
            Logger.addln("WARN Detections.addTiming(): "+e.toString());
        }
    }
}

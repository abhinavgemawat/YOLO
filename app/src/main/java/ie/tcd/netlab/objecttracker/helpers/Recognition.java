package ie.tcd.netlab.objecttracker.helpers;

import android.graphics.RectF;
import android.util.Size;

import org.json.JSONObject;

import ie.tcd.netlab.objecttracker.testing.Logger;

public class Recognition {
    public String title;
    public Float confidence;
    public RectF location;
    public Size image_size;

    public Recognition(String t, Float c, RectF l, Size i) {
        title=t; confidence=c; location=l; image_size=i;
    }

    public RectF getLocation() {return location;}
    public String getTitle() {return title;}
    public float getConfidence() {return confidence;}
    //public Size getImageSize() {return image_size;}
    public JSONObject toJSON() {
        JSONObject js = new JSONObject();
        try {
            js.put("title", title);
            js.put("confidence", confidence);
            js.put("bottom", location.bottom);
            js.put("top",location.top);
            js.put("left",location.left);
            js.put("right", location.right);
        } catch (Exception e) {
            Logger.addln("WARN Recognition.toJSON: "+e.getMessage());
        }
        return js;
    }
}



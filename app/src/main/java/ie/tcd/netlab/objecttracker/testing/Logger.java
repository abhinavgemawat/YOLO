package ie.tcd.netlab.objecttracker.testing;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

public class Logger {

    private  static final List<String> log = new ArrayList<>();
    private static String cur_line="";
    private static final HashMap<String,Long> timings = new HashMap<>();
    private static int verbose = 1;
    private static long time_last_changed;

    public void logger(int v) {verbose = v;}

    private static void update_timestamp() {
        time_last_changed = System.currentTimeMillis();
    }

    public static boolean log_changed(long t) {
        return (time_last_changed>t);
    }

    public static void add(String s) {
        cur_line += s;
        update_timestamp();
    }

    public static void addln(String s) {
        add(s);
        newln();
    }

    public static void newln() {
        if (cur_line==null) return;
        log.add(cur_line);
        if (verbose > 0) System.out.println(cur_line);
        cur_line = "";
    }

    public static void tick(String key) {
        timings.remove(key);
        timings.put(key, now());
    }

    public static String tock(String key) {
        Long prevtime = timings.get(key);
        if (prevtime != null) {
            timings.remove(key);
            return Long.toString(now() - prevtime);
        } else {
            return "no tick for key: "+key;
        }
    }

    public static long tockLong(String key) {
        Long prevtime = timings.get(key);
        if (prevtime != null) {
            timings.remove(key);
            return (now() - prevtime);
        } else {
            return -1;
        }
    }

    public static long now() {
        return System.nanoTime()/1000;  // in microseconds
        //return System.currentTimeMillis();
    }

    public static String tail(int num) {
        // dump last 20 lines of log to a string
        List<String> tailLog = log.subList(Math.max(0,log.size()-num),log.size());
        StringBuilder logS = new StringBuilder();
        for(String line: tailLog) {
            logS.append(line+"\n");
        }
        return logS.toString();
    }
}

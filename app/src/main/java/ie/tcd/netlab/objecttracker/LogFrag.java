package ie.tcd.netlab.objecttracker;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment ;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.Handler;

import java.lang.Runnable;

import ie.tcd.netlab.objecttracker.testing.Logger;

public class LogFrag extends Fragment {

    private final Handler handler = new Handler();
    private final Runnable updateRun = new Runnable() {
        public void run() {update(); handler.postDelayed(updateRun, POLL);}
    };
    private long last_update=0;
    private static final int NUM=25;  // number of log entries to show
    private static final long POLL=1000; // polling interval in ms for checking if log has changed

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.log_fragment, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.removeCallbacks(updateRun);
        handler.postDelayed(updateRun, 0);
    }


    @Override
    public void onPause() {
        handler.removeCallbacks(updateRun);
        super.onPause();
    }

    private void update() {
        if (!Logger.log_changed(last_update)) return;
        if (getView()!=null) {
            TextView legend = getView().findViewById(R.id.log);
            legend.setText(Logger.tail(NUM));
        }
        last_update = System.currentTimeMillis();
    }
}

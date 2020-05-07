package ie.tcd.netlab.objecttracker;


import android.view.WindowManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v4.view.ViewPager;
import android.support.design.widget.TabLayout;
import android.os.Bundle;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.Fragment;

public class MainActivity extends AppCompatActivity {

    // This class manages the user interface.  The actual work is done in:
    // * DetectorFrag implements object detection and display
    // * SettingsFrag allows detector settings etc to be changed
    // * LogFrag displays the log history

    private static final int DETECTOR_FRAG=0;
    private static final int SETTINGS_FRAG=1;
    private static final int LOG_FRAG=2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // display the main interface screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final ViewPager viewPager = findViewById(R.id.pager);
        TabLayout tabLayout = findViewById(R.id.tabDots);
        tabLayout.setupWithViewPager(viewPager, true);
        final Adapter adapter = new Adapter(getSupportFragmentManager());
        viewPager.setAdapter(adapter);

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            // This method will be invoked when a new page becomes selected.
            public void onPageSelected(int position) {
                adapter.getItem(position).onResume();
            }
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
            public void onPageScrollStateChanged(int state) {}
        });
    }

    static class Adapter extends FragmentPagerAdapter {
        Adapter(FragmentManager fm) {
            super(fm);
        }

        final Fragment detectorFrag = new DetectorFrag();
        final Fragment settingsFrag = new SettingsFrag();
        final Fragment logFrag = new LogFrag();

        @Override
        public int getCount() {
            return 3;
        }

        @Override
        public Fragment getItem(int position) {
            switch(position) {
                case DETECTOR_FRAG:
                    return detectorFrag;
                case SETTINGS_FRAG:
                    return settingsFrag;
                case LOG_FRAG:
                    return logFrag;
                default:
                    return null;
            }
        }
    }
}


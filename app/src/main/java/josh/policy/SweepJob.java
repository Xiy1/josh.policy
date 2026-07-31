package josh.policy;

import android.app.job.*;

public class SweepJob extends JobService {
    public static final int ID = 1;
    @Override public boolean onStartJob(JobParameters p) {
        BrowserSweeper.sweep(this);
        return false;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}

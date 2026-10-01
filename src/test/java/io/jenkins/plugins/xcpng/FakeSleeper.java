package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake time for {@link XcpngAgent.Sleeper}: a sleep is recorded and moves the clock, nothing blocks. A test can
 * also {@link #advance} the clock on its own, to stand in for a read that takes a while to answer.
 */
final class FakeSleeper implements XcpngAgent.Sleeper {

    final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private volatile long now = 0;

    @Override
    public void sleep(@NonNull Duration duration) {
        sleeps.add(duration);
        advance(duration);
    }

    @Override
    public long nanoTime() {
        return now;
    }

    synchronized void advance(Duration duration) {
        now += duration.toNanos();
    }
}

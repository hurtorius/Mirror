package org.hurtorius.mirror.core;

/** A two-frame jitter allowance avoids halving video cadence on 20 Hz game ticks. */
public final class FrameRateGate {
    private double credit=2;
    private long previous=-1;
    public boolean allow(long now,int fps){
        fps=Math.clamp(fps,1,60);
        if(previous>=0)credit=Math.min(Math.max(2,Math.ceil(fps/20.0)+1),credit+Math.max(0,now-previous)*fps/1000.0);
        previous=now;
        if(credit+1e-9<1)return false;
        credit=Math.max(0,credit-1);return true;
    }
}

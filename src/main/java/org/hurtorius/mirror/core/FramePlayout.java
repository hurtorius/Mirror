package org.hurtorius.mirror.core;

import java.util.ArrayDeque;

/** Smooths tick-batched network delivery without accumulating a long video backlog. */
public final class FramePlayout {
    private record Frame(byte[] jpeg,long due){}
    private final ArrayDeque<Frame> frames=new ArrayDeque<>();
    private final long delay;
    public FramePlayout(){this(50_000_000);}
    public FramePlayout(long delay){if(delay<0||delay>100_000_000)throw new IllegalArgumentException("Invalid playout delay");this.delay=delay;}
    private long sourceOrigin=-1,localOrigin,lastSequence=-1,lastDue;
    public void offer(long sequence,byte[] jpeg,long now,int fps){
        if(sequence<=lastSequence)return;lastSequence=sequence;
        long due;
        if(FrameSequence.timed(sequence)){
            long source=FrameSequence.time(sequence);
            if(sourceOrigin<0){sourceOrigin=source;localOrigin=now+delay;}
            due=localOrigin+(source-sourceOrigin);
            if(due<now-75_000_000||due>now+150_000_000){sourceOrigin=source;localOrigin=now+delay;due=localOrigin;frames.clear();}
        }else due=lastDue==0?now+delay:Math.max(now,lastDue+1_000_000_000L/Math.clamp(fps,1,60));
        if(frames.size()>=6)frames.removeFirst();frames.addLast(new Frame(jpeg,due));lastDue=due;
    }
    public byte[] poll(long now){
        while(frames.size()>1&&frames.getFirst().due<now-50_000_000)frames.removeFirst();
        if(frames.isEmpty()||frames.getFirst().due>now)return null;
        return frames.removeFirst().jpeg;
    }
    public int size(){return frames.size();}
}

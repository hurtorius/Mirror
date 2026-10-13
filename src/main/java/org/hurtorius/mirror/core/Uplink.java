package org.hurtorius.mirror.core;

import java.util.concurrent.atomic.AtomicInteger;

/** The newest picture waiting to leave for the server: at most two frames queue on the link, and a publication nobody watches sends one per second. */
public final class Uplink {
    public record Frame(String epoch,byte[] picture,long received){}
    private final AtomicInteger flight=new AtomicInteger();
    private Frame waiting;
    private String unwatched="";
    private long sent=Long.MIN_VALUE/2;
    public void offer(Frame frame){waiting=frame;}
    public void audience(String epoch,boolean watched){if(!watched)unwatched=epoch;else if(epoch.equals(unwatched))unwatched="";}
    /** The frame to send now, counted in flight until {@link #delivered()}; a frame of an ended publication is dropped. */
    public Frame take(String publishing,long now){
        Frame next=waiting;if(next==null||flight.get()>=2||next.epoch().equals(unwatched)&&now-sent<1000)return null;
        waiting=null;if(!next.epoch().equals(publishing))return null;
        sent=now;flight.incrementAndGet();return next;
    }
    /** Called from the network thread once the last part of a frame left. */
    public void delivered(){flight.decrementAndGet();}
}

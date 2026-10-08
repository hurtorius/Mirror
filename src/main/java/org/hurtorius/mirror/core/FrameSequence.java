package org.hurtorius.mirror.core;

/** Tagged monotonic sender time carried in the existing positive sequence field. */
public final class FrameSequence {
    private static final long TIMED=1L<<62,MASK=TIMED-1;
    private FrameSequence(){}
    public static long next(long previous,long now){return Math.max(previous+1,TIMED|(now&MASK));}
    public static boolean timed(long sequence){return (sequence&TIMED)!=0;}
    public static long time(long sequence){return sequence&MASK;}
}

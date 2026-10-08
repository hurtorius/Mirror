package org.hurtorius.mirror.core;

/** Shared flight clock, independent of video seeks and playlist changes. */
public final class MotionPath {
    public record Pose(double x,double y,double z,double yaw,double pitch){}
    public static Pose sample(ScreenSpec spec,long started,long now){
        if(spec.path.isEmpty())return new Pose(spec.x,spec.y,spec.z,spec.yaw,spec.pitch);
        double duration=spec.path.stream().mapToDouble(ScreenSpec.Waypoint::seconds).sum();
        double elapsed=Math.max(0,(now-started)/1000.0);
        double at=spec.pathLoop?elapsed%duration:Math.min(duration,elapsed);
        ScreenSpec.Waypoint previous=spec.pathLoop?spec.path.getLast():spec.path.getFirst();
        for(var next:spec.path){
            if(at<=next.seconds()){
                double f=at/next.seconds();f=f*f*(3-2*f);
                double angle=Math.IEEEremainder(next.yaw()-previous.yaw(),360);
                return new Pose(lerp(previous.x(),next.x(),f),lerp(previous.y(),next.y(),f),lerp(previous.z(),next.z(),f),previous.yaw()+angle*f,lerp(previous.pitch(),next.pitch(),f));
            }
            at-=next.seconds();previous=next;
        }
        var end=spec.path.getLast();return new Pose(end.x(),end.y(),end.z(),end.yaw(),end.pitch());
    }
    private static double lerp(double a,double b,double f){return a+(b-a)*f;}
}

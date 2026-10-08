package org.hurtorius.mirror.client;

import org.hurtorius.mirror.core.ScreenSpec;

/** Transient float geometry input, adapted from the merged source. Never saved or sent. */
public final class PreviewConfig {
    public enum Shape { FLAT, CONCAVE, CONVEX, PANORAMA, CIRCLE, OVAL, ROUNDED, HEXAGON, CUSTOM }
    public enum Fit { FIT, FILL, STRETCH, ORIGINAL }
    public enum EdgeStyle { NONE, BEZEL, TV, NEON, CARVED, RUNIC, FADE }
    public enum Transition { NONE, FADE, SLIDE, ZOOM, DISSOLVE, IRIS, UNFOLD, RISE }
    public enum BackFace { HIDDEN, SOLID, MIRROR, READABLE }
    public enum Facing { FIXED, EACH_VIEWER, NEAREST_PLAYER, BILLBOARD, SPECIFIC_PLAYER, ORBIT, MOUNTED }
    public enum Source { BLANK, MEDIA, WEB, SHARE, WHITEBOARD, TEXT }
    public Shape shape=Shape.FLAT;
    public Fit fit=Fit.FIT;
    public EdgeStyle edgeStyle=EdgeStyle.RUNIC;
    public Facing facing=Facing.FIXED;
    public Source source=Source.MEDIA;
    public BackFace backFace=BackFace.MIRROR;
    public float width=6,height=3.375f,curveDegrees=45,cornerRadius=.12f,edgeWidth=.06f;
    public float offsetX,offsetY=3.5f,offsetZ,yaw,pitch,roll,opacity=1,glow=.5f;
    public int tileColumns=1,tileRows=1,tileColumn,tileRow,background=0x000000,color=0x86e1db,backColor=0x000000;
    public boolean border=true,scanlines,twoSided=true,projector,enabled,followPlayer;
    public String customShape=org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV,text="";
    public int light=0xf000f0;
    public double brightness=1,contrast=1,saturation=1;
    public float edgeGlowIntensity(){return glow;}
    public static PreviewConfig from(ScreenSpec s){
        PreviewConfig p=new PreviewConfig();
        p.shape=Shape.valueOf(s.shape.name());p.fit=Fit.valueOf(s.fit.name());
        p.edgeStyle=EdgeStyle.valueOf(s.edge==ScreenSpec.Edge.TELEVISION?"TV":s.edge.name());
        p.width=(float)s.width;p.height=(float)s.height;p.edgeWidth=(float)s.edgeWidth;
        p.offsetX=(float)s.x;p.offsetY=(float)s.y;p.offsetZ=(float)s.z;
        p.yaw=(float)s.yaw;p.pitch=(float)s.pitch;p.roll=(float)s.roll;p.opacity=(float)s.opacity;p.glow=(float)s.glow;
        p.brightness=s.brightness;p.contrast=s.contrast;p.saturation=s.saturation;
        p.color=s.color;p.scanlines=s.scanlines;p.twoSided=s.doubleSided;p.projector=s.projector;
        p.backFace=s.doubleSided?(s.backMirrored?BackFace.MIRROR:BackFace.READABLE):BackFace.SOLID;
        p.enabled=s.live;p.followPlayer=s.followPlayer;p.facing=Facing.valueOf(s.facing.name());
        p.source=Source.valueOf(s.source.name());p.text=s.text;
        p.tileColumns=s.wallColumns;p.tileRows=s.wallRows;p.tileColumn=s.wallColumn;p.tileRow=s.wallRow;
        p.customShape=s.outline.stream().map(v->(v.x()*2-1)+","+(1-v.y()*2)).collect(java.util.stream.Collectors.joining(";"));
        return p;
    }
}

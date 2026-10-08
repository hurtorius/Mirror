package org.hurtorius.mirror.client;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.mirror.core.Device;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** Adapts the latest-dev assembly to the merged world's immutable draw queue. */
final class InitiatorDecorationRenderer {
    private static final Identifier FACET=Identifier.parse(InitiatorVisualProfile.CRYSTAL_TEXTURE);
    private static final Identifier WHITE=Identifier.parse("mirror:textures/white.png");
    static void append(List<MirrorRenderer.Draw> output,Device device,InitiatorLifecycle.Pose pose,Vec3 camera,int light,boolean glow){append(output,device,pose,camera,light,glow,MirrorClient.preferences.reducedMotion);}
    static void append(List<MirrorRenderer.Draw> output,Device device,InitiatorLifecycle.Pose pose,Vec3 camera,int light,boolean glow,boolean reducedMotion){
        if(pose.alpha()<.004f||pose.scale()<.004f)return;
        Vector3f origin=new Vector3f((float)(device.x()-camera.x),(float)(device.y()-camera.y),(float)(device.z()-camera.z));
        float phase=pose.spin();int color=switch(pose.mood()){
            case PREPARING->0xF1BD68;case SHARING->0x82E8B1;case LOCKED->0xB5A1ED;
            default->mixColor(0x7294A4,0x71E8ED,pose.energy());
        };
        Matrix4f center=new Matrix4f().translation(origin).translate(.5f,pose.height(),.5f);
        Matrix4f crystal=new Matrix4f(center).rotateY(phase*.65f).scale(pose.scale());
        List<MirrorRenderer.Quad> facets=new ArrayList<>();
        for(var facet:InitiatorDecorationGeometry.crystal())facets.add(transform(facet.quad(),crystal,argb(mixColor(color,facet.mixTarget(),facet.mixAmount()),.96f*pose.alpha())));
        output.add(new MirrorRenderer.Draw(facets,FACET,true,glow?0xf000f0:light,true));
        for(int index=0;index<InitiatorVisualProfile.RINGS.size();index++){
            var ring=InitiatorVisualProfile.RINGS.get(index);float staging=reducedMotion?1:index==0?pose.effects().firstRing():pose.effects().secondRing();
            float scale=pose.ringScale()*staging*(1+pose.pulse()*.18f);if(scale<.001f)continue;
            Matrix4f matrix=new Matrix4f(center).translate(0,ring.yOffset(),0).rotateY(phase*ring.spinMultiplier()).rotateX((float)Math.toRadians(ring.tiltDegrees())).scale(scale);
            int material=mixColor(mixColor(0,ring.color(),.72f),ring.color(),pose.energy());
            if(pose.mood()==InitiatorLifecycle.Mood.PREPARING||pose.mood()==InitiatorLifecycle.Mood.SHARING||pose.mood()==InitiatorLifecycle.Mood.LOCKED)material=mixColor(material,color,.35f);
            List<MirrorRenderer.Quad> quads=new ArrayList<>();for(var q:InitiatorDecorationGeometry.ring(index))quads.add(transform(q,matrix,argb(material,pose.alpha()*staging)));
            output.add(new MirrorRenderer.Draw(quads,FACET,true,light,true));
        }
        if(glow&&pose.effects().rippleAlpha()>.001f){
            List<MirrorRenderer.Quad> quads=new ArrayList<>();float radius=pose.effects().rippleRadius(),inner=Math.max(0,radius-.035f);
            Matrix4f ground=new Matrix4f().translation(origin).translate(.5f,.012f,.5f);int tint=argb(color,pose.effects().rippleAlpha());
            for(int i=0;i<48;i++){double a=i*Math.PI/24,b=(i+1)*Math.PI/24;quads.add(flat(ground,new float[][]{ground(a,inner),ground(b,inner),ground(b,radius),ground(a,radius)},tint));}
            output.add(new MirrorRenderer.Draw(quads,WHITE,true));
        }
        if(glow&&pose.effects().particleAlpha()>.001f){
            List<MirrorRenderer.Quad> quads=new ArrayList<>();
            for(int mote=0;mote<8;mote++){
                float travel=(pose.effects().particlePhase()*2+mote/8f)%1;double angle=mote*Math.PI*.763+phase*.12;
                float radius=.22f+(mote%3)*.045f,x=(float)Math.cos(angle)*radius,z=(float)Math.sin(angle)*radius,y=-.24f+travel*.74f;
                int tint=argb(color,(float)Math.sin(travel*Math.PI)*pose.effects().particleAlpha());float r=.014f;
                for(int plane=0;plane<2;plane++){float dx=plane==0?r:0,dz=plane==1?r:0;quads.add(flat(center,new float[][]{{x,y+r,z},{x+dx,y,z+dz},{x,y-r,z},{x-dx,y,z-dz}},tint));}
            }
            output.add(new MirrorRenderer.Draw(quads,WHITE,true));
        }
    }
    private static MirrorRenderer.Quad transform(InitiatorDecorationGeometry.Quad q,Matrix4f matrix,int color){
        Vector3f[] p=new Vector3f[4];float[] uv=new float[8];for(int i=0;i<4;i++){var v=q.vertices().get(i);p[i]=matrix.transformPosition(new Vector3f(v.x(),v.y(),v.z()));uv[i*2]=v.u();uv[i*2+1]=v.v();}
        var v=q.vertices().getFirst();Vector3f normal=matrix.transformDirection(new Vector3f(v.nx(),v.ny(),v.nz())).normalize();
        return new MirrorRenderer.Quad(p[0],p[1],p[2],p[3],0,0,1,1,color,uv,normal);
    }
    private static MirrorRenderer.Quad flat(Matrix4f matrix,float[][] p,int color){Vector3f[] v=new Vector3f[4];for(int i=0;i<4;i++)v[i]=matrix.transformPosition(new Vector3f(p[i]));return new MirrorRenderer.Quad(v[0],v[1],v[2],v[3],0,0,1,1,color);}
    private static float[] ground(double angle,float radius){return new float[]{(float)Math.cos(angle)*radius,0,(float)Math.sin(angle)*radius};}
    private static int argb(int color,float alpha){return (Math.clamp(Math.round(alpha*255),0,255)<<24)|(color&0xffffff);}
    private static int mixColor(int a,int b,float t){int r=Math.round(((a>>16)&255)+(((b>>16)&255)-((a>>16)&255))*t),g=Math.round(((a>>8)&255)+(((b>>8)&255)-((a>>8)&255))*t),blue=Math.round((a&255)+((b&255)-(a&255))*t);return (r<<16)|(g<<8)|blue;}
}

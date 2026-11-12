package org.hurtorius.mirror.client;

import org.joml.Vector3d;
import org.joml.Quaternionf;
import org.joml.Matrix4f;

/** Off-axis planar reflection. The near plane is the mirror, not the virtual eye. */
public final class ReflectionView {
    public record View(Vector3d eye,Quaternionf rotation,Matrix4f projection,boolean back){}
    private ReflectionView(){}
    public static View of(Vector3d center,Quaternionf surface,Vector3d viewer,float width,float height,float thickness,float distance){
        Vector3d local=new Quaternionf(surface).conjugate().transform(new Vector3d(viewer).sub(center));
        boolean back=local.z<0;double plane=back?-thickness:0,d=back?plane-local.z:local.z-plane;
        if(!Double.isFinite(d)||d<.04||width<=0||height<=0)return null;
        Vector3d eye=new Vector3d(local.x,local.y,2*plane-local.z);surface.transform(eye).add(center);
        Quaternionf rotation=new Quaternionf(surface);if(!back)rotation.rotateY((float)Math.PI);
        float near=(float)d+.008f,scale=(float)(near/d);
        float x=(float)(back?-local.x:local.x),y=(float)-local.y;
        Matrix4f projection=new Matrix4f().setFrustum((x-width/2)*scale,(x+width/2)*scale,(y-height/2)*scale,(y+height/2)*scale,near,near+Math.max(64,distance));
        return new View(eye,rotation,projection,back);
    }
}

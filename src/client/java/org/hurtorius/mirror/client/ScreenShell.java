package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.List;

/** Physical back and perimeter shared by the world display and layout preview. */
public final class ScreenShell {
    private ScreenShell(){}
    public static ScreenMesh.Mesh back(ScreenMesh.Mesh surface,float thickness){
        var triangles=new ArrayList<ScreenMesh.Triangle>(surface.triangles().size());
        for(var t:surface.triangles())triangles.add(new ScreenMesh.Triangle(back(t.c(),thickness),back(t.b(),thickness),back(t.a(),thickness)));
        return new ScreenMesh.Mesh(triangles,List.of());
    }
    private static ScreenMesh.Vertex back(ScreenMesh.Vertex v,float thickness){
        return new ScreenMesh.Vertex(v.x(),v.y(),v.z()-thickness,1-v.u(),v.v(),-v.nx(),-v.ny(),-v.nz(),v.alpha(),v.logicalX(),v.logicalY());
    }
    public static ScreenMesh.Mesh sides(ScreenMesh.Mesh surface,float thickness){
        if(thickness<=0)return new ScreenMesh.Mesh(List.of(),List.of());
        var triangles=new ArrayList<ScreenMesh.Triangle>();var boundary=surface.boundary();
        for(int i=0;i<boundary.size();i++){
            var a=boundary.get(i);var b=boundary.get((i+1)%boundary.size());
            float dx=b.x()-a.x(),dy=b.y()-a.y(),len=(float)Math.hypot(dx,dy);if(len<.00001f)continue;
            var af=wall(a,0,dy/len,-dx/len);var bf=wall(b,0,dy/len,-dx/len);
            var ar=wall(a,thickness,dy/len,-dx/len);var br=wall(b,thickness,dy/len,-dx/len);
            triangles.add(new ScreenMesh.Triangle(af,bf,br));triangles.add(new ScreenMesh.Triangle(af,br,ar));
        }
        return new ScreenMesh.Mesh(triangles,List.of());
    }
    private static ScreenMesh.Vertex wall(ScreenMesh.Vertex v,float depth,float nx,float ny){
        return new ScreenMesh.Vertex(v.x(),v.y(),v.z()-depth,0,0,nx,ny,0,1,v.logicalX(),v.logicalY());
    }
}

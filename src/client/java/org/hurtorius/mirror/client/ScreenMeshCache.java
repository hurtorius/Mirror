package org.hurtorius.mirror.client;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Shared pure geometry only; source pixels, camera positions and permissions are never cached. */
final class ScreenMeshCache {
    private record Key(String kind,PreviewConfig.Shape shape,float width,float height,float curve,float corner,
                       String polygon,PreviewConfig.Fit fit,int pixelsX,int pixelsY,float a,float b,boolean dashed,
                       ScreenTileLayout.Tile tile){}
    private static final LinkedHashMap<Key,ScreenMesh.Mesh> cache=new LinkedHashMap<>(32,.75f,true);
    private static int triangles;
    private static synchronized ScreenMesh.Mesh get(Key key,Supplier<ScreenMesh.Mesh> build){
        var existing=cache.get(key);if(existing!=null)return existing;var mesh=build.get();int count=mesh.triangles().size();
        if(count>12000)return mesh;
        while(!cache.isEmpty()&&(cache.size()>=32||triangles+count>12000)){var first=cache.entrySet().iterator();triangles-=first.next().getValue().triangles().size();first.remove();}
        cache.put(key,mesh);triangles+=count;return mesh;
    }
    private static Key key(String kind,PreviewConfig p,float w,float h,int px,int py,float a,float b,boolean dashed,ScreenTileLayout.Tile tile){return new Key(kind,p.shape,w,h,p.curveDegrees,p.cornerRadius,p.customShape,p.fit,px,py,a,b,dashed,tile);}
    static ScreenMesh.Mesh surface(PreviewConfig p,float w,float h,float fade){return get(key("surface",p,w,h,0,0,fade,0,false,null),()->ScreenMesh.surface(p.shape,w,h,p.curveDegrees,p.cornerRadius,fade,p.customShape));}
    static ScreenMesh.Mesh content(PreviewConfig p,float w,float h,int px,int py,float fade,ScreenTileLayout.Tile tile){return get(key("content",p,w,h,px,py,fade,0,false,tile),()->ScreenMesh.content(p.shape,w,h,p.curveDegrees,p.cornerRadius,p.fit,px,py,fade,tile,p.customShape).image());}
    static ScreenMesh.Mesh edge(PreviewConfig p,float w,float h,float inner,float outer,boolean dashed){return get(key("edge",p,w,h,0,0,inner,outer,dashed,null),()->ScreenMesh.edge(p.shape,w,h,p.curveDegrees,p.cornerRadius,inner,outer,dashed,p.customShape));}
    static ScreenMesh.Mesh scans(PreviewConfig p,float w,float h){return get(key("scans",p,w,h,0,0,0,0,false,null),()->ScreenMesh.scanlines(p.shape,w,h,p.curveDegrees,p.cornerRadius,96,0,p.customShape));}
    static synchronized void clear(){cache.clear();triangles=0;}
    static synchronized int triangles(){return triangles;}
}

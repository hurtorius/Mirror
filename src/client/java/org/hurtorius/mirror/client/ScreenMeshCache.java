package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One screen's pure geometry: it keeps exactly the meshes it drew during its previous frame, so screens never
 * evict each other. Source pixels, camera positions and permissions are never cached. Sizes that are still
 * easing are built without being kept.
 */
final class ScreenMeshCache {
    private record Key(String kind,PreviewConfig.Shape shape,float width,float height,float curve,float corner,
                       String polygon,PreviewConfig.Fit fit,int pixelsX,int pixelsY,float a,float b,boolean dashed,
                       ScreenTileLayout.Tile tile){}
    private Map<Key,ScreenMesh.Mesh> used=new HashMap<>(),kept=new HashMap<>();
    /** Starts this screen's frame: meshes it did not request during the previous frame are released. */
    void begin(){var previous=kept;kept=used;used=previous;used.clear();}
    private ScreenMesh.Mesh find(Key key,boolean keep){var mesh=used.get(key);if(mesh==null&&(mesh=kept.get(key))!=null&&keep)used.put(key,mesh);return mesh;}
    private ScreenMesh.Mesh keep(Key key,boolean keep,ScreenMesh.Mesh mesh){if(keep)used.put(key,mesh);return mesh;}
    private static Key key(String kind,PreviewConfig p,float w,float h,int px,int py,float a,float b,boolean dashed,ScreenTileLayout.Tile tile){return new Key(kind,p.shape,w,h,p.curveDegrees,p.cornerRadius,p.customShape,p.fit,px,py,a,b,dashed,tile);}
    ScreenMesh.Mesh surface(PreviewConfig p,float w,float h,float fade,boolean keep){var k=key("surface",p,w,h,0,0,fade,0,false,null);var mesh=find(k,keep);return mesh!=null?mesh:keep(k,keep,ScreenMesh.surface(p.shape,w,h,p.curveDegrees,p.cornerRadius,fade,p.customShape));}
    ScreenMesh.Mesh content(PreviewConfig p,float w,float h,int px,int py,float fade,ScreenTileLayout.Tile tile,boolean keep){var k=key("content",p,w,h,px,py,fade,0,false,tile);var mesh=find(k,keep);return mesh!=null?mesh:keep(k,keep,ScreenMesh.image(p.shape,w,h,p.curveDegrees,p.cornerRadius,p.fit,px,py,fade,tile,p.customShape));}
    ScreenMesh.Mesh edge(PreviewConfig p,float w,float h,float inner,float outer,boolean dashed,boolean keep){var k=key("edge",p,w,h,0,0,inner,outer,dashed,null);var mesh=find(k,keep);return mesh!=null?mesh:keep(k,keep,ScreenMesh.edge(p.shape,w,h,p.curveDegrees,p.cornerRadius,inner,outer,dashed,p.customShape));}
    ScreenMesh.Mesh back(PreviewConfig p,float w,float h,float fade,boolean keep){var k=key("back",p,w,h,0,0,fade,p.thickness,false,null);var mesh=find(k,keep);return mesh!=null?mesh:keep(k,keep,ScreenShell.back(surface(p,w,h,fade,keep),p.thickness));}
    ScreenMesh.Mesh sides(PreviewConfig p,float w,float h,boolean keep){var k=key("sides",p,w,h,0,0,p.thickness,0,false,null);var mesh=find(k,keep);return mesh!=null?mesh:keep(k,keep,ScreenShell.sides(surface(p,w,h,0,keep),p.thickness));}

    /** One edge layer: its band, dash, color, alpha relative to the screen's, and depth off the surface. */
    record EdgeLayer(float inner,float outer,boolean dashed,int rgb,float alpha,float offset){}
    /** Old-TV lines darken the unfaded surface at this alpha, just above the picture. */
    static final int SCANLINE_ALPHA=0x28;
    static final float SCANLINE_OFFSET=.01f;
    /**
     * The one edge recipe the world and the editor preview both draw: the frame, its carved or runic detail, and for neon and
     * runic edges a glow halo, transparent when glow is 0 so the preview keeps its framing.
     */
    static List<EdgeLayer> edgeLayers(PreviewConfig.EdgeStyle style,float width,int color,float glow){
        boolean frame=style==PreviewConfig.EdgeStyle.TV||style==PreviewConfig.EdgeStyle.CARVED;float thick=width*(frame?4:1);
        if(!(thick>0)||style==PreviewConfig.EdgeStyle.NONE||style==PreviewConfig.EdgeStyle.FADE)return List.of();
        List<EdgeLayer> layers=new ArrayList<>(3);
        layers.add(new EdgeLayer(0,thick,false,style==PreviewConfig.EdgeStyle.TV?0x17202a:style==PreviewConfig.EdgeStyle.CARVED?0x754a2d:color,1,.012f));
        if(frame||style==PreviewConfig.EdgeStyle.RUNIC)layers.add(new EdgeLayer(thick*.25f,thick*.6f,style!=PreviewConfig.EdgeStyle.TV,0xd6bdf7,1,.016f));
        // Faint layers start above the text pipeline's 10% alpha cutoff and fade with the screen.
        if(style==PreviewConfig.EdgeStyle.NEON||style==PreviewConfig.EdgeStyle.RUNIC)layers.add(new EdgeLayer(-thick*.5f,thick*2,false,color,glow>0?(26+(int)(38*glow))/255f:0,.005f));
        return List.copyOf(layers);
    }
}

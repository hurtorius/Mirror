package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import static org.junit.jupiter.api.Assertions.*;

class DraftPreviewGeometryTest {
    @Test void distantInitiatorDoesNotChangeScreenCenterOrScale(){
        var c=base();var renderer=new DraftPreviewGeometry();var a=renderer.project(c,FRONT,240,160,100,100);
        c.offsetX=100;c.offsetY=100;c.offsetZ=-100;var b=renderer.project(c,FRONT,240,160,100,100);
        assertEquals(120,b.center().x(),.001);assertEquals(80,b.center().y(),.001);
        assertEquals(a.pixelsPerBlock(),b.pixelsPerBlock(),.001);
    }

    @Test void rearImageHasGreaterCameraDepthThanItsBlackBacking(){
        PreviewConfig c=base();c.yaw=180;c.fit=PreviewConfig.Fit.STRETCH;
        var scene=new DraftPreviewGeometry().project(c,FRONT,240,160,100,100);
        double picture=vertices(scene,true).mapToDouble(DraftPreviewGeometry.Vertex::depth).min().orElseThrow();
        double backing=vertices(scene,false).mapToDouble(DraftPreviewGeometry.Vertex::depth).max().orElseThrow();
        assertTrue(picture>backing,"The rear picture must face the camera ahead of its backing");
    }
    @Test void nativeDeviceProjectionStaysAtTheAnchorWhenScreenMoves(){
        PreviewConfig c=base();c.offsetX=4;c.offsetY=5;c.yaw=45;c.roll=30;
        var scene=new DraftPreviewGeometry().project(c,FRONT,240,160,0,0);
        assertEquals(scene.origin(),scene.deviceProjection().project(0,0,0));
        assertNotEquals(scene.center(),scene.origin());
        assertEquals(scene.pixelsPerBlock(),scene.deviceProjection().project(1,0,0).x()-scene.origin().x(),.001);
    }
    @Test void edgeGlowInvalidatesThePreviewAndViewerGlowSuppressesOnlyTheHalo() {
        PreviewConfig c = new PreviewConfig(); c.edgeStyle = PreviewConfig.EdgeStyle.NEON;
        var preview = new DraftPreviewGeometry();
        var normal = preview.project(c, FRONT, 240, 180, 100, 100);
        assertSame(normal, preview.project(c, FRONT, 240, 180, 100, 100));
        c.glow = 0f;
        var off = preview.project(c, FRONT, 240, 180, 100, 100);
        assertNotSame(normal, off);
        assertTrue(normal.triangles().size() > off.triangles().size());
        assertEquals(normal.pixelsPerBlock(), off.pixelsPerBlock(), .001);
        c.glow = 2f;
        var stronger = preview.project(c, FRONT, 240, 180, 100, 100);
        assertEquals(normal.triangles().size(), stronger.triangles().size());
        assertNotEquals(normal.triangles(), stronger.triangles());
        var viewerOff = preview.project(c, FRONT, 240, 180, 100, 100, false);
        assertEquals(off.triangles(), viewerOff.triangles());

        assertSame(viewerOff, preview.project(c, FRONT, 240, 180, 100, 100, false));
        assertEquals(vertices(normal, true).count(), vertices(off, true).count());
    }
    private static final DraftPreviewGeometry.Camera FRONT = new DraftPreviewGeometry.Camera(0,0);
    private static Stream<DraftPreviewGeometry.Vertex> vertices(DraftPreviewGeometry.Scene scene, boolean textured) {
        return scene.triangles().stream().filter(t->t.textured()==textured).flatMap(t->Stream.of(t.a(),t.b(),t.c()));
    }
    private static PreviewConfig base() {
        PreviewConfig c=new PreviewConfig();c.border=false;c.width=4;c.height=2;c.offsetY=3;return c;
    }
    @ParameterizedTest @EnumSource(PreviewConfig.Shape.class)
    void everyActualSurfaceProjectsWithFiniteBoundedClippedUVs(PreviewConfig.Shape shape) {
        PreviewConfig c=base();c.shape=shape;c.curveDegrees=300;c.scanlines=true;c.border=true;c.edgeStyle=PreviewConfig.EdgeStyle.CARVED;
        var scene=new DraftPreviewGeometry().project(c,DraftPreviewGeometry.Camera.reset(),240,160,1920,1080);
        assertFalse(scene.triangles().isEmpty());assertTrue(scene.triangles().size()<=DraftPreviewGeometry.MAX_TRIANGLES);
        assertTrue(vertices(scene,true).count()>0);
        for(var t:scene.triangles())for(var v:List.of(t.a(),t.b(),t.c())) {
            assertTrue(Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.depth()));
            assertTrue(v.x()>=0&&v.x()<=240&&v.y()>=0&&v.y()<=160,"shape="+shape+" vertex="+v);
            if(t.textured())assertTrue(v.u()>=-.00001&&v.u()<=1.00001&&v.v()>=-.00001&&v.v()<=1.00001);
        }
        for(int i=1;i<scene.triangles().size();i++)assertTrue(scene.triangles().get(i-1).depth()<=scene.triangles().get(i).depth());
    }
    @Test void circleIsClippedMeshNotBoundingRectangle() {
        PreviewConfig c=base();c.shape=PreviewConfig.Shape.CIRCLE;c.fit=PreviewConfig.Fit.STRETCH;
        var scene=new DraftPreviewGeometry().project(c,FRONT,200,160,100,100);
        assertTrue(scene.triangles().stream().filter(DraftPreviewGeometry.Triangle::textured).count()>8);
        float radius=scene.pixelsPerBlock();
        vertices(scene,true).forEach(v->assertTrue(Math.hypot(v.x()-scene.center().x(),v.y()-scene.center().y())<=radius+.001));
        assertTrue(vertices(scene,true).anyMatch(v->Math.abs(v.x()-scene.center().x())<.1&&Math.abs(v.y()-scene.center().y())>radius*.99));
    }
    @ParameterizedTest @EnumSource(PreviewConfig.Fit.class)
    void previewPreservesRealMeshTextureFit(PreviewConfig.Fit fit) {
        PreviewConfig c=base();c.fit=fit;
        var scene=new DraftPreviewGeometry().project(c,FRONT,220,160,100,100);
        var mesh=ScreenMesh.content(c.shape,c.width,c.height,c.curveDegrees,c.cornerRadius,fit,100,100,0,c.customShape).image();
        assertEquals(mesh.triangles().size(),scene.triangles().stream().filter(DraftPreviewGeometry.Triangle::textured).count());
        List<String> expected=mesh.triangles().stream().flatMap(t->Stream.of(t.a(),t.b(),t.c())).map(v->v.u()+","+v.v()).sorted().toList();
        List<String> actual=vertices(scene,true).map(v->v.u()+","+v.v()).sorted().toList();
        assertEquals(expected,actual);
    }
    @Test void textureAbsenceClearsImageGeometryAndLaterSourceUsesNewDimensions() {
        PreviewConfig c=base();c.fit=PreviewConfig.Fit.FIT;
        DraftPreviewGeometry preview=new DraftPreviewGeometry();
        var landscape=preview.project(c,FRONT,200,160,1600,900);
        var empty=preview.project(c,FRONT,200,160,0,0);
        var portrait=preview.project(c,FRONT,200,160,900,1600);
        assertTrue(vertices(landscape,true).count()>0);assertEquals(0,vertices(empty,true).count());
        double wide=vertices(landscape,true).mapToDouble(DraftPreviewGeometry.Vertex::x).max().orElseThrow()-vertices(landscape,true).mapToDouble(DraftPreviewGeometry.Vertex::x).min().orElseThrow();
        double narrow=vertices(portrait,true).mapToDouble(DraftPreviewGeometry.Vertex::x).max().orElseThrow()-vertices(portrait,true).mapToDouble(DraftPreviewGeometry.Vertex::x).min().orElseThrow();
        assertTrue(wide>narrow*2);assertEquals(landscape.pixelsPerBlock(),empty.pixelsPerBlock(),.001);
    }
    @Test void offsetAndSizeUseWorldBlocksRelativeToInitiatorOrigin() {
        PreviewConfig c=base();c.offsetX=2;c.offsetY=3;
        var scene=new DraftPreviewGeometry().project(c,FRONT,260,200,100,100);
        assertEquals(2,(scene.center().x()-scene.origin().x())/scene.pixelsPerBlock(),.00001);
        assertEquals(-3,(scene.center().y()-scene.origin().y())/scene.pixelsPerBlock(),.00001);
        var world=DraftPreviewGeometry.world(new DraftPreviewGeometry.Point(0,0,0),c);
        assertEquals(2,world.x());assertEquals(3,world.y());assertEquals(0,world.z());
        assertEquals(19,scene.grid().size());
    }
    @Test void rotationMatchesWorldQuaternionOrder() {
        PreviewConfig c=base();c.yaw=73;c.pitch=-24;c.roll=31;
        var source=new DraftPreviewGeometry.Point(1.2f,-.7f,2.6f);
        var actual=DraftPreviewGeometry.rotate(source,c);
        var expected=new Quaternionf().rotateY((float)Math.toRadians(c.yaw)).rotateX((float)Math.toRadians(c.pitch)).rotateZ((float)Math.toRadians(c.roll)).transform(new Vector3f(source.x(),source.y(),source.z()));
        assertEquals(expected.x,actual.x(),.000001);assertEquals(expected.y,actual.y(),.000001);assertEquals(expected.z,actual.z(),.000001);
    }
    @Test void backVisibilityMatchesOneSidedHiddenSolidAndMirrorModes() {
        PreviewConfig c=base();c.yaw=180;
        DraftPreviewGeometry preview=new DraftPreviewGeometry();
        c.twoSided=false;var one=preview.project(c,FRONT,200,160,100,100);assertEquals(0,vertices(one,true).count());assertFalse(one.text().visible());
        c.twoSided=true;c.backFace=PreviewConfig.BackFace.MIRROR;var mirror=preview.project(c,FRONT,200,160,100,100);assertTrue(vertices(mirror,true).count()>0);assertTrue(mirror.text().visible());
        c.backFace=PreviewConfig.BackFace.SOLID;assertEquals(0,vertices(preview.project(c,FRONT,200,160,100,100),true).count());
        c.backFace=PreviewConfig.BackFace.HIDDEN;var hidden=preview.project(c,FRONT,200,160,100,100);assertEquals(0,hidden.triangles().size());assertFalse(hidden.text().visible());
        c.backFace=PreviewConfig.BackFace.MIRROR;c.projector=true;assertEquals(0,vertices(preview.project(c,FRONT,200,160,100,100),true).count());
    }
    @Test void panoramaHasActualDepthAndSideViewChangesSilhouette() {
        PreviewConfig c=base();c.shape=PreviewConfig.Shape.PANORAMA;c.curveDegrees=240;c.fit=PreviewConfig.Fit.STRETCH;
        DraftPreviewGeometry preview=new DraftPreviewGeometry();
        var front=preview.project(c,FRONT,240,160,100,100);
        var side=preview.project(c,new DraftPreviewGeometry.Camera(90,18),240,160,100,100);
        assertTrue(vertices(front,true).mapToDouble(DraftPreviewGeometry.Vertex::depth).max().orElseThrow()-vertices(front,true).mapToDouble(DraftPreviewGeometry.Vertex::depth).min().orElseThrow()>1);
        assertNotEquals(front.triangles(),side.triangles());
    }
    @Test void opacityDoesNotChangeSceneScale() {
        PreviewConfig c=base();c.width=16;c.height=12;c.offsetX=16;
        DraftPreviewGeometry preview=new DraftPreviewGeometry();
        float scale=preview.project(c,FRONT,200,160,100,100).pixelsPerBlock();
        c.opacity=0;
        assertEquals(scale,preview.project(c,FRONT,200,160,0,0).pixelsPerBlock(),.01);
    }
    @Test void extremeDetailIsBoundedForEveryShapeAndFrame() {
        for(var shape:PreviewConfig.Shape.values())for(var edge:PreviewConfig.EdgeStyle.values()) {
            PreviewConfig c=base();c.shape=shape;c.edgeStyle=edge;c.border=true;c.edgeWidth=.25f;c.scanlines=true;c.curveDegrees=300;c.width=16;c.height=12;
            var scene=new DraftPreviewGeometry().project(c,DraftPreviewGeometry.Camera.reset(),240,160,1920,1080);
            assertTrue(scene.triangles().size()<=DraftPreviewGeometry.MAX_TRIANGLES);
            int runs=0;boolean last=false;for(var t:scene.triangles()){if(runs==0||t.textured()!=last)runs++;last=t.textured();}
            assertTrue(runs <= 512,"Unbounded GUI layering: "+shape+"/"+edge+" has "+runs+" material runs");
        }
    }
    @Test void projectedCacheRetainsOnlyGeometryAndInvalidatesOnPlacementAndSourceSize() {
        PreviewConfig c=base();DraftPreviewGeometry preview=new DraftPreviewGeometry();
        var first=preview.project(c,FRONT,200,160,100,100);
        c.text="Another private source";
        assertSame(first,preview.project(c,FRONT,200,160,100,100));
        c.offsetX=1;
        assertNotSame(first,preview.project(c,FRONT,200,160,100,100));
        assertEquals(0,vertices(preview.project(c,FRONT,200,160,0,0),true).count());
    }
    @Test void tinyViewportAndNonfiniteCameraStayFinite() {
        var scene=new DraftPreviewGeometry().project(base(),new DraftPreviewGeometry.Camera(Float.NaN,Float.POSITIVE_INFINITY),0,0,0,0);
        assertTrue(Float.isFinite(scene.pixelsPerBlock()));assertFalse(scene.limited());
    }

    @Test void zoomIsBoundedCachedAndCentersTheEditedScreenWithoutChangingDraft() {
        var preview=new DraftPreviewGeometry();PreviewConfig c=base();c.offsetX=12;c.offsetZ=-4;
        String unchanged=org.hurtorius.mirror.core.ScreenSpec.JSON.toJson(c);
        var fit=preview.project(c,FRONT,240,160,100,100);
        var camera=new DraftPreviewGeometry.Camera(0,0,2);
        var zoomed=preview.project(c,camera,240,160,100,100);
        assertEquals(fit.pixelsPerBlock()*2,zoomed.pixelsPerBlock(),.001);
        assertEquals(120,zoomed.center().x(),.001);assertEquals(80,zoomed.center().y(),.001);
        assertSame(zoomed,preview.project(c,camera,240,160,100,100));
        assertNotSame(zoomed,preview.project(c,camera,300,200,100,100));
        assertEquals(unchanged,org.hurtorius.mirror.core.ScreenSpec.JSON.toJson(c));
        assertEquals(.5f,new DraftPreviewGeometry.Camera(0,0,-10).zoom());
        assertEquals(4,new DraftPreviewGeometry.Camera(0,0,100).zoom());
        assertEquals(1,new DraftPreviewGeometry.Camera(0,0,Float.NaN).zoom());
    }

    @Test void editedGeometryPlacementAndAppearanceAlwaysInvalidateProjectedScene() {
        var preview=new DraftPreviewGeometry();PreviewConfig c=base();
        List<java.util.function.Consumer<PreviewConfig>> edits=List.of(v->v.width=7,v->v.height=4,
                v->v.shape=PreviewConfig.Shape.PANORAMA,v->v.curveDegrees=210,v->v.offsetX=3,
                v->v.offsetY=5,v->v.offsetZ=-2,v->v.yaw=55,v->v.pitch=20,v->v.roll=12,
                v->v.fit=PreviewConfig.Fit.FILL,v->v.opacity=.5f,v->v.background=0x456789,
                v->{v.border=true;v.edgeStyle=PreviewConfig.EdgeStyle.CARVED;},v->v.scanlines=true);
        var last=preview.project(c,FRONT,240,160,100,100);
        for(var edit:edits){
            edit.accept(c);var next=preview.project(c,FRONT,240,160,100,100);
            assertNotSame(last,next);assertSame(next,preview.project(c,FRONT,240,160,100,100));last=next;
        }
    }
}

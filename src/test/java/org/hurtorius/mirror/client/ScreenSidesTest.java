package org.hurtorius.mirror.client;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenSidesTest {
    @Test void newDevicesAreFrontOnlyAndLegacyTwoSidedScreensBecomeReadable(){
        var fresh=new ScreenSpec();assertFalse(fresh.doubleSided);assertEquals(PreviewConfig.BackFace.SOLID,PreviewConfig.from(fresh).backFace);
        var old=ScreenSpec.read("{\"doubleSided\":true}");assertEquals(PreviewConfig.BackFace.READABLE,PreviewConfig.from(old).backFace);
        old.backMirrored=true;assertEquals(PreviewConfig.BackFace.MIRROR,PreviewConfig.from(old).backFace);
    }
    @Test void textureCoordinatesAreOnlyReflectedOnReadableRearFaces(){
        assertEquals(.2f,ScreenSurfaceSide.imageU(.2f,1,true));assertEquals(.8f,ScreenSurfaceSide.imageU(.2f,-1,true));assertEquals(.2f,ScreenSurfaceSide.imageU(.2f,-1,false));
    }
    @Test void frontOnlyRearHasBlackBackingWithoutPictureOrText(){
        var spec=new ScreenSpec();spec.yaw=180;spec.text="Front content";
        var scene=new DraftPreviewGeometry().project(PreviewConfig.from(spec),new DraftPreviewGeometry.Camera(0,0),300,200,1280,720);
        assertFalse(scene.triangles().isEmpty());assertFalse(scene.text().visible());
        for(var triangle:scene.triangles()){
            assertFalse(triangle.textured());
            assertEquals(0xff000000,triangle.a().color());
            assertEquals(0xff000000,triangle.b().color());
            assertEquals(0xff000000,triangle.c().color());
        }
    }
    @Test void previewAndWorldUseTheSameRearUvPolicy(){
        var spec=new ScreenSpec();spec.doubleSided=true;spec.yaw=180;spec.fit=ScreenSpec.Fit.STRETCH;
        var config=PreviewConfig.from(spec);var camera=new DraftPreviewGeometry.Camera(0,0);var geometry=new DraftPreviewGeometry();
        var readable=geometry.project(config,camera,300,200,1280,720);
        config.backFace=PreviewConfig.BackFace.MIRROR;var mirrored=geometry.project(config,camera,300,200,1280,720);
        var a=readable.triangles().stream().filter(DraftPreviewGeometry.Triangle::textured).toList();var b=mirrored.triangles().stream().filter(DraftPreviewGeometry.Triangle::textured).toList();
        assertFalse(a.isEmpty());assertEquals(a.size(),b.size());
        for(int i=0;i<a.size();i++){assertEquals(1-b.get(i).a().u(),a.get(i).a().u(),.00001);assertEquals(b.get(i).a().x(),a.get(i).a().x());}
        config.twoSided=false;assertTrue(geometry.project(config,camera,300,200,1280,720).triangles().isEmpty());
    }
}

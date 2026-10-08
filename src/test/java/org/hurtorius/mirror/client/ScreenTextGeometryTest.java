package org.hurtorius.mirror.client;

import org.hurtorius.mirror.core.PolygonShape;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenTextGeometryTest {
    private static final int COLOR=0x80BBDDFF,LIGHT=0x00A000B0;
    private static ScreenMesh.Mesh surface(PreviewConfig.Shape shape,float fade){
        return ScreenMesh.surface(shape,4,2,150,.3f,fade,PolygonShape.DEFAULT_CSV);
    }
    private static ScreenTextGeometry.Input input(float x,float y){
        return new ScreenTextGeometry.Input(x,y,0,(x+2)/4,(1-y)/2,COLOR,LIGHT);
    }
    private static List<ScreenTextGeometry.Triangle> cover(ScreenTextGeometry.Projector p){
        return p.project(input(-2,1),input(-2,-1),input(2,-1),input(2,1));
    }
    private static Stream<ScreenTextGeometry.Vertex> vertices(List<ScreenTextGeometry.Triangle> triangles){
        return triangles.stream().flatMap(t->Stream.of(t.a(),t.b(),t.c()));
    }
    private static double area(List<ScreenTextGeometry.Triangle> triangles){
        return triangles.stream().mapToDouble(t->Math.abs((t.b().logicalX()-t.a().logicalX())*(t.c().logicalY()-t.a().logicalY())
                -(t.b().logicalY()-t.a().logicalY())*(t.c().logicalX()-t.a().logicalX()))/2).sum();
    }
    private static double meshArea(ScreenMesh.Mesh mesh){
        return mesh.triangles().stream().mapToDouble(t->Math.abs((t.b().logicalX()-t.a().logicalX())*(t.c().logicalY()-t.a().logicalY())
                -(t.b().logicalY()-t.a().logicalY())*(t.c().logicalX()-t.a().logicalX()))/2).sum();
    }

    @Test void fullNativeQuadMatchesEverySurfaceAndPreservesUvColorAndLight(){
        for(var shape:PreviewConfig.Shape.values()){
            var mesh=surface(shape,0);var projector=new ScreenTextGeometry.Projector(mesh,ScreenTextGeometry.Budget.frame());
            var result=cover(projector);assertFalse(projector.failed(),shape.name());
            assertEquals(meshArea(mesh),area(result),.00002,shape.name());
            vertices(result).forEach(v->{
                assertEquals((v.logicalX()+2)/4,v.u(),.00001);assertEquals((1-v.logicalY())/2,v.v(),.00001);
                assertEquals(COLOR,v.color());assertEquals(LIGHT,v.light());
                assertTrue(Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z()));
            });
        }
    }
    @Test void irisAndDissolveHaveExactSurfaceCoverageRatherThanAlphaFallback(){
        for(var effect:List.of(PreviewConfig.Transition.IRIS,PreviewConfig.Transition.DISSOLVE))
            for(var shape:PreviewConfig.Shape.values())for(float progress:new float[]{0,.13f,.5f,.87f,1}){
                var mask=ScreenMesh.transition(surface(shape,0),shape,4,2,150,.3f,
                        TransitionMath.appearance(effect,progress,false),false,.2f);
                var projector=new ScreenTextGeometry.Projector(mask,ScreenTextGeometry.Budget.frame());
                var output=cover(projector);assertFalse(projector.failed(),shape+" "+effect+" "+progress);
                assertEquals(meshArea(mask),area(output),.00003,shape+" "+effect+" "+progress);
            }
    }
    @Test void offsetPolygonClipsNativeOverhangsWithoutReflectingTheSilhouette(){
        String polygon=".2,-1;1,-1;1,1;.2,1";
        var mesh=ScreenMesh.surface(PreviewConfig.Shape.CUSTOM,4,2,150,.3f,0,polygon);
        var projector=new ScreenTextGeometry.Projector(mesh,ScreenTextGeometry.Budget.frame());
        var output=cover(projector);assertFalse(output.isEmpty());
        assertEquals(3.2,area(output),.00001);
        vertices(output).forEach(v->{assertTrue(v.logicalX()>=.39999f);assertTrue(v.logicalX()<=2.00001f);});
    }
    @Test void alphaFeathersAndRearDepthPreserveNativeAttributes(){
        var projector=new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.ROUNDED,.2f),ScreenTextGeometry.Budget.frame());
        var output=cover(projector);assertTrue(vertices(output).anyMatch(v->(v.color()>>>24)<128));
        vertices(output).forEach(v->{
            assertEquals(COLOR&0xFFFFFF,v.color()&0xFFFFFF);assertEquals(LIGHT,v.light());
            var back=ScreenTextGeometry.back(v);assertEquals(v.u(),back.u());assertEquals(v.v(),back.v());
            assertEquals(v.color(),back.color());assertEquals(v.light(),back.light());
            assertEquals(v.x()-2*v.nx()*v.offset(),back.x(),.000001);
            var again=ScreenTextGeometry.back(back);assertEquals(v.x(),again.x(),.000001);assertEquals(v.z(),again.z(),.000001);
        });
    }
    @Test void curvesConformToSurfaceInsteadOfRemainingOnOneCentralPlane(){
        var output=cover(new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.CONCAVE,0),ScreenTextGeometry.Budget.frame()));
        float min=(float)vertices(output).mapToDouble(ScreenTextGeometry.Vertex::z).min().orElseThrow();
        float max=(float)vertices(output).mapToDouble(ScreenTextGeometry.Vertex::z).max().orElseThrow();
        assertTrue(max-min>.8f);
    }
    @Test void sharedFrameAndPanelBudgetsFailClosedAndNeverOverspend(){
        var frame=new ScreenTextGeometry.Budget(8,1,20_000);
        var first=new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.FLAT,0),frame);
        assertTrue(cover(first).isEmpty());assertTrue(first.failed());assertEquals(0,frame.remainingTriangles());
        var second=new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.FLAT,0),frame);
        assertTrue(cover(second).isEmpty());assertTrue(second.failed());assertEquals(0,frame.remainingTriangles());
        var work=new ScreenTextGeometry.Budget(100,100,1);
        var tooLittle=new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.CIRCLE,0),work);
        assertTrue(tooLittle.failed());assertEquals(0,work.remainingWork());assertTrue(cover(tooLittle).isEmpty());
        var inputs=new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.FLAT,0),new ScreenTextGeometry.Budget(1,100,10000));
        assertTrue(inputs.vertex());assertFalse(inputs.vertex());assertTrue(cover(inputs).isEmpty());
    }
    @Test void malformedOrOutOfViewGeometryCannotLeakOrGrowOutput(){
        var p=new ScreenTextGeometry.Projector(surface(PreviewConfig.Shape.FLAT,0),ScreenTextGeometry.Budget.frame());
        assertTrue(p.project(input(10,10),input(10,11),input(11,11),input(11,10)).isEmpty());assertFalse(p.failed());
        assertTrue(p.project(input(Float.NaN,0),input(0,0),input(1,0),input(1,1)).isEmpty());assertTrue(p.failed());
    }
    @Test void ordinaryMaximumWrappedTextFitsBudgetsOnEveryShapeAndMask(){
        for(var shape:PreviewConfig.Shape.values())for(var effect:List.of(PreviewConfig.Transition.NONE,PreviewConfig.Transition.IRIS,PreviewConfig.Transition.DISSOLVE)){
            var mesh=ScreenMesh.transition(surface(shape,0),shape,4,2,150,.3f,
                    TransitionMath.appearance(effect,.5f,false),false,.2f);
            var layout=ScreenTextLayout.of(shape,4,2,150,.3f,PreviewConfig.Fit.FIT,PolygonShape.DEFAULT_CSV,
                    new ScreenTileLayout.Tile(1,1,0,0),318,48);
            var p=new ScreenTextGeometry.Projector(mesh,ScreenTextGeometry.Budget.frame());
            for(int line=0;line<48&&!p.failed();line++)for(int character=0;character<53&&!p.failed();character++){
                float x=character*6-159,y=line*11-264;
                p.project(new ScreenTextGeometry.Input(layout.x(x),layout.y(y),0,0,0,COLOR,LIGHT),
                        new ScreenTextGeometry.Input(layout.x(x),layout.y(y+8),0,0,1,COLOR,LIGHT),
                        new ScreenTextGeometry.Input(layout.x(x+5),layout.y(y+8),0,1,1,COLOR,LIGHT),
                        new ScreenTextGeometry.Input(layout.x(x+5),layout.y(y),0,1,0,COLOR,LIGHT));
            }
            assertFalse(p.failed(),shape+" "+effect);
        }
    }
    @Test void denseBoldTextKeepsItsNativeGeometry(){
        for(var shape:List.of(PreviewConfig.Shape.CIRCLE,PreviewConfig.Shape.ROUNDED,PreviewConfig.Shape.PANORAMA)){
            var mesh=ScreenMesh.transition(surface(shape,0),shape,4,2,150,.3f,
                    TransitionMath.appearance(PreviewConfig.Transition.IRIS,.5f,false),false,.2f);
            var layout=ScreenTextLayout.of(shape,4,2,150,.3f,PreviewConfig.Fit.FIT,PolygonShape.DEFAULT_CSV,
                    new ScreenTileLayout.Tile(1,1,0,0),318,48);
            var budget=ScreenTextGeometry.Budget.frame();
            var p=new ScreenTextGeometry.Projector(mesh,budget);
            for(int line=0;line<48&&!p.failed();line++)for(int character=0;character<53&&!p.failed();character++)
                for(int style=0;style<2&&!p.failed();style++){
                    float x=character*6-159+(style==1?.7f:0),y=line*11-264;
                    float height=8,width=5;
                    p.project(new ScreenTextGeometry.Input(layout.x(x),layout.y(y),0,0,0,COLOR,LIGHT),
                            new ScreenTextGeometry.Input(layout.x(x),layout.y(y+height),0,0,1,COLOR,LIGHT),
                            new ScreenTextGeometry.Input(layout.x(x+width),layout.y(y+height),0,1,1,COLOR,LIGHT),
                            new ScreenTextGeometry.Input(layout.x(x+width),layout.y(y),0,1,0,COLOR,LIGHT));
                }
            assertFalse(p.failed(),shape+" work="+(ScreenTextGeometry.FRAME_WORK-budget.remainingWork())
                    +" triangles="+(ScreenTextGeometry.FRAME_TRIANGLES-budget.remainingTriangles()));
        }
    }
}

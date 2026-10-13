package org.hurtorius.mirror.client;

/** Which side of a surface triangle faces the viewer, in the screen's own coordinates. */
final class ScreenSurfaceSide {
    static float imageU(float u,double side,boolean readableBack){return side<0&&readableBack?1-u:u;}
    static float side(ScreenMesh.Triangle triangle,float x,float y,float z){
        var a=triangle.a();var b=triangle.b();var c=triangle.c();
        float nx=a.nx()+b.nx()+c.nx(),ny=a.ny()+b.ny()+c.ny(),nz=a.nz()+b.nz()+c.nz();
        return (x-(a.x()+b.x()+c.x())/3)*nx+(y-(a.y()+b.y()+c.y())/3)*ny+(z-(a.z()+b.z()+c.z())/3)*nz>=0?1:-1;
    }
}

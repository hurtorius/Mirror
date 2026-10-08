package org.hurtorius.mirror.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Static base from the recovered dev artwork. The crystal and rings use their authored meshes. */
public final class InitiatorModel {
    public record Point(float x,float y,float z) {}
    public record Face(List<Point> points,String texture,float[] uv,float shade) {}
    public record Bounds(float x0,float y0,float z0,float x1,float y1,float z1) {}
    public record Model(List<Face> faces,List<Bounds> bounds) {}
    public static final Model MODEL=load();
    private static Model load(){
        try(var in=InitiatorModel.class.getResourceAsStream("/assets/mirror/models/block/initiator.json")){
            if(in==null)throw new IOException("Missing Initiator model");
            return read(JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject());
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    public static Model read(JsonObject model){
        List<Face> faces=new ArrayList<>();List<Bounds> bounds=new ArrayList<>();
        for(JsonElement entry:model.getAsJsonArray("elements")){
            JsonObject part=entry.getAsJsonObject();float[] f=vec(part.getAsJsonArray("from")),t=vec(part.getAsJsonArray("to"));
            float x=f[0],y=f[1],z=f[2],X=t[0],Y=t[1],Z=t[2];
            List<Point> all=new ArrayList<>();
            for(var e:part.getAsJsonObject("faces").entrySet()){
                float[][] corners=switch(e.getKey()){
                    case "down"->new float[][]{{x,y,Z},{x,y,z},{X,y,z},{X,y,Z}};
                    case "up"->new float[][]{{x,Y,z},{x,Y,Z},{X,Y,Z},{X,Y,z}};
                    case "north"->new float[][]{{X,Y,z},{X,y,z},{x,y,z},{x,Y,z}};
                    case "south"->new float[][]{{x,Y,Z},{x,y,Z},{X,y,Z},{X,Y,Z}};
                    case "west"->new float[][]{{x,Y,z},{x,y,z},{x,y,Z},{x,Y,Z}};
                    case "east"->new float[][]{{X,Y,Z},{X,y,Z},{X,y,z},{X,Y,z}};
                    default->throw new IllegalArgumentException("Unknown model face");
                };
                float[] rect=switch(e.getKey()){
                    case "down"->new float[]{x,16-Z,X,16-z};case "up"->new float[]{x,z,X,Z};
                    case "north"->new float[]{16-X,16-Y,16-x,16-y};case "south"->new float[]{x,16-Y,X,16-y};
                    case "west"->new float[]{z,16-Y,Z,16-y};default->new float[]{16-Z,16-Y,16-z,16-y};
                };
                JsonObject face=e.getValue().getAsJsonObject();if(face.has("uv"))rect=vec(face.getAsJsonArray("uv"));
                float[] uv={rect[0]/16,rect[1]/16,rect[0]/16,rect[3]/16,rect[2]/16,rect[3]/16,rect[2]/16,rect[1]/16};
                List<Point> points=new ArrayList<>();for(float[] c:corners)points.add(rotate(c,part));all.addAll(points);
                String texture=face.get("texture").getAsString();Set<String> visited=new HashSet<>();
                while(texture.startsWith("#")){if(!visited.add(texture))throw new IllegalArgumentException("Texture cycle");texture=model.getAsJsonObject("textures").get(texture.substring(1)).getAsString();}
                float shade=switch(e.getKey()){case "up"->1;case "down"->.5f;case "east","west"->.6f;default->.8f;};
                faces.add(new Face(List.copyOf(points),texture,uv,shade));
            }
            bounds.add(new Bounds(min(all,0),min(all,1),min(all,2),max(all,0),max(all,1),max(all,2)));
        }
        return new Model(List.copyOf(faces),List.copyOf(bounds));
    }
    private static float[] vec(JsonArray a){float[] v=new float[a.size()];for(int i=0;i<v.length;i++)v[i]=a.get(i).getAsFloat();return v;}
    private static Point rotate(float[] p,JsonObject part){
        double x=p[0],y=p[1],z=p[2];
        if(part.has("rotation")){
            JsonObject r=part.getAsJsonObject("rotation");float[] o=vec(r.getAsJsonArray("origin"));x-=o[0];y-=o[1];z-=o[2];
            double a=Math.toRadians(r.get("angle").getAsDouble()),c=Math.cos(a),s=Math.sin(a),u=x,v=y,w=z;
            switch(r.get("axis").getAsString()){case "x"->{y=v*c-w*s;z=v*s+w*c;}case "y"->{x=u*c+w*s;z=-u*s+w*c;}case "z"->{x=u*c-v*s;y=u*s+v*c;}}
            x+=o[0];y+=o[1];z+=o[2];
        }
        return new Point((float)x/16,(float)y/16,(float)z/16);
    }
    private static float coordinate(Point p,int i){return i==0?p.x:i==1?p.y:p.z;}
    private static float min(List<Point> p,int i){return (float)p.stream().mapToDouble(v->coordinate(v,i)).min().orElseThrow();}
    private static float max(List<Point> p,int i){return (float)p.stream().mapToDouble(v->coordinate(v,i)).max().orElseThrow();}
}

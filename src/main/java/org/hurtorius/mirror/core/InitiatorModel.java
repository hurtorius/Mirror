package org.hurtorius.mirror.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Static base of the Initiator block. The crystal and rings use their own meshes. */
public final class InitiatorModel {
    public record Point(float x,float y,float z) {}
    public record Face(List<Point> points,String texture,float[] uv) {}
    public record Model(List<Face> faces) {}
    public static final Model MODEL=load();
    private static Model load(){
        try(var in=InitiatorModel.class.getResourceAsStream("/assets/mirror/models/block/initiator.json")){
            if(in==null)throw new IOException("Missing Initiator model");
            return read(JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject());
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    public static Model read(JsonObject model){
        List<Face> faces=new ArrayList<>();
        for(JsonElement entry:model.getAsJsonArray("elements")){
            JsonObject part=entry.getAsJsonObject();float[] f=vec(part.getAsJsonArray("from")),t=vec(part.getAsJsonArray("to"));
            float x=f[0],y=f[1],z=f[2],X=t[0],Y=t[1],Z=t[2];
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
                List<Point> points=new ArrayList<>();for(float[] c:corners)points.add(rotate(c,part));
                String texture=face.get("texture").getAsString();Set<String> visited=new HashSet<>();
                while(texture.startsWith("#")){if(!visited.add(texture))throw new IllegalArgumentException("Texture cycle");texture=model.getAsJsonObject("textures").get(texture.substring(1)).getAsString();}
                faces.add(new Face(List.copyOf(points),texture,uv));
            }
        }
        return new Model(List.copyOf(faces));
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
}

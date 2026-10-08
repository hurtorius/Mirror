package org.hurtorius.mirror.client;

import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Loaded-only, content-free collision receiver snapshots. Client thread only. */
final class ProjectorSurface {
    static final int MAX_BLOCKS=8192, MAX_BOXES=4096, MAX_BOXES_PER_BLOCK=32, MAX_PATCHES=1024;
    static final int TICK_BLOCKS=65536, WINDOW_READS=131072, TICK_WORK=1048576;
    private static final double EPS=1e-6;
    private static final class Stop extends RuntimeException {
        final String reason;
        Stop(String reason) { super(null,null,false,false);this.reason=reason; }
    }
    record Hit(Vec3 point,Direction face,Vec3 source) {
        Vec3 normal() { return face.getUnitVec3(); }
        Vec3 center() { return point.add(normal().scale(.025)); }
        float yaw() { Vec3 n=normal();return (float)Math.toDegrees(Math.atan2(n.x,n.z)); }
        float pitch() { Vec3 n=normal();return -(float)Math.toDegrees(Math.atan2(n.y,Math.hypot(n.x,n.z))); }
    }
    record Result(List<ProjectorGeometry.Patch> patches,String status) {
        Result { patches=List.copyOf(patches); }
        boolean empty() { return patches.isEmpty(); }
    }
    private record Key(Direction face,double plane,int u0,int v0,int u1,int v1,Vec3 source) { }
    private record Cached(Key key,long tick,Map<Long,BlockState> dependencies,List<ProjectorGeometry.Box> boxes,Result result) { }
    private static final Map<BlockPos,Cached> cache=new LinkedHashMap<>();
    private static final Map<BlockPos,String> statuses=new LinkedHashMap<>();
    private static final Map<BlockPos,Long> geometryFailures=new LinkedHashMap<>();
    private static Object world;
    private static long budgetTick=Long.MIN_VALUE;
    private static int tickBlocks,tickWork,windowReads,frameTriangles=96000,frameWork=1048576;
    private static int eligibilityTriangles=48000,eligibilityWork=524288;
    private ProjectorSurface() { }
    static void endFrame() { frameTriangles=96000;frameWork=1048576; }
    static void clear() { cache.clear();statuses.clear();geometryFailures.clear();world=null;budgetTick=Long.MIN_VALUE;tickBlocks=tickWork=windowReads=0;eligibilityTriangles=48000;eligibilityWork=524288;endFrame(); }
    static long window() {return System.nanoTime()/50_000_000L;}
    record MeshBudget(ProjectorGeometry.Budget budget,int triangles,int work,boolean rendered) { }
    static MeshBudget geometryBudget() {return geometryBudget(true);}
    static MeshBudget geometryBudget(boolean rendered) {
        int triangles=Math.min(24000,rendered?frameTriangles:eligibilityTriangles),work=Math.min(262144,rendered?frameWork:eligibilityWork);
        return new MeshBudget(new ProjectorGeometry.Budget(24000,triangles,work),triangles,work,rendered);
    }
    static void consume(MeshBudget b) {
        int triangles=b.triangles-b.budget.remainingTriangles(),work=b.work-b.budget.remainingWork();
        if(b.rendered) {frameTriangles-=triangles;frameWork-=work;}
        else {eligibilityTriangles-=triangles;eligibilityWork-=work;}
    }
    static boolean admitCached(int triangles) {if(triangles<0||triangles>frameTriangles)return false;frameTriangles-=triangles;return true;}
    static void invalidate(BlockPos pos) { cache.remove(pos);statuses.remove(pos);geometryFailures.remove(pos); }
    static String status(BlockPos pos) { return statuses.getOrDefault(pos,"No saved wall projection has been checked yet. The preview does not scan the world."); }
    static void failed(BlockPos pos,String reason) {message(pos,reason);if(geometryFailures.size()>=16&&!geometryFailures.containsKey(pos))geometryFailures.remove(geometryFailures.keySet().iterator().next());geometryFailures.put(pos.immutable(),window());}
    private static void context(ClientLevel level) {
        if(world!=level) {clear();world=level;}
        beginWindow(window());
    }
    /** Numeric clock seam; world gameTime is intentionally not a budget or freshness clock. */
    static void beginWindow(long value) {
        if(budgetTick!=value) {budgetTick=value;tickBlocks=tickWork=windowReads=0;eligibilityTriangles=48000;eligibilityWork=524288;}
    }
    private static void work() { if(++tickWork>TICK_WORK)throw new Stop("Projection paused: collision work limit reached."); }
    private static BlockState read(ClientLevel level,BlockPos pos) {
        if(++windowReads>WINDOW_READS)throw new Stop("Projection paused: collision read limit reached.");
        if(!level.isInWorldBounds(pos)||!level.hasChunkAt(pos))throw new Stop("Projection paused: the receiver or beam crosses unloaded world space.");
        return level.getBlockState(pos);
    }
    /** Every direct and collision-shape neighbor lookup goes through this guard. */
    private static final class View implements BlockGetter {
        final ClientLevel level; final Map<Long,BlockState> states=new LinkedHashMap<>();
        int shapeBoxes,totalBoxes;
        View(ClientLevel level) {this.level=level;}
        @Override public BlockState getBlockState(BlockPos pos) {
            BlockState known=states.get(pos.asLong());if(known!=null)return known;
            if(states.size()>=MAX_BLOCKS||++tickBlocks>TICK_BLOCKS)throw new Stop("Projection paused: collision scan limit reached. Try a smaller or nearer wall.");
            BlockState state=read(level,pos);states.put(pos.asLong(),state);return state;
        }
        @Override public FluidState getFluidState(BlockPos pos) {return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos) {throw new Stop("Projection paused: this collision shape requires dynamic block-entity data.");}
        @Override public int getHeight() {return level.getHeight();}
        @Override public int getMinY() {return level.getMinY();}
        VoxelShape shape(BlockPos pos) {
            work();VoxelShape shape=getBlockState(pos).getCollisionShape(this,pos,CollisionContext.empty());
            long cells=1;
            for(Direction.Axis axis:Direction.Axis.values()) {
                int count=shape.getCoords(axis).size();
                if(count>65)throw new Stop("Projection paused: unsupported collision grid complexity.");
                cells*=Math.max(1,count-1);
            }
            if(cells>4096)throw new Stop("Projection paused: unsupported collision grid complexity.");
            for(long i=0;i<cells;i++)work();
            return shape;
        }
        void boxes(BlockPos pos,List<ProjectorGeometry.Box> output) {
            boxes(shape(pos),pos,output);
        }
        void boxes(VoxelShape shape,BlockPos pos,List<ProjectorGeometry.Box> output) {
            shapeBoxes=0;
            shape.forAllBoxes((x0,y0,z0,x1,y1,z1)->{
                if(++shapeBoxes>MAX_BOXES_PER_BLOCK||++totalBoxes>MAX_BOXES)throw new Stop("Projection paused: this wall has too much collision detail.");
                if(!finite(x0,y0,z0,x1,y1,z1)||x0< -1||y0< -1||z0< -1||x1>2||y1>2||z1>2)
                    throw new Stop("Projection paused: unsupported collision bounds.");
                output.add(new ProjectorGeometry.Box(pos.getX()+x0,pos.getY()+y0,pos.getZ()+z0,pos.getX()+x1,pos.getY()+y1,pos.getZ()+z1));
            });
        }
    }
    /** Exact DDA stops before any unloaded cell; no chunk creation or sampling gaps. */
    static Hit ray(ClientLevel level,BlockPos initiator,float yaw,float pitch,float maximum) {
        context(level);
        if(!Float.isFinite(yaw)||!Float.isFinite(pitch)||!Float.isFinite(maximum))return null;
        Vec3 start=Vec3.atLowerCornerOf(initiator).add(.5,1.02,.5);
        double yr=Math.toRadians(yaw),pr=Math.toRadians(pitch);
        Vec3 end=start.add(Math.sin(yr)*Math.cos(pr)*Math.clamp(maximum,1,32),-Math.sin(pr)*Math.clamp(maximum,1,32),Math.cos(yr)*Math.cos(pr)*Math.clamp(maximum,1,32));
        View view=new View(level);int[] count={0};
        try {
            BlockHitResult hit=BlockGetter.traverseBlocks(start,end,view,(guard,pos)->{
                if(++count[0]>128)throw new Stop("Projection paused: ray work limit reached.");
                guard.getBlockState(pos);
                if(pos.equals(initiator))return null;
                VoxelShape shape=guard.shape(pos);guard.boxes(shape,pos,new ArrayList<>());
                return shape.clip(start,end,pos);
            },guard->null);
            if(hit==null||hit.getType()!=HitResult.Type.BLOCK||!finite(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z)) {message(initiator,"No loaded collision wall is in the projector's reach.");return null;}
            return new Hit(hit.getLocation(),hit.getDirection(),start);
        } catch(Stop stop) {message(initiator,stop.reason);return null;}
    }
    static Result receivers(ClientLevel level,BlockPos initiator,Hit hit,float roll,float width,float height,float edge,
                            TransitionMath.Plan appearance) {
        context(level);
        try {
            if(geometryFailures.getOrDefault(initiator,Long.MIN_VALUE)==window())return new Result(List.of(),status(initiator));
            if(hit==null||!finite(roll,width,height,edge)||width<=0||height<=0||appearance==null)return empty(initiator,"No supported projector receiver.");
            if(!finite(appearance.scaleX(),appearance.scaleY(),appearance.offsetX()))return empty(initiator,"No supported projection transform.");
            if(appearance.scaleX()<.001f||appearance.scaleY()<.001f)return empty(initiator,"Projection is folded away.");
            Quaternionf rotation=rotation(hit,roll);
            double u0=Double.POSITIVE_INFINITY,v0=u0,u1=Double.NEGATIVE_INFINITY,v1=u1;
            float pad=Math.max(0,edge)*4;
            for(float x:new float[]{-width/2-pad,width/2+pad})for(float y:new float[]{-height/2-pad,height/2+pad}) {
                Vector3f p=rotation.transform(new Vector3f(x*appearance.scaleX()+appearance.offsetX()*width,y*appearance.scaleY(),0));
                Vec3 q=hit.point.add(p.x,p.y,p.z);double u=tangent(q,hit.face.getAxis(),0),v=tangent(q,hit.face.getAxis(),1);
                u0=Math.min(u0,u);u1=Math.max(u1,u);v0=Math.min(v0,v);v1=Math.max(v1,v);
            }
            Key key=new Key(hit.face,axis(hit.point,hit.face.getAxis()),(int)Math.floor(u0),(int)Math.floor(v0),(int)Math.ceil(u1),(int)Math.ceil(v1),hit.source);
            Cached old=cache.get(initiator);
            boolean valid=old!=null&&old.key.equals(key)&&window()>=old.tick;
            if(valid)for(var entry:old.dependencies.entrySet())if(read(level,BlockPos.of(entry.getKey()))!=entry.getValue()) {valid=false;break;}
            // State edits and chunk unloads are revalidated before every reuse. Shapes with other
            // time-dependent behavior are re-evaluated every 50 ms, even with a paused game clock.
            if(valid&&old.tick==window()) {message(initiator,old.result.status);return old.result;}
            View view=new View(level);List<ProjectorGeometry.Box> boxes=new ArrayList<>();
            Vec3 corner0=point(key,key.u0,key.v0),corner1=point(key,key.u1,key.v1);
            int x0=(int)Math.floor(Math.min(hit.source.x,Math.min(corner0.x,corner1.x)))-1,x1=(int)Math.floor(Math.max(hit.source.x,Math.max(corner0.x,corner1.x)))+1;
            int y0=(int)Math.floor(Math.min(hit.source.y,Math.min(corner0.y,corner1.y)))-1,y1=(int)Math.floor(Math.max(hit.source.y,Math.max(corner0.y,corner1.y)))+1;
            int z0=(int)Math.floor(Math.min(hit.source.z,Math.min(corner0.z,corner1.z)))-1,z1=(int)Math.floor(Math.max(hit.source.z,Math.max(corner0.z,corner1.z)))+1;
            if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>MAX_BLOCKS)throw new Stop("Projection paused: collision scan limit reached. Try a smaller or nearer wall.");
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++) {
                BlockPos pos=new BlockPos(x,y,z);view.getBlockState(pos);if(!pos.equals(initiator))view.boxes(pos,boxes);
            }
            Result result;
            if(valid&&old.boxes.equals(boxes))result=old.result;
            else result=collisionFaces(key.face,key.plane,key.u0,key.v0,key.u1,key.v1,key.source,boxes);
            if(cache.size()>=16&&!cache.containsKey(initiator))cache.remove(cache.keySet().iterator().next());
            cache.put(initiator.immutable(),new Cached(key,window(),Map.copyOf(view.states),List.copyOf(boxes),result));
            message(initiator,result.status);return result;
        } catch(Stop stop) {cache.remove(initiator);return empty(initiator,stop.reason);}
    }
    /** Detached collision-box seam: tests require no level, client, renderer or native runtime. */
    static Result collisionFaces(Direction face,double plane,int u0,int v0,int u1,int v1,Vec3 source,List<ProjectorGeometry.Box> boxes) {
        try {
            if(boxes==null||boxes.size()>MAX_BOXES||face==null||source==null||!finite(plane,source.x,source.y,source.z))
                return new Result(List.of(),"Projection paused: unsupported receiver geometry.");
            return build(new Key(face,plane,u0,v0,u1,v1,source),boxes);
        } catch(Stop stop) {return new Result(List.of(),stop.reason);}
    }
    private static Result build(Key key,List<ProjectorGeometry.Box> boxes) {
        List<double[]> rectangles=new ArrayList<>();List<ProjectorGeometry.Box> blockers=new ArrayList<>();
        int normalSign=key.face.getAxisDirection().getStep();Direction.Axis axis=key.face.getAxis();
        double sourceDepth=(axis(key.source,axis)-key.plane)*normalSign;
        if(sourceDepth<=EPS)return new Result(List.of(),"No front-facing collision receiver.");
        for(var box:boxes) {
            double near=boxAxis(box,axis,normalSign>0),far=boxAxis(box,axis,normalSign<0);
            if((near-key.plane)*normalSign>0&&(far-key.plane)*normalSign<sourceDepth)blockers.add(box);
            if(Math.abs(near-key.plane)>EPS)continue;
            double a=Math.max(key.u0,boxTangent(box,axis,0,false)),b=Math.max(key.v0,boxTangent(box,axis,1,false));
            double c=Math.min(key.u1,boxTangent(box,axis,0,true)),d=Math.min(key.v1,boxTangent(box,axis,1,true));
            if(c-a<=EPS||d-b<=EPS)continue;
            // AABBs from different blocks may overlap. Subtraction forms a disjoint receiver union.
            List<double[]> pieces=new ArrayList<>();pieces.add(new double[]{a,b,c,d});
            for(double[] previous:rectangles) {
                List<double[]> next=new ArrayList<>();for(double[] part:pieces) {work();subtract(part,previous,next);if(next.size()>MAX_PATCHES)throw new Stop("Projection paused: receiver detail limit reached.");}
                pieces=next;if(pieces.isEmpty())break;
            }
            rectangles.addAll(pieces);if(rectangles.size()>MAX_PATCHES)throw new Stop("Projection paused: receiver detail limit reached.");
        }
        List<ProjectorGeometry.Patch> patches=new ArrayList<>();int count=0;
        var source=pg(key.source);
        for(double[] rect:rectangles)for(double u=rect[0];u<rect[2]-EPS;u+=.5)for(double v=rect[1];v<rect[3]-EPS;v+=.5) {
            if(++count>MAX_PATCHES)throw new Stop("Projection paused: receiver detail limit reached. Try a smaller wall.");
            double right=Math.min(rect[2],u+.5),top=Math.min(rect[3],v+.5);
            var patch=new ProjectorGeometry.Patch(List.of(pg(point(key,u,v)),pg(point(key,right,v)),pg(point(key,right,top)),pg(point(key,u,top))));
            boolean blocked=false;for(var box:blockers) {work();if(ProjectorGeometry.potentiallyBlocked(source,patch,box)) {blocked=true;break;}}
            if(!blocked)patches.add(patch);
        }
        return new Result(patches,patches.isEmpty()?"No unblocked collision face remains on this wall plane.":"Projecting onto loaded flat collision faces. Holes and blocked patches are omitted; recessed or angled faces are not wrapped.");
    }
    private static void subtract(double[] a,double[] b,List<double[]> out) {
        double x0=Math.max(a[0],b[0]),y0=Math.max(a[1],b[1]),x1=Math.min(a[2],b[2]),y1=Math.min(a[3],b[3]);
        if(x1-x0<=EPS||y1-y0<=EPS){out.add(a);return;}
        if(a[0]<x0-EPS)out.add(new double[]{a[0],a[1],x0,a[3]});
        if(x1<a[2]-EPS)out.add(new double[]{x1,a[1],a[2],a[3]});
        if(a[1]<y0-EPS)out.add(new double[]{x0,a[1],x1,y0});
        if(y1<a[3]-EPS)out.add(new double[]{x0,y1,x1,a[3]});
    }
    static List<List<ScreenMesh.Point>> local(Result result,Hit hit,float roll) {
        Quaternionf inverse=rotation(hit,roll).conjugate();List<List<ScreenMesh.Point>> out=new ArrayList<>();
        for(var patch:result.patches) {List<ScreenMesh.Point> polygon=new ArrayList<>();for(var p:patch.corners()) {
            Vector3f q=inverse.transform(new Vector3f((float)(p.x()-hit.point.x),(float)(p.y()-hit.point.y),(float)(p.z()-hit.point.z)));
            polygon.add(new ScreenMesh.Point(q.x,q.y));
        }out.add(List.copyOf(polygon));}return List.copyOf(out);
    }
    static Quaternionf rotation(Hit hit,float roll) {return new Quaternionf().rotateY((float)Math.toRadians(hit.yaw())).rotateX((float)Math.toRadians(hit.pitch())).rotateZ((float)Math.toRadians(roll));}
    private static ProjectorGeometry.Point pg(Vec3 p) {return new ProjectorGeometry.Point(p.x,p.y,p.z);}
    private static Vec3 point(Key k,double u,double v) {return switch(k.face.getAxis()){case X->new Vec3(k.plane,u,v);case Y->new Vec3(u,k.plane,v);case Z->new Vec3(u,v,k.plane);};}
    private static double axis(Vec3 p,Direction.Axis a) {return switch(a){case X->p.x;case Y->p.y;case Z->p.z;};}
    private static double tangent(Vec3 p,Direction.Axis a,int n) {return a==Direction.Axis.X?(n==0?p.y:p.z):a==Direction.Axis.Y?(n==0?p.x:p.z):(n==0?p.x:p.y);}
    private static double boxAxis(ProjectorGeometry.Box b,Direction.Axis a,boolean max) {return switch(a){case X->max?b.x1():b.x0();case Y->max?b.y1():b.y0();case Z->max?b.z1():b.z0();};}
    private static double boxTangent(ProjectorGeometry.Box b,Direction.Axis a,int n,boolean max) {return boxAxis(b,a==Direction.Axis.X?(n==0?Direction.Axis.Y:Direction.Axis.Z):a==Direction.Axis.Y?(n==0?Direction.Axis.X:Direction.Axis.Z):(n==0?Direction.Axis.X:Direction.Axis.Y),max);}
    private static boolean finite(double... values) {for(double value:values)if(!Double.isFinite(value))return false;return true;}
    private static Result empty(BlockPos pos,String reason) {message(pos,reason);return new Result(List.of(),reason);}
    private static void message(BlockPos pos,String value) {if(statuses.size()>=16&&!statuses.containsKey(pos))statuses.remove(statuses.keySet().iterator().next());statuses.put(pos.immutable(),value);}
}

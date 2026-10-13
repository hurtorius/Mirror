package org.hurtorius.mirror.client;

import java.lang.reflect.*;
import java.util.Map;

/**
 * Optional Iris bridge: reflect with its existing vanilla path, preserving the player's shader pipeline. A part that no longer
 * matches Iris switches itself off once, with a log line and a report, instead of crashing the game.
 */
final class IrisReflectionScope implements AutoCloseable {
    private static Object vanilla;
    private static Method managerMethod,dimensionMethod,blendRestore,depthRestore,shadowMethod,packMethod;
    private static Object api;
    private static Field currentField,mapField;
    private final Object manager,previous,key,entry;
    private final Map<Object,Object> map;
    static void initialize(){
        if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris"))return;
        try{
            // Called before joining a world, so the vanilla constructor cannot invalidate live chunk formats.
            Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
            managerMethod=iris.getMethod("getPipelineManager");dimensionMethod=iris.getMethod("getCurrentDimension");
            Class<?> manager=Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            currentField=manager.getDeclaredField("pipeline");mapField=manager.getDeclaredField("pipelinesPerDimension");currentField.setAccessible(true);mapField.setAccessible(true);
            if(!Map.class.isAssignableFrom(mapField.getType()))throw new NoSuchFieldException("PipelineManager.pipelinesPerDimension is no longer a Map");
            vanilla=Class.forName("net.irisshaders.iris.pipeline.VanillaRenderingPipeline").getConstructor().newInstance();
            blendRestore=Class.forName("net.irisshaders.iris.gl.blending.BlendModeStorage").getMethod("restoreBlend");
            depthRestore=Class.forName("net.irisshaders.iris.gl.blending.DepthColorStorage").getMethod("unlockDepthColor");
            Class<?> apiClass=Class.forName("net.irisshaders.iris.api.v0.IrisApi");api=apiClass.getMethod("getInstance").invoke(null);shadowMethod=apiClass.getMethod("isRenderingShadowPass");packMethod=apiClass.getMethod("isShaderPackInUse");
        }catch(ReflectiveOperationException|LinkageError|RuntimeException e){vanilla=null;unavailable("reflection bridge",e);}
    }
    @SuppressWarnings("unchecked")
    static IrisReflectionScope begin(){
        if(vanilla==null)return null;
        Object manager=null,previous=null,key=null,entry=null;Map<Object,Object> map=null;boolean swapped=false;
        try{
            manager=managerMethod.invoke(null);previous=currentField.get(manager);
            if(previous==null||previous.getClass().getSimpleName().equals("VanillaRenderingPipeline"))return null;
            key=dimensionMethod.invoke(null);map=(Map<Object,Object>)mapField.get(manager);
            if(!map.containsKey(key))return null;
            entry=map.put(key,vanilla);swapped=true;currentField.set(manager,vanilla);restore();
            return new IrisReflectionScope(manager,previous,key,entry,map);
        }catch(ReflectiveOperationException|RuntimeException e){
            // Never leave Iris drawing with the borrowed vanilla pipeline.
            if(swapped){try{map.put(key,entry);}catch(RuntimeException ignored){}try{currentField.set(manager,previous);}catch(ReflectiveOperationException|RuntimeException ignored){}}
            vanilla=null;unavailable("reflection bridge",e);return null;
        }
    }
    static boolean shadowPass(){try{return api!=null&&(boolean)shadowMethod.invoke(api);}catch(ReflectiveOperationException|RuntimeException e){api=null;unavailable("shader pass query",e);return false;}}
    /** Shader packs also draw Initiators from the sun, so their shadows need decoration outside the camera view. */
    static boolean shaderPack(){try{return api!=null&&(boolean)packMethod.invoke(api);}catch(ReflectiveOperationException|RuntimeException e){api=null;unavailable("shader pass query",e);return false;}}
    static void restoreDrawingState(){try{restore();}catch(ReflectiveOperationException|RuntimeException e){blendRestore=depthRestore=null;unavailable("drawing state restore",e);}}
    private static void restore()throws ReflectiveOperationException{if(blendRestore!=null)blendRestore.invoke(null);if(depthRestore!=null)depthRestore.invoke(null);}
    /** Each part reports once: the caller has already cleared the handle that failed. */
    private static void unavailable(String part,Throwable e){
        String version=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("iris").map(m->m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        org.slf4j.LoggerFactory.getLogger("mirror").warn("Iris {} unavailable for Iris {}; mirrors may render without the vanilla pass",part,version,e);
        Reports.add("Shader "+part+" disabled for Iris "+version+": "+e);
    }
    private IrisReflectionScope(Object manager,Object previous,Object key,Object entry,Map<Object,Object> map){this.manager=manager;this.previous=previous;this.key=key;this.entry=entry;this.map=map;}
    /** Iris always gets its current pipeline back, even when its per-dimension map refuses the restore. */
    public void close(){
        RuntimeException refused=null;try{map.put(key,entry);}catch(RuntimeException e){refused=e;}
        try{currentField.set(manager,previous);if(refused!=null)throw refused;}catch(ReflectiveOperationException|RuntimeException e){vanilla=null;unavailable("reflection bridge",e);}
    }
}

package org.hurtorius.mirror.client;

import com.google.gson.*;
import org.hurtorius.mirror.core.*;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class EngineProcess implements AutoCloseable {
    public record Picture(byte[] bytes,long received){}
    private final ArrayBlockingQueue<Picture> picture=new ArrayBlockingQueue<>(8);
    private volatile long picturesRead,picturesTaken;
    public Picture takePicture(){var image=picture.poll();if(image!=null)picturesTaken++;return image;}
    public void discardPictures(){picture.clear();}
    public Map<String,Long> pictureStats(){return Map.of("received",picturesRead,"consumed",picturesTaken);}
    private final Consumer<JsonObject> events;
    private final Consumer<byte[]> audio;
    private final BlockingQueue<JsonObject> pending=new ArrayBlockingQueue<>(128);
    private final Map<String,JsonObject> coalesced=new ConcurrentHashMap<>();
    private static final Set<Process> LIVE=ConcurrentHashMap.newKeySet();
    static {Runtime.getRuntime().addShutdownHook(new Thread(()->{for(Process p:LIVE){p.descendants().forEach(ProcessHandle::destroyForcibly);p.destroyForcibly();}},"Mirror source cleanup"));}
    private volatile boolean closed=false;
    private volatile DataOutputStream out;
    private volatile Process process;
    private Socket connection;
    private Thread thread,writer;
    public EngineProcess(Path root,String id,boolean browser,boolean media,Consumer<JsonObject> events,Consumer<byte[]> audio){
        this.events=events;this.audio=audio;
        thread=new Thread(()->start(root,id,browser,media),"Mirror source "+id);thread.setDaemon(true);thread.start();
    }
    private void start(Path root,String id,boolean browser,boolean media){
        try{List<Path> jars=RuntimeInstaller.prepare(root,browser,media,text->event("PREPARING","text",text));if(closed)return;
            String token=UUID.randomUUID().toString()+UUID.randomUUID();
            try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){listener.setSoTimeout(20000);
                String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").contains("Windows")?"java.exe":"java").toString();
                String cp=jars.stream().map(Path::toString).reduce((a,b)->a+File.pathSeparator+b).orElseThrow();
                List<String> args=new ArrayList<>(List.of(javaExecutable,"-Xmx512m","-Dorg.bytedeco.javacpp.cachedir="+root.resolve("native-cache"),"--add-opens=java.desktop/sun.awt=ALL-UNNAMED"));
                if(System.getProperty("os.name").contains("Mac"))args.addAll(List.of("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED","--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"));
                args.addAll(List.of("-cp",cp,"org.hurtorius.mirror.engine.EngineMain",Integer.toString(listener.getLocalPort()),root.toString()));
                Path log=AtomicFile.child(root,"engine-"+id+".log");if(Files.exists(log)&&Files.size(log)>1024*1024)Files.write(log,new byte[0],StandardOpenOption.TRUNCATE_EXISTING);
                ProcessBuilder builder=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));builder.environment().put("MIRROR_ENGINE_TOKEN",token);builder.environment().put("MIRROR_BROWSER_ID",browser&&id.startsWith("browser-")?id.substring(8):id);process=builder.start();LIVE.add(process);
                if(closed){process.destroy();return;}
                connection=listener.accept();connection.setTcpNoDelay(true);connection.setSoTimeout(10000);DataInputStream in=new DataInputStream(new BufferedInputStream(connection.getInputStream()));
                int kind=in.readUnsignedByte(),size=in.readInt();if(kind!=0||size<1||size>24000)throw new IOException("Invalid source handshake.");JsonObject auth=JsonParser.parseString(new String(in.readNBytes(size),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();if(!"AUTH".equals(auth.get("type").getAsString())||!token.equals(auth.get("token").getAsString()))throw new IOException("Source authentication failed.");
                connection.setSoTimeout(0);out=new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()));writer=new Thread(this::writeCommands,"Mirror source commands");writer.setDaemon(true);writer.start();
                while(!closed){kind=in.readUnsignedByte();size=in.readInt();if(size<1||size>(kind==1?240000:kind==2?9600:24000))throw new IOException("Source exceeded its data limit.");byte[] bytes=in.readNBytes(size);if(bytes.length!=size)break;
                    switch(kind){case 0->events.accept(JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());case 1->{if(FrameAssembler.validJpeg(bytes)){picturesRead++;var frame=new Picture(bytes,System.nanoTime());if(!picture.offer(frame)){picture.poll();picture.offer(frame);}}}case 2->audio.accept(bytes);default->throw new IOException("Invalid source data.");}
                }
            }
        }catch(Exception e){if(!closed)event("SOURCE_FAILED","text",e instanceof EOFException||e instanceof java.net.SocketException?(browser?"The browser stopped. Choose Reload to reopen it.":"The source stopped. Reload the media or try the action again."):e.getMessage()==null?"The source stopped. Reopen it to recover.":e.getMessage());}
        finally{close();}
    }
    private void event(String type,Object...values){JsonObject m=new JsonObject();m.addProperty("type",type);for(int i=0;i<values.length;i+=2)m.add((String)values[i],ScreenSpec.JSON.toJsonTree(values[i+1]));events.accept(m);}
    public void send(JsonObject message){
        if(closed)return;
        String type=message.get("type").getAsString();
        if(type.equals("TIME")||type.equals("PAGE")||type.equals("POINTER")&&message.get("event").getAsInt()==503){coalesced.put(type,message.deepCopy());return;}
        if(!pending.offer(message.deepCopy()))event("ERROR","text","The source is busy. Wait a moment and try that action again.");
    }
    private void writeCommands(){
        try{while(!closed){
            JsonObject message=pending.poll(4,TimeUnit.MILLISECONDS);
            if(message!=null)writeCommand(message);
            for(String key:List.of("POINTER","TIME","PAGE")){JsonObject latest=coalesced.remove(key);if(latest!=null)writeCommand(latest);}
        }}catch(InterruptedException ignored){}catch(IOException error){if(!closed)event("SOURCE_FAILED","text","The source connection stopped. Reopen it to recover.");close();}
    }
    private void writeCommand(JsonObject message)throws IOException{
        byte[] bytes=message.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(bytes.length>24000)return;
        out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
    public void send(String type,Object...values){JsonObject m=new JsonObject();m.addProperty("type",type);for(int i=0;i<values.length;i+=2)m.add((String)values[i],ScreenSpec.JSON.toJsonTree(values[i+1]));send(m);}
    public synchronized void close(){
        if(closed)return;closed=true;pending.clear();coalesced.clear();picture.clear();
        try{if(connection!=null)connection.close();}catch(IOException ignored){}
        if(writer!=null)writer.interrupt();if(thread!=Thread.currentThread())thread.interrupt();
        Process owned=process;
        if(owned!=null)CompletableFuture.runAsync(()->{
            var children=owned.descendants().toList();
            try{if(!owned.waitFor(3500,TimeUnit.MILLISECONDS)){children.forEach(ProcessHandle::destroy);owned.destroy();}}
            catch(InterruptedException e){owned.destroy();Thread.currentThread().interrupt();}
            finally{LIVE.remove(owned);}
        });
    }
    public boolean alive(){return !closed;}
}

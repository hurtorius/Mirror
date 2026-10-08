package org.hurtorius.mirror.client;

import javax.sound.sampled.*;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.hurtorius.mirror.core.Anchor;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Short, bounded PCM playback. Output-device waits never run on Minecraft's thread. */
public final class SpatialAudio implements AutoCloseable {
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    private final LongAdder accepted=new LongAdder(),written=new LongAdder(),audible=new LongAdder(),dropped=new LongAdder();
    private volatile boolean alive=true,flushRequested;
    private volatile double gain,pan;
    private volatile boolean wide=true;
    private volatile SourceDataLine line;
    private int queuedBytes;
    public SpatialAudio(){Thread thread=new Thread(this::play,"Mirror spatial sound");thread.setDaemon(true);thread.start();}
    public synchronized void accept(byte[] pcm){
        if(!alive||gain<=0)return;
        accepted.add(pcm.length);byte[] bytes=pcm.clone();
        while(queuedBytes+bytes.length>19200||queue.size()>=16){byte[] old=queue.poll();if(old==null)break;queuedBytes-=old.length;dropped.increment();}
        queue.add(bytes);queuedBytes+=bytes.length;notifyAll();
    }
    private double volume(double source){Minecraft mc=Minecraft.getInstance();return Math.clamp(source*MirrorClient.preferences.volume*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.RECORDS),0,1);}
    private synchronized void gain(double value){if(value==0&&gain>0){queue.clear();queuedBytes=0;flushRequested=true;notifyAll();}gain=value;}
    public void preview(double sourceVolume){
        gain(MirrorClient.preferences.hidden?0:volume(Double.isFinite(sourceVolume)?sourceVolume:.7));pan=0;wide=true;
    }
    public void update(Anchor a){
        Minecraft mc=Minecraft.getInstance();ClientPreferences p=MirrorClient.preferences;
        if(mc.player==null||p.hidden||!a.spec.live){gain(0);return;}
        var location=MirrorRenderer.soundPosition(a.id);
        if(location==null&&a.spec.projector){gain(0);return;}
        if(location==null)location=new net.minecraft.world.phys.Vec3(a.x+.5+a.spec.x,a.y+a.spec.y,a.z+.5+a.spec.z);
        double dx=location.x-mc.player.getX(),dy=location.y-mc.player.getEyeY(),dz=location.z-mc.player.getZ(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double attenuation=a.spec.positional&&p.positional?Math.pow(Math.max(0,1-distance/a.spec.soundRange),2):1;
        gain(a.spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.MEDIA&&!a.playing?0:volume(a.spec.volume)*attenuation);
        wide=a.spec.wideStereo;double yaw=Math.toRadians(mc.player.getYRot());
        pan=a.spec.positional&&p.positional?Math.clamp((dx*Math.cos(yaw)+dz*Math.sin(yaw))/Math.max(1,distance),-1,1):0;
    }
    private synchronized byte[] take()throws InterruptedException{if(queue.isEmpty()&&alive)wait(50);byte[] data=queue.poll();if(data!=null)queuedBytes-=data.length;return data;}
    private void play(){
        try{
            AudioFormat format=new AudioFormat(48000,16,2,true,false);line=AudioSystem.getSourceDataLine(format);line.open(format,9600);line.start();
            while(alive){
                if(flushRequested){line.flush();flushRequested=false;}
                byte[] data=take();if(data==null)continue;
                double left=gain*(pan>0?1-pan*.8:1),right=gain*(pan<0?1+pan*.8:1);boolean nonzero=false;
                for(int i=0;i+3<data.length;i+=4){
                    if(!wide){int a=(short)((data[i]&255)|(data[i+1]<<8)),b=(short)((data[i+2]&255)|(data[i+3]<<8)),mean=(a+b)/2;data[i]=data[i+2]=(byte)mean;data[i+1]=data[i+3]=(byte)(mean>>8);}
                    scale(data,i,left);scale(data,i+2,right);nonzero|=data[i]!=0||data[i+1]!=0||data[i+2]!=0||data[i+3]!=0;
                }
                if(!alive)break;
                int count=line.write(data,0,data.length);written.add(count);if(nonzero)audible.add(count);
            }
        }catch(Exception e){if(alive)MirrorClient.notice("Mirror audio is unavailable. Check your audio output device.");}
        finally{alive=false;if(line!=null){line.stop();line.flush();line.close();}}
    }
    private static void scale(byte[] b,int i,double factor){short sample=(short)((b[i]&255)|(b[i+1]<<8));int output=(int)(sample*factor);b[i]=(byte)output;b[i+1]=(byte)(output>>8);}
    public synchronized Map<String,Object> diagnostics(){return Map.of("gain",gain,"acceptedBytes",accepted.sum(),"writtenBytes",written.sum(),"audibleBytes",audible.sum(),"droppedChunks",dropped.sum(),"queuedChunks",queue.size(),"outputOpen",line!=null&&line.isOpen());}
    public synchronized void close(){gain=0;alive=false;queue.clear();queuedBytes=0;flushRequested=true;notifyAll();}
}

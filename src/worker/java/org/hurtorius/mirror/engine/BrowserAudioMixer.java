package org.hurtorius.mirror.engine;

import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Mix bounded per-frame Web Audio streams into one 48 kHz stereo output, independent of native mute. */
final class BrowserAudioMixer implements AutoCloseable {
    private static final int BLOCK=3840,CAPACITY=19200;
    private final Map<String,Input> sources=new HashMap<>();
    private final Consumer<byte[]> output;
    private volatile boolean alive=true,enabled=true;
    private long received,mixed,audible;
    private static final class Input {final byte[] ring=new byte[CAPACITY];int start,size;long last;boolean primed;}
    BrowserAudioMixer(Consumer<byte[]> output){this.output=output;Thread thread=new Thread(this::mix,"Mirror browser audio mixer");thread.setDaemon(true);thread.start();}
    synchronized void accept(String id,byte[] pcm){
        if(!alive||pcm.length<4||pcm.length>8192||pcm.length%4!=0||!id.matches("[a-fA-F0-9-]{36}"))return;
        Input input=sources.get(id);if(input==null){if(sources.size()>=8)return;input=new Input();sources.put(id,input);}
        int drop=Math.max(0,input.size+pcm.length-CAPACITY);input.start=(input.start+drop)%CAPACITY;input.size-=drop;
        int write=(input.start+input.size)%CAPACITY,first=Math.min(pcm.length,CAPACITY-write);System.arraycopy(pcm,0,input.ring,write,first);System.arraycopy(pcm,first,input.ring,0,pcm.length-first);
        input.size+=pcm.length;input.last=System.nanoTime();received+=pcm.length;
    }
    void enabled(boolean value){enabled=value;}
    synchronized Map<String,Object> status(){return Map.of("mode","browser audio bridge","receivedBytes",received,"mixedBytes",mixed,"audibleBytes",audible,"streams",sources.size());}
    private synchronized byte[] next(){
        long now=System.nanoTime();sources.values().removeIf(input->now-input.last>2_000_000_000L);
        int[] sum=new int[BLOCK/2];boolean present=false;
        for(Input input:sources.values()){
            if(!input.primed){if(input.size<BLOCK*2)continue;input.primed=true;}
            if(input.size<BLOCK){input.primed=false;continue;}
            int count=BLOCK;present=true;
            for(int i=0;i<count;i+=2){int low=input.ring[(input.start+i)%CAPACITY]&255,high=input.ring[(input.start+i+1)%CAPACITY];sum[i/2]+=(short)(low|high<<8);}
            input.start=(input.start+count)%CAPACITY;input.size-=count;
        }
        if(!present||!enabled)return null;
        byte[] pcm=new byte[BLOCK];boolean signal=false;
        for(int i=0;i<sum.length;i++){int sample=Math.clamp(sum[i],-32768,32767);pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>8);signal|=sample!=0;}
        mixed+=pcm.length;if(signal)audible+=pcm.length;return pcm;
    }
    private void mix(){long due=System.nanoTime();while(alive){byte[] pcm=next();if(pcm!=null)output.accept(pcm);due+=20_000_000;long wait=due-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);else if(wait< -100_000_000)due=System.nanoTime();}}
    public synchronized void close(){alive=false;sources.clear();}
}

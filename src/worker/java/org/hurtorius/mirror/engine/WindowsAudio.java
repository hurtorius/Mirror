package org.hurtorius.mirror.engine;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** WASAPI process loopback, directly through installed Windows libraries. No microphone. */
final class WindowsAudio implements AutoCloseable {
    interface AudioApi extends StdCallLibrary {int ActivateAudioInterfaceAsync(WString device,Pointer iid,Pointer properties,Pointer handler,PointerByReference operation);}
    static final AudioApi API=Native.load("Mmdevapi",AudioApi.class);
    interface Query extends StdCallLibrary.StdCallCallback {int invoke(Pointer self,Pointer iid,Pointer result);}
    interface Ref extends StdCallLibrary.StdCallCallback {int invoke(Pointer self);}
    interface Completed extends StdCallLibrary.StdCallCallback {int invoke(Pointer self,Pointer operation);}
    private static final String AUDIO_CLIENT="1cb9ad4c-dbfa-4c32-b178-c2f568a703b2",AUDIO_CAPTURE="c8adbd64-e71e-48a0-a4de-185c395cd317";
    private final Thread thread;
    private volatile boolean alive=true;
    private final Consumer<byte[]> pcm;
    private final Consumer<String> error;
    private final long pid;
    private final boolean exclude;
    private final boolean quietLocal;
    private volatile int quietedSessions=0;
    int quietedSessions(){return quietedSessions;}
    WindowsAudio(long pid,boolean exclude,boolean quietLocal,Consumer<byte[]> pcm,Consumer<String> error){this.pid=pid;this.exclude=exclude;this.quietLocal=quietLocal;this.pcm=pcm;this.error=error;thread=new Thread(this::capture,"Mirror selected-process sound");thread.setDaemon(true);thread.start();}
    private static final class Completion {
        final Memory object=new Memory(Native.POINTER_SIZE),table=new Memory(Native.POINTER_SIZE*4L);
        final AtomicInteger refs=new AtomicInteger(1);
        final CountDownLatch ready=new CountDownLatch(1);
        volatile Pointer client;
        volatile int result=0x80004005;
        final Query query=(self,iid,out)->{if(out==null)return 0x80004003;byte[] id=iid.getByteArray(0,16);boolean match=java.util.Arrays.equals(id,WindowsNative.guid("00000000-0000-0000-c000-000000000046").getByteArray(0,16))||java.util.Arrays.equals(id,WindowsNative.guid("41d949ab-9862-444a-80f6-c261334da5eb").getByteArray(0,16))||java.util.Arrays.equals(id,WindowsNative.guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90").getByteArray(0,16));out.setPointer(0,match?object:null);if(match)refs.incrementAndGet();return match?0:0x80004002;};
        final Ref add=self->refs.incrementAndGet(),release=self->refs.decrementAndGet();
        final Completed complete=(self,operation)->{Pointer raw=null;try{IntByReference hr=new IntByReference();PointerByReference result=new PointerByReference();WindowsNative.check(WindowsNative.call(operation,3,hr,result));WindowsNative.check(hr.getValue());raw=result.getValue();client=WindowsNative.query(raw,AUDIO_CLIENT);this.result=0;}catch(RuntimeException e){this.result=0x80004005;}finally{WindowsNative.release(raw);ready.countDown();}return 0;};
        Completion(){table.setPointer(0,CallbackReference.getFunctionPointer(query));table.setPointer(Native.POINTER_SIZE,CallbackReference.getFunctionPointer(add));table.setPointer(Native.POINTER_SIZE*2L,CallbackReference.getFunctionPointer(release));table.setPointer(Native.POINTER_SIZE*3L,CallbackReference.getFunctionPointer(complete));object.setPointer(0,table);}
    }
    private void capture(){Pointer operation=null,client=null,capture=null;WinNT.HANDLE event=null;BrowserSoundMute mute=quietLocal?new BrowserSoundMute():null;long lastMute=0;
        try{WindowsNative.check(WindowsNative.RUNTIME.RoInitialize(1));Completion callback=new Completion();Memory selection=new Memory(12);selection.setInt(0,1);selection.setInt(4,(int)pid);selection.setInt(8,exclude?1:0);Memory properties=new Memory(24);properties.clear();properties.setShort(0,(short)65);properties.setInt(8,12);properties.setPointer(16,selection);PointerByReference result=new PointerByReference();WindowsNative.check(API.ActivateAudioInterfaceAsync(new WString("VAD\\Process_Loopback"),WindowsNative.guid(AUDIO_CLIENT),properties,callback.object,result));operation=result.getValue();if(!callback.ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Source sound did not initialize.");WindowsNative.check(callback.result);client=callback.client;
            Memory format=new Memory(18);format.clear();format.setShort(0,(short)1);format.setShort(2,(short)2);format.setInt(4,48000);format.setInt(8,192000);format.setShort(12,(short)4);format.setShort(14,(short)16);
            WindowsNative.check(WindowsNative.call(client,3,0,0x80060000,0L,0L,format,null));event=Kernel32.INSTANCE.CreateEvent(null,false,false,null);if(event==null)throw new IllegalStateException("Source sound event could not open.");WindowsNative.check(WindowsNative.call(client,13,event.getPointer()));result=new PointerByReference();WindowsNative.check(WindowsNative.call(client,14,WindowsNative.guid(AUDIO_CAPTURE),result));capture=result.getValue();WindowsNative.check(WindowsNative.call(client,10));
            while(alive){if(mute!=null&&System.currentTimeMillis()-lastMute>500){mute.refresh();quietedSessions=mute.count();lastMute=System.currentTimeMillis();}int wait=Kernel32.INSTANCE.WaitForSingleObject(event,200);if(wait==258)continue;if(wait!=0)break;IntByReference pending=new IntByReference();WindowsNative.check(WindowsNative.call(capture,5,pending));while(alive&&pending.getValue()>0){PointerByReference data=new PointerByReference();IntByReference frames=new IntByReference(),flags=new IntByReference();WindowsNative.check(WindowsNative.call(capture,3,data,frames,flags,null,null));try{int bytes=Math.multiplyExact(frames.getValue(),4);if(bytes<0||bytes>192000)throw new IllegalStateException("Source sound exceeded its buffer limit.");byte[] samples=(flags.getValue()&2)!=0?new byte[bytes]:data.getValue().getByteArray(0,bytes);for(int offset=0;offset<samples.length;offset+=9600)pcm.accept(java.util.Arrays.copyOfRange(samples,offset,Math.min(samples.length,offset+9600)));}finally{WindowsNative.call(capture,4,frames.getValue());}WindowsNative.check(WindowsNative.call(capture,5,pending));}
                java.lang.ref.Reference.reachabilityFence(callback);
            }
        }catch(Exception e){if(alive)error.accept("This source's sound could not be captured. Windows 11 or build 20348+ and an active audio output are required.");}
        finally{if(mute!=null)mute.close();if(client!=null)WindowsNative.call(client,11);WindowsNative.release(capture);WindowsNative.release(client);WindowsNative.release(operation);if(event!=null)Kernel32.INSTANCE.CloseHandle(event);WindowsNative.RUNTIME.RoUninitialize();}
    }
    public void close(){alive=false;thread.interrupt();if(Thread.currentThread()!=thread)try{thread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

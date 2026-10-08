package org.hurtorius.mirror.engine;
import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.util.*;

/** Mutes only this Mirror browser's own process tree while its sound is relayed. */
final class BrowserSoundMute implements AutoCloseable {
    interface Ole extends StdCallLibrary {int CoCreateInstance(Pointer clsid,Pointer outer,int context,Pointer iid,PointerByReference result);}
    static final Ole OLE=Native.load("ole32",Ole.class);
    private record Saved(Pointer volume,int mute){}
    private final Map<String,Saved> saved=new HashMap<>();
    int count(){return saved.size();}
    void refresh(){Pointer enumerator=null,device=null,manager=null,sessions=null;
        try{PointerByReference result=new PointerByReference();WindowsNative.check(OLE.CoCreateInstance(WindowsNative.guid("bcde0395-e52f-467c-8e3d-c4579291692e"),null,1,WindowsNative.guid("a95664d2-9614-4f35-a746-de8db63617e6"),result));enumerator=result.getValue();result=new PointerByReference();WindowsNative.check(WindowsNative.call(enumerator,4,0,1,result));device=result.getValue();result=new PointerByReference();WindowsNative.check(WindowsNative.call(device,3,WindowsNative.guid("77aa99a0-1bd6-484f-8bc7-2c654c9a9b6f"),1,null,result));manager=result.getValue();result=new PointerByReference();WindowsNative.check(WindowsNative.call(manager,5,result));sessions=result.getValue();IntByReference count=new IntByReference();WindowsNative.check(WindowsNative.call(sessions,3,count));Set<Long> pids=new HashSet<>();ProcessHandle.current().descendants().forEach(p->pids.add(p.pid()));
            for(int i=0;i<Math.min(256,count.getValue());i++){Pointer control=null,control2=null,volume=null;try{result=new PointerByReference();WindowsNative.check(WindowsNative.call(sessions,4,i,result));control=result.getValue();control2=WindowsNative.query(control,"bfb7ff88-7239-4fc9-8fa2-07c950be9c6d");IntByReference pid=new IntByReference();WindowsNative.check(WindowsNative.call(control2,14,pid));if(!pids.contains(Integer.toUnsignedLong(pid.getValue())))continue;volume=WindowsNative.query(control,"87ce5498-68d6-44e5-9215-6da47ef883d8");PointerByReference name=new PointerByReference();WindowsNative.check(WindowsNative.call(control2,13,name));Pointer label=name.getValue();String identity;try{identity=label.getWideString(0);}finally{com.sun.jna.platform.win32.Ole32.INSTANCE.CoTaskMemFree(label);}if(saved.containsKey(identity))continue;IntByReference mute=new IntByReference();WindowsNative.check(WindowsNative.call(volume,6,mute));WindowsNative.check(WindowsNative.call(volume,5,1,null));saved.put(identity,new Saved(volume,mute.getValue()));volume=null;}finally{WindowsNative.release(volume);WindowsNative.release(control2);WindowsNative.release(control);}}
        }catch(RuntimeException ignored){}finally{WindowsNative.release(sessions);WindowsNative.release(manager);WindowsNative.release(device);WindowsNative.release(enumerator);}
    }
    public void close(){for(Saved state:saved.values())try{WindowsNative.call(state.volume,5,state.mute,null);}finally{WindowsNative.release(state.volume);}saved.clear();}
}

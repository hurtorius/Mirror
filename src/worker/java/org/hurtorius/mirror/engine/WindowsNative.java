package org.hurtorius.mirror.engine;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.win32.StdCallLibrary;
import java.util.*;

/** Calls installed Windows APIs through the bundled, established JNA runtime. */
final class WindowsNative {
    interface RuntimeApi extends StdCallLibrary {
        int RoInitialize(int type);
        void RoUninitialize();
        int WindowsCreateString(WString source,int length,PointerByReference result);
        int WindowsDeleteString(Pointer string);
        int RoGetActivationFactory(Pointer className,Pointer iid,PointerByReference result);
    }
    static final RuntimeApi RUNTIME=Native.load("combase",RuntimeApi.class);
    static Pointer guid(String id){Guid.GUID guid=new Guid.GUID(id);guid.write();return guid.getPointer();}
    static int call(Pointer object,int index,Object...arguments){if(object==null)throw new IllegalStateException("Missing Windows interface.");Pointer function=object.getPointer(0).getPointer((long)index*Native.POINTER_SIZE);Object[] args=new Object[arguments.length+1];args[0]=object;System.arraycopy(arguments,0,args,1,arguments.length);return Function.getFunction(function,Function.ALT_CONVENTION).invokeInt(args);}
    static void check(int hr){if(hr<0)throw new IllegalStateException("Windows source API failed (0x"+Integer.toHexString(hr)+").");}
    static Pointer query(Pointer object,String iid){PointerByReference result=new PointerByReference();check(call(object,0,guid(iid),result));return result.getValue();}
    static Pointer factory(String name,String iid){PointerByReference h=new PointerByReference(),result=new PointerByReference();check(RUNTIME.WindowsCreateString(new WString(name),name.length(),h));try{check(RUNTIME.RoGetActivationFactory(h.getValue(),guid(iid),result));return result.getValue();}finally{RUNTIME.WindowsDeleteString(h.getValue());}}
    static void release(Pointer object){if(object!=null)call(object,2);}
    static void close(Pointer object){if(object==null)return;Pointer closable=query(object,"30d5a829-7fa4-4026-83bb-d75bae4ea99e");try{check(call(closable,6));}finally{release(closable);}}
}

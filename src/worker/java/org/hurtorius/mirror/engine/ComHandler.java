package org.hurtorius.mirror.engine;
import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
/** Small strongly-held COM callback used only inside the browser's apartment. */
final class ComHandler {
    interface Query extends StdCallLibrary.StdCallCallback {int invoke(Pointer self,Pointer iid,Pointer out);}
    interface Ref extends StdCallLibrary.StdCallCallback {int invoke(Pointer self);}
    interface Result extends StdCallLibrary.StdCallCallback {int invoke(Pointer self,int error,Pointer value);}
    interface Event extends StdCallLibrary.StdCallCallback {int invoke(Pointer self,Pointer sender,Pointer args);}
    interface Done extends StdCallLibrary.StdCallCallback {int invoke(Pointer self,int error);}
    final Memory object=new Memory(Native.POINTER_SIZE),table=new Memory(Native.POINTER_SIZE*4L);
    final Callback invoke;
    final AtomicInteger refs=new AtomicInteger(1);
    volatile long completedAt=0;
    final Query query;
    final Ref add=self->refs.incrementAndGet(),release=self->refs.decrementAndGet();
    ComHandler(String iid,Callback invoke){this.invoke=invoke;query=(self,id,out)->{if(out==null)return 0x80004003;boolean match=java.util.Arrays.equals(id.getByteArray(0,16),WindowsNative.guid(iid).getByteArray(0,16))||java.util.Arrays.equals(id.getByteArray(0,16),WindowsNative.guid("00000000-0000-0000-c000-000000000046").getByteArray(0,16));out.setPointer(0,match?object:null);if(match)refs.incrementAndGet();return match?0:0x80004002;};table.setPointer(0,CallbackReference.getFunctionPointer(query));table.setPointer(Native.POINTER_SIZE,CallbackReference.getFunctionPointer(add));table.setPointer(Native.POINTER_SIZE*2L,CallbackReference.getFunctionPointer(release));table.setPointer(Native.POINTER_SIZE*3L,CallbackReference.getFunctionPointer(invoke));object.setPointer(0,table);}
}

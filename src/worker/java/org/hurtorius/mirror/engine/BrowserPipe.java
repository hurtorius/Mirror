package org.hurtorius.mirror.engine;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.platform.win32.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native inherited descriptors 3/4 on each OS. No debugger port or shell is opened. */
interface BrowserPipe extends AutoCloseable {
    int read(byte[] bytes)throws IOException;
    void write(byte[] bytes)throws IOException;
    void close();
    static BrowserPipe launch(List<String> args)throws IOException{return Platform.isWindows()?new Windows(args):new Posix(args);}
    static String quote(String value){
        StringBuilder out=new StringBuilder("\"");int slashes=0;
        for(char c:value.toCharArray()){if(c=='\\'){slashes++;continue;}out.append("\\".repeat(c=='"'?slashes*2+1:slashes)).append(c);slashes=0;}
        return out.append("\\".repeat(slashes*2)).append('"').toString();
    }
    final class Windows implements BrowserPipe {
        private WinNT.HANDLE input,output,process;
        Windows(List<String> args)throws IOException{
            var security=new WinBase.SECURITY_ATTRIBUTES();security.bInheritHandle=true;
            var readChild=new WinNT.HANDLEByReference();var writeParent=new WinNT.HANDLEByReference();var readParent=new WinNT.HANDLEByReference();var writeChild=new WinNT.HANDLEByReference();
            try{
                check(Kernel32.INSTANCE.CreatePipe(readChild,writeParent,security,65536));input=writeParent.getValue();
                check(Kernel32.INSTANCE.CreatePipe(readParent,writeChild,security,65536));output=readParent.getValue();
                check(Kernel32.INSTANCE.SetHandleInformation(input,1,0));check(Kernel32.INSTANCE.SetHandleInformation(output,1,0));
                int count=5;Memory table=new Memory(4+count+(long)count*Native.POINTER_SIZE);table.clear();table.setInt(0,count);
                for(int i=0;i<count;i++)table.setPointer(4+count+(long)i*Native.POINTER_SIZE,Pointer.createConstant(-1));
                table.setByte(7,(byte)9);table.setByte(8,(byte)9);
                table.setPointer(4+count+3L*Native.POINTER_SIZE,readChild.getValue().getPointer());table.setPointer(4+count+4L*Native.POINTER_SIZE,writeChild.getValue().getPointer());
                var startup=new WinBase.STARTUPINFO();startup.cbReserved2=new WinDef.WORD((int)table.size());startup.lpReserved2=new ByteByReference();startup.lpReserved2.setPointer(table);startup.dwFlags=1;startup.wShowWindow=new WinDef.WORD(0);
                String environment=System.getenv().entrySet().stream().filter(e->!e.getKey().equals("MIRROR_ENGINE_TOKEN")).sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)).map(e->e.getKey()+"="+e.getValue()+"\0").reduce("",String::concat)+"\0";
                byte[] env=environment.getBytes(StandardCharsets.UTF_16LE);Memory block=new Memory(env.length);block.write(0,env,0,env.length);
                var info=new WinBase.PROCESS_INFORMATION();String command=String.join(" ",args.stream().map(BrowserPipe::quote).toList());
                check(Kernel32.INSTANCE.CreateProcess(null,command,null,null,true,new WinDef.DWORD(0x08000400),block,null,startup,info));process=info.hProcess;closeHandle(info.hThread);
            }catch(IOException e){close();throw e;}finally{closeHandle(readChild.getValue());closeHandle(writeChild.getValue());}
        }
        private static void check(boolean ok)throws IOException{if(!ok)throw new IOException("Browser pipe failed (Windows "+Kernel32.INSTANCE.GetLastError()+").");}
        public int read(byte[] bytes)throws IOException{var count=new IntByReference();if(output==null||!Kernel32.INSTANCE.ReadFile(output,bytes,bytes.length,count,null))return -1;return count.getValue();}
        public void write(byte[] bytes)throws IOException{var count=new IntByReference();if(input==null||!Kernel32.INSTANCE.WriteFile(input,bytes,bytes.length,count,null)||count.getValue()!=bytes.length)throw new IOException("The browser stopped responding.");}
        private static void closeHandle(WinNT.HANDLE handle){if(handle!=null)Kernel32.INSTANCE.CloseHandle(handle);}
        public synchronized void close(){closeHandle(input);input=null;if(process!=null){if(Kernel32.INSTANCE.WaitForSingleObject(process,1500)==258)Kernel32.INSTANCE.TerminateProcess(process,0);closeHandle(process);process=null;}closeHandle(output);output=null;}
    }
    final class Posix implements BrowserPipe {
        interface LibC extends Library {
            int pipe(int[] descriptors);
            int fcntl(int fd,int command,Object... values);
            int close(int fd);
            long read(int fd,byte[] bytes,long size);
            long write(int fd,byte[] bytes,long size);
            int posix_spawn_file_actions_init(Pointer actions);
            int posix_spawn_file_actions_adddup2(Pointer actions,int fd,int target);
            int posix_spawn_file_actions_addclose(Pointer actions,int fd);
            int posix_spawn_file_actions_destroy(Pointer actions);
            int posix_spawn(IntByReference pid,String path,Pointer actions,Pointer attributes,StringArray argv,StringArray env);
            int kill(int pid,int signal);
            int waitpid(int pid,IntByReference status,int options);
        }
        private final LibC libc=Native.load(Platform.C_LIBRARY_NAME,LibC.class);
        private int input=-1,output=-1,pid=-1;
        Posix(List<String> args)throws IOException{
            int[] toChild={-1,-1},fromChild={-1,-1};Memory actions=new Memory(1024);boolean initialized=false;
            try{
                toChild=pipe();fromChild=pipe();input=toChild[1];output=fromChild[0];check(libc.posix_spawn_file_actions_init(actions));initialized=true;
                check(libc.posix_spawn_file_actions_adddup2(actions,toChild[0],3));check(libc.posix_spawn_file_actions_adddup2(actions,fromChild[1],4));
                for(int fd:new int[]{toChild[0],toChild[1],fromChild[0],fromChild[1]})check(libc.posix_spawn_file_actions_addclose(actions,fd));
                String[] env=System.getenv().entrySet().stream().filter(e->!e.getKey().equals("MIRROR_ENGINE_TOKEN")).map(e->e.getKey()+"="+e.getValue()).toArray(String[]::new);
                IntByReference child=new IntByReference();check(libc.posix_spawn(child,args.getFirst(),actions,null,new StringArray(args.toArray(String[]::new)),new StringArray(env)));pid=child.getValue();
            }catch(IOException e){close();throw e;}finally{if(initialized)libc.posix_spawn_file_actions_destroy(actions);closeFd(toChild[0]);closeFd(fromChild[1]);}
        }
        private int[] pipe()throws IOException{
            int[] pair={-1,-1};if(libc.pipe(pair)!=0)throw new IOException("Browser pipe could not open.");int[] owned={-1,-1};
            try{for(int i=0;i<2;i++){owned[i]=libc.fcntl(pair[i],0,10);if(owned[i]<0||libc.fcntl(owned[i],2,1)<0)throw new IOException("Browser descriptor could not be prepared.");}return owned;}
            catch(IOException e){for(int fd:owned)closeFd(fd);throw e;}finally{for(int fd:pair)closeFd(fd);}
        }
        private static void check(int error)throws IOException{if(error!=0)throw new IOException("Browser launch failed (system "+error+").");}
        private void closeFd(int fd){if(fd>=0)libc.close(fd);}
        public int read(byte[] bytes)throws IOException{if(output<0)return -1;long count=libc.read(output,bytes,bytes.length);if(count<0){if(Native.getLastError()==4)return 0;throw new IOException("The browser pipe closed.");}return count==0?-1:(int)count;}
        public void write(byte[] bytes)throws IOException{
            int offset=0;while(offset<bytes.length){if(input<0)throw new IOException("The browser is closed.");byte[] part=offset==0?bytes:Arrays.copyOfRange(bytes,offset,bytes.length);long count=libc.write(input,part,part.length);if(count<0&&Native.getLastError()==4)continue;if(count<=0)throw new IOException("The browser stopped responding.");offset+=(int)count;}
        }
        public synchronized void close(){
            closeFd(input);input=-1;
            if(pid>0){int child=pid;pid=-1;var status=new IntByReference();long deadline=System.nanoTime()+1_500_000_000L;int result;
                while((result=libc.waitpid(child,status,1))==0&&System.nanoTime()<deadline){try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
                if(result==0){libc.kill(child,15);long end=System.nanoTime()+500_000_000L;while((result=libc.waitpid(child,status,1))==0&&System.nanoTime()<end)Thread.onSpinWait();if(result==0){libc.kill(child,9);libc.waitpid(child,status,0);}}
            }
            closeFd(output);output=-1;
        }
    }
}

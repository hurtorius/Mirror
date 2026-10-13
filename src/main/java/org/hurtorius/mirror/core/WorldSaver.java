package org.hurtorius.mirror.core;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Writes world.json off the game thread, in order and newest first: a snapshot still waiting when a newer one arrives is never written, and finishes with the newer one. */
public final class WorldSaver {
    private static final Executor THREAD=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Mirror world saver");t.setDaemon(true);return t;});
    private record Write(Path file,byte[] json,CompletableFuture<Void> done){}
    private final Executor executor;
    private final AtomicReference<Write> pending=new AtomicReference<>();
    public WorldSaver(){this(THREAD);}
    WorldSaver(Executor executor){this.executor=executor;}
    /** Completes once this snapshot, or the newer one that replaced it, is on disk; AtomicFile keeps the old file whole if the write fails. */
    public CompletableFuture<Void> write(Path file,byte[] json){
        Write next=new Write(file,json,new CompletableFuture<>()),replaced=pending.getAndSet(next);
        if(replaced!=null)next.done.whenComplete((ok,error)->{if(error==null)replaced.done.complete(null);else replaced.done.completeExceptionally(error);});
        executor.execute(this::drain);return next.done;
    }
    /** Returns once every write queued before it has finished. */
    public void flush()throws InterruptedException{var marker=new FutureTask<Void>(()->{},null);executor.execute(marker);try{marker.get();}catch(ExecutionException e){throw new IllegalStateException(e);}}
    private void drain(){Write w=pending.getAndSet(null);if(w==null)return;try{AtomicFile.write(w.file,w.json);w.done.complete(null);}catch(Throwable e){w.done.completeExceptionally(e);}}
}

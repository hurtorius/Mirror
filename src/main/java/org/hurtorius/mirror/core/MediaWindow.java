package org.hurtorius.mirror.core;

import java.util.*;

/** Ack-clocked world-media sending for one player: never more than WINDOW chunks past the last position the client confirmed. */
public final class MediaWindow {
    public static final int WINDOW=16,ACK=4;
    public final String media;
    private final long size;
    private long cursor,acked;
    private MediaWindow(String media,long size,long offset){this.media=media;this.size=size;cursor=acked=offset;}
    /** A confirmed position inside the current transfer slides its window; a start, another file or any other position sends from there. */
    public static MediaWindow request(MediaWindow current,String media,long size,long offset,boolean start){
        if(!start&&current!=null&&current.media.equals(media)&&current.size==size&&offset>=current.acked&&offset<=current.cursor){current.acked=offset;return current;}
        return new MediaWindow(media,size,offset);
    }
    /** The next chunk's offset, or -1 while the window is full or everything was sent. */
    public long next(){if(cursor>=size||cursor-acked>=(long)WINDOW*MediaRepository.CHUNK)return -1;long at=cursor;cursor=Math.min(size,cursor+MediaRepository.CHUNK);return at;}
    public boolean done(){return cursor>=size;}
    /** One tick's chunks: a round gives each window one in turn, from the window at first, until the budget or the rounds run out. Moving first each tick serves every window when more wait than the budget covers. */
    public static List<List<Long>> share(List<MediaWindow> windows,int first,int budget,int rounds){
        List<List<Long>> offsets=new ArrayList<>();for(int i=0;i<windows.size();i++)offsets.add(new ArrayList<>());
        for(int round=0;round<rounds;round++)for(int i=0;i<windows.size()&&budget>0;i++){int at=Math.floorMod(first+i,windows.size());long next=windows.get(at).next();if(next>=0){offsets.get(at).add(next);budget--;}}
        return offsets;
    }
}

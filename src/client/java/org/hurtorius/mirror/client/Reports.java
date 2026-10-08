package org.hurtorius.mirror.client;

import java.util.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Small in-memory report history. No page contents, cookies or credentials are collected. */
final class Reports {
    private static final ArrayDeque<String> entries=new ArrayDeque<>();
    private static String last="";
    private static long at;
    static synchronized void add(String message){
        if(message==null||message.isBlank())return;long now=System.currentTimeMillis();
        if(message.equals(last)&&now-at<10000)return;
        last=message;at=now;if(entries.size()==100)entries.removeFirst();
        entries.addLast(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"  "+message.substring(0,Math.min(1200,message.length())));
    }
    static synchronized List<String> list(){return List.copyOf(entries).reversed();}
    static synchronized void clear(){entries.clear();last="";}
}

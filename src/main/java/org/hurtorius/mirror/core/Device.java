package org.hurtorius.mirror.core;
/** Public machine appearance only; carries no source, audience or editor details. */
public record Device(String id,int x,int y,int z,String mood,long createdMillis,boolean active){}

package org.hurtorius.mirror.core;

/** Keep the selected movie stable when removing a different playlist entry. */
public final class PlaylistEdits {
    private PlaylistEdits(){}
    public static void remove(ScreenSpec spec,String id){
        int previous=spec.item;
        String selected=spec.playlist.isEmpty()?"":spec.playlist.get(Math.min(previous,spec.playlist.size()-1));
        if(!spec.playlist.remove(id))return;
        spec.branches.removeIf(branch->branch.media().equals(id));
        spec.item=selected.equals(id)?Math.min(previous,Math.max(0,spec.playlist.size()-1)):Math.max(0,spec.playlist.indexOf(selected));
    }
}

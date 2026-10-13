package org.hurtorius.mirror.core;

/** A browser and a window capture can coexist at one Initiator, but never share a grant. */
public final class PublicationSource {
    private PublicationSource(){}
    public static boolean forwards(boolean browser,String grant){return browser?"WEB".equals(grant):"SHARE".equals(grant);}
}

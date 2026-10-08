package org.hurtorius.mirror.client;

/** A restarted stream can reuse a frame number, but must never reuse the old stream's styled pixels. */
record PictureStyleKey(Object source,long version,int resolution,double brightness,double contrast,double saturation) { }

package org.hurtorius.mirror.engine;
import org.bytedeco.javacv.FFmpegFrameGrabber;
/** World blobs are standalone local media, not playlists that may open other files/URLs. */
final class LocalMediaDecoder {
    static void restrict(FFmpegFrameGrabber grabber,boolean image){
        grabber.setOption("protocol_whitelist","file");
        grabber.setOption("format_whitelist",image?"png_pipe,jpeg_pipe,webp_pipe,gif":"mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,avi,mp3,wav,ogg,flac");
        grabber.setOption("enable_drefs","0");
    }
}

package org.hurtorius.mirror.client;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
/** Raster fallback for the optional OBS export. In-world text uses NativeScreenText. */
final class TextSource {
    static void render(String id,String text){
        BufferedImage image=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(Color.BLACK);g.fillRect(0,0,1280,720);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif",Font.PLAIN,42));var fm=g.getFontMetrics();
        var lines=new ArrayList<String>();StringBuilder line=new StringBuilder();
        for(int cp:text.codePoints().toArray()){if(cp=='\n'||fm.stringWidth(line.toString()+new String(Character.toChars(cp)))>1140){lines.add(line.toString());line.setLength(0);if(lines.size()>=12)break;}if(cp!='\n')line.appendCodePoint(cp);}
        if(lines.size()<12)lines.add(line.toString());g.setColor(new Color(0xf0f0f0));
        int y=Math.max(70,(720-lines.size()*50)/2+40);for(String row:lines){g.drawString(row,(1280-fm.stringWidth(row))/2,y);y+=50;}
        g.dispose();MirrorClient.submitImage(id,image);
    }
}

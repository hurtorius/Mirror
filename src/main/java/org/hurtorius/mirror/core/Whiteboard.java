package org.hurtorius.mirror.core;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/** Raster whiteboard with bounded undo/redo and one reversible gesture at a time. */
public final class Whiteboard {
    public enum Tool { PEN, MARKER, ERASER, LINE, ARROW, RECTANGLE, ELLIPSE, FILL, TEXT, PICK_COLOR, SELECT, PAN }
    public final BufferedImage image;
    public int color=0x202020,size=5;
    public boolean filled;
    public String text="";
    public Tool tool=Tool.PEN;
    public Rectangle selection;
    private final ArrayDeque<int[]> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private int[] before;
    private int startX,startY,lastX,lastY;
    private Rectangle moving;
    public Whiteboard(int width,int height){image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);paintWhite();}
    private void paintWhite(){var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());g.dispose();}
    private int[] pixels(){return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());}
    private void restore(int[] pixels){image.setRGB(0,0,image.getWidth(),image.getHeight(),pixels,0,image.getWidth());}
    private static void push(ArrayDeque<int[]> history,int[] image){if(history.size()==12)history.removeFirst();history.addLast(image);}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public void undo(){cancel();if(canUndo()){push(redo,pixels());restore(undo.removeLast());selection=null;}}
    public void redo(){cancel();if(canRedo()){push(undo,pixels());restore(redo.removeLast());selection=null;}}
    public void clear(){cancel();push(undo,pixels());redo.clear();selection=null;paintWhite();}
    public BufferedImage snapshot(){var copy=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);var g=copy.createGraphics();g.drawImage(image,0,0,null);g.dispose();return copy;}
    public void load(BufferedImage source){var g=image.createGraphics();g.drawImage(source,0,0,image.getWidth(),image.getHeight(),null);g.dispose();undo.clear();redo.clear();selection=null;}
    public void begin(int x,int y){
        cancel();x=clampX(x);y=clampY(y);startX=lastX=x;startY=lastY=y;
        if(tool==Tool.PAN)return;
        if(tool==Tool.PICK_COLOR){color=image.getRGB(x,y)&0xffffff;return;}
        moving=tool==Tool.SELECT&&selection!=null&&selection.contains(x,y)?new Rectangle(selection):null;
        before=pixels();
        if(tool==Tool.FILL)fill(x,y);
        else if(tool==Tool.TEXT){var g=graphics();g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,Math.max(12,size*4)));g.drawString(text,x,y);g.dispose();}
        else drag(x,y);
    }
    public void drag(int x,int y){
        if(before==null)return;x=clampX(x);y=clampY(y);
        if(tool==Tool.FILL||tool==Tool.TEXT)return;
        boolean stroke=tool==Tool.PEN||tool==Tool.ERASER||tool==Tool.MARKER;
        if(!stroke)restore(before);
        var g=graphics();int left=Math.min(startX,x),top=Math.min(startY,y),w=Math.abs(x-startX),h=Math.abs(y-startY);
        switch(tool){
            case PEN,MARKER,ERASER->g.drawLine(lastX,lastY,x,y);
            case LINE,ARROW->{g.drawLine(startX,startY,x,y);if(tool==Tool.ARROW){double a=Math.atan2(y-startY,x-startX),len=Math.max(14,size*4);for(int sign:new int[]{-1,1})g.drawLine(x,y,(int)(x-len*Math.cos(a+sign*.5)),(int)(y-len*Math.sin(a+sign*.5)));}}
            case RECTANGLE->{if(filled)g.fillRect(left,top,w,h);else g.drawRect(left,top,w,h);}
            case ELLIPSE->{if(filled)g.fillOval(left,top,w,h);else g.drawOval(left,top,w,h);}
            case SELECT->{if(moving==null)selection=new Rectangle(left,top,Math.max(1,w),Math.max(1,h));else{
                int dx=Math.clamp(x-startX,-moving.x,image.getWidth()-moving.x-moving.width),dy=Math.clamp(y-startY,-moving.y,image.getHeight()-moving.y-moving.height);
                var part=new BufferedImage(moving.width,moving.height,BufferedImage.TYPE_INT_RGB);part.setRGB(0,0,moving.width,moving.height,before,moving.y*image.getWidth()+moving.x,image.getWidth());
                g.setColor(Color.WHITE);g.fill(moving);g.drawImage(part,moving.x+dx,moving.y+dy,null);selection=new Rectangle(moving.x+dx,moving.y+dy,moving.width,moving.height);
            }}
            default->{}
        }
        g.dispose();lastX=x;lastY=y;
    }
    public void end(){if(before!=null&&!(tool==Tool.SELECT&&moving==null)){push(undo,before);redo.clear();}before=null;moving=null;}
    public void cancel(){if(before!=null)restore(before);before=null;moving=null;}
    private Graphics2D graphics(){var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(tool==Tool.ERASER?0xffffff:color));if(tool==Tool.MARKER)g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,.2f));g.setStroke(new BasicStroke(size,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));return g;}
    private int clampX(int x){return Math.clamp(x,0,image.getWidth()-1);}
    private int clampY(int y){return Math.clamp(y,0,image.getHeight()-1);}
    private void fill(int x,int y){
        int w=image.getWidth(),h=image.getHeight(),target=image.getRGB(x,y),replacement=0xff000000|color;if(target==replacement)return;
        int[] queue=new int[w*h];int head=0,tail=0;queue[tail++]=y*w+x;image.setRGB(x,y,replacement);
        while(head<tail){int n=queue[head++],px=n%w,py=n/w;for(int d=0;d<4;d++){int nx=px+(d==0?-1:d==1?1:0),ny=py+(d==2?-1:d==3?1:0);if(nx>=0&&ny>=0&&nx<w&&ny<h&&image.getRGB(nx,ny)==target){image.setRGB(nx,ny,replacement);queue[tail++]=ny*w+nx;}}}
    }
}

package org.hurtorius.mirror.core;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Raster whiteboard with bounded undo/redo and one reversible gesture at a time. History keeps only the changed rectangle. */
public final class Whiteboard {
    public enum Tool { PEN, MARKER, ERASER, LINE, ARROW, RECTANGLE, ELLIPSE, FILL, TEXT, PICK_COLOR, SELECT, PAN }
    private record Patch(Rectangle area,int[] pixels){}
    public final BufferedImage image;
    public int color=0x202020,size=5;
    public boolean filled;
    public String text="";
    public Tool tool=Tool.PEN;
    public Rectangle selection;
    private final ArrayDeque<Patch> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final int[] data,before;
    private final Rectangle canvas;
    private Rectangle touched;
    private int[] queue;
    private int startX,startY,lastX,lastY;
    private Rectangle moving;
    public Whiteboard(int width,int height){image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);data=((DataBufferInt)image.getRaster().getDataBuffer()).getData();before=new int[data.length];canvas=new Rectangle(width,height);paintWhite();}
    private void paintWhite(){Arrays.fill(data,0xffffff);}
    private int[] crop(int[] source,Rectangle r){int w=image.getWidth();int[] p=new int[r.width*r.height];for(int row=0;row<r.height;row++)System.arraycopy(source,(r.y+row)*w+r.x,p,row*r.width,r.width);return p;}
    private void write(Patch p){int w=image.getWidth();var r=p.area;for(int row=0;row<r.height;row++)System.arraycopy(p.pixels,row*r.width,data,(r.y+row)*w+r.x,r.width);}
    private void revert(Rectangle r){int w=image.getWidth();for(int row=r.y;row<r.y+r.height;row++)System.arraycopy(before,row*w+r.x,data,row*w+r.x,r.width);}
    private void touch(Rectangle r){touched=touched.union(r).intersection(canvas);}
    private static Rectangle span(int ax,int ay,int bx,int by,int pad){return new Rectangle(Math.min(ax,bx)-pad,Math.min(ay,by)-pad,Math.abs(bx-ax)+2*pad+1,Math.abs(by-ay)+2*pad+1);}
    private static void push(ArrayDeque<Patch> history,Patch patch){if(history.size()==12)history.removeFirst();history.addLast(patch);}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public void undo(){cancel();if(canUndo())swap(undo,redo);}
    public void redo(){cancel();if(canRedo())swap(redo,undo);}
    private void swap(ArrayDeque<Patch> from,ArrayDeque<Patch> to){Patch p=from.removeLast();push(to,new Patch(p.area,crop(data,p.area)));write(p);selection=null;}
    public void clear(){cancel();push(undo,new Patch(canvas,data.clone()));redo.clear();selection=null;paintWhite();}
    public BufferedImage snapshot(){var copy=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);System.arraycopy(data,0,((DataBufferInt)copy.getRaster().getDataBuffer()).getData(),0,data.length);return copy;}
    public void load(BufferedImage source){var g=image.createGraphics();g.drawImage(source,0,0,image.getWidth(),image.getHeight(),null);g.dispose();undo.clear();redo.clear();selection=null;}
    public void begin(int x,int y){
        cancel();x=clampX(x);y=clampY(y);startX=lastX=x;startY=lastY=y;
        if(tool==Tool.PAN)return;
        if(tool==Tool.PICK_COLOR){color=data[y*image.getWidth()+x]&0xffffff;return;}
        moving=tool==Tool.SELECT&&selection!=null&&selection.contains(x,y)?new Rectangle(selection):null;
        System.arraycopy(data,0,before,0,data.length);touched=new Rectangle(x,y,1,1);
        if(tool==Tool.FILL)fill(x,y);
        else if(tool==Tool.TEXT){var g=graphics();g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,Math.max(12,size*4)));g.drawString(text,x,y);var bounds=g.getFontMetrics().getStringBounds(text,g).getBounds();bounds.translate(x,y);bounds.add(g.getFont().createGlyphVector(g.getFontRenderContext(),text).getPixelBounds(null,x,y));g.dispose();bounds.grow(4,4);touch(bounds);}
        else drag(x,y);
    }
    public void drag(int x,int y){
        if(touched==null)return;x=clampX(x);y=clampY(y);
        if(tool==Tool.FILL||tool==Tool.TEXT)return;
        boolean stroke=tool==Tool.PEN||tool==Tool.ERASER||tool==Tool.MARKER;
        if(!stroke)revert(touched);
        var g=graphics();int left=Math.min(startX,x),top=Math.min(startY,y),w=Math.abs(x-startX),h=Math.abs(y-startY),pad=size+2;
        switch(tool){
            case PEN,MARKER,ERASER->{g.drawLine(lastX,lastY,x,y);touch(span(lastX,lastY,x,y,pad));}
            case LINE,ARROW->{g.drawLine(startX,startY,x,y);int head=0;if(tool==Tool.ARROW){double a=Math.atan2(y-startY,x-startX),len=Math.max(14,size*4);head=(int)Math.ceil(len);for(int sign:new int[]{-1,1})g.drawLine(x,y,(int)(x-len*Math.cos(a+sign*.5)),(int)(y-len*Math.sin(a+sign*.5)));}touch(span(startX,startY,x,y,pad+head));}
            case RECTANGLE->{if(filled)g.fillRect(left,top,w,h);else g.drawRect(left,top,w,h);touch(span(left,top,left+w,top+h,pad));}
            case ELLIPSE->{if(filled)g.fillOval(left,top,w,h);else g.drawOval(left,top,w,h);touch(span(left,top,left+w,top+h,pad));}
            case SELECT->{if(moving==null)selection=new Rectangle(left,top,Math.max(1,w),Math.max(1,h));else{
                int dx=Math.clamp(x-startX,-moving.x,image.getWidth()-moving.x-moving.width),dy=Math.clamp(y-startY,-moving.y,image.getHeight()-moving.y-moving.height);
                var part=new BufferedImage(moving.width,moving.height,BufferedImage.TYPE_INT_RGB);part.setRGB(0,0,moving.width,moving.height,before,moving.y*image.getWidth()+moving.x,image.getWidth());
                g.setColor(Color.WHITE);g.fill(moving);g.drawImage(part,moving.x+dx,moving.y+dy,null);selection=new Rectangle(moving.x+dx,moving.y+dy,moving.width,moving.height);touch(moving.union(selection));
            }}
            default->{}
        }
        g.dispose();lastX=x;lastY=y;
    }
    public void end(){if(touched!=null&&!(tool==Tool.SELECT&&moving==null)){push(undo,new Patch(touched,crop(before,touched)));redo.clear();}touched=null;moving=null;}
    public void cancel(){if(touched!=null)revert(touched);touched=null;moving=null;}
    private Graphics2D graphics(){var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(tool==Tool.ERASER?0xffffff:color));if(tool==Tool.MARKER)g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,.2f));g.setStroke(new BasicStroke(size,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));return g;}
    private int clampX(int x){return Math.clamp(x,0,image.getWidth()-1);}
    private int clampY(int y){return Math.clamp(y,0,image.getHeight()-1);}
    private void fill(int x,int y){
        int w=image.getWidth(),h=image.getHeight(),start=y*w+x,target=data[start]&0xffffff,replacement=color&0xffffff;if(target==replacement)return;
        if(queue==null)queue=new int[data.length];
        int head=0,tail=0,minX=x,maxX=x,minY=y,maxY=y;queue[tail++]=start;data[start]=replacement;
        while(head<tail){int n=queue[head++],px=n%w,py=n/w;minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);
            for(int d=0;d<4;d++){int nx=px+(d==0?-1:d==1?1:0),ny=py+(d==2?-1:d==3?1:0),i=ny*w+nx;if(nx>=0&&ny>=0&&nx<w&&ny<h&&(data[i]&0xffffff)==target){data[i]=replacement;queue[tail++]=i;}}}
        touch(new Rectangle(minX,minY,maxX-minX+1,maxY-minY+1));
    }
}

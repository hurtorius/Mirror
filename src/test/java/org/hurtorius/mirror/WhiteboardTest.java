package org.hurtorius.mirror;
import org.hurtorius.mirror.core.Whiteboard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhiteboardTest {
    private int pixel(Whiteboard b,int x,int y){return b.image.getRGB(x,y)&0xffffff;}
    @Test void shapesDoNotLeaveDragTrailsAndUndoRedoRestorePixels(){
        var b=new Whiteboard(100,100);b.tool=Whiteboard.Tool.RECTANGLE;b.filled=true;b.color=0xff0000;
        b.begin(10,10);b.drag(90,90);b.drag(30,30);b.end();assertEquals(0xffffff,pixel(b,80,80));assertEquals(0xff0000,pixel(b,20,20));
        b.undo();assertEquals(0xffffff,pixel(b,20,20));b.redo();assertEquals(0xff0000,pixel(b,20,20));
    }
    @Test void fillRespectsClosedBoundaryAndPickingChangesOnlyInk(){
        var b=new Whiteboard(100,100);b.tool=Whiteboard.Tool.RECTANGLE;b.color=0;b.begin(10,10);b.drag(90,90);b.end();
        b.tool=Whiteboard.Tool.FILL;b.color=0x0088ff;b.begin(50,50);b.end();assertEquals(0x0088ff,pixel(b,50,50));assertEquals(0xffffff,pixel(b,0,0));
        b.tool=Whiteboard.Tool.PICK_COLOR;b.begin(10,10);assertEquals(0,b.color);assertEquals(0x0088ff,pixel(b,50,50));
    }
    @Test void selectionMovesWithoutTrailsAndCanBeUndone(){
        var b=new Whiteboard(100,100);b.tool=Whiteboard.Tool.RECTANGLE;b.filled=true;b.color=0xff0000;b.begin(10,10);b.drag(30,30);b.end();
        b.tool=Whiteboard.Tool.SELECT;b.begin(5,5);b.drag(35,35);b.end();b.begin(20,20);b.drag(50,50);b.drag(60,60);b.end();
        assertEquals(0xffffff,pixel(b,20,20));assertEquals(0xff0000,pixel(b,60,60));b.undo();assertEquals(0xff0000,pixel(b,20,20));assertEquals(0xffffff,pixel(b,60,60));
    }
    @Test void cancelAndNewStrokeKeepHistoryConsistent(){
        var b=new Whiteboard(100,100);b.begin(10,10);b.drag(80,80);b.cancel();assertEquals(0xffffff,pixel(b,20,20));assertFalse(b.canUndo());
        b.begin(20,20);b.end();b.undo();assertTrue(b.canRedo());b.begin(30,30);b.end();assertFalse(b.canRedo());
    }
}

package org.hurtorius.mirror.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PictureStyleKeyTest {
    @Test void replacedStreamCannotReuseThePreviousStyledImageAtTheSameFrameNumber(){
        Object source=new Object(),replacement=new Object();
        var first=new PictureStyleKey(source,1,1280,.8,1,1);
        assertEquals(first,new PictureStyleKey(source,1,1280,.8,1,1));
        assertNotEquals(first,new PictureStyleKey(replacement,1,1280,.8,1,1));
        assertNotEquals(first,new PictureStyleKey(source,2,1280,.8,1,1));
        assertNotEquals(first,new PictureStyleKey(source,1,1280,1,1,1));
    }
}

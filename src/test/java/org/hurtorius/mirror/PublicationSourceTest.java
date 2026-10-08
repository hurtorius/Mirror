package org.hurtorius.mirror;
import org.hurtorius.mirror.core.PublicationSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PublicationSourceTest {
    @Test void privateBrowserCannotUseAnotherSourceGrantAtTheSameDevice(){
        assertFalse(PublicationSource.forwards(true,"SHARE"));assertFalse(PublicationSource.forwards(true,"WHITEBOARD"));assertFalse(PublicationSource.forwards(true,null));assertTrue(PublicationSource.forwards(true,"WEB"));
        assertTrue(PublicationSource.forwards(false,"SHARE"));assertFalse(PublicationSource.forwards(false,"WEB"));
    }
}

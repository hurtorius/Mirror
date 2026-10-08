package org.hurtorius.mirror;
import org.hurtorius.mirror.core.PublicationIntent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class PublicationIntentTest {
    @Test void serverCannotCreateOrReplayConsent(){var p=new PublicationIntent();assertFalse(p.accept("a","WEB",0));p.request("a","WEB",0);assertFalse(p.accept("b","WEB",1));assertTrue(p.accept("a","WEB",1));assertFalse(p.accept("a","WEB",2));}
    @Test void sourceExpiryStopAndDisconnectFailClosed(){var p=new PublicationIntent();p.request("a","WEB",0);assertFalse(p.accept("a","WHITEBOARD",1));p.request("a","WEB",0);assertFalse(p.accept("a","WEB",15001));p.request("a","WEB",0);p.cancel("a");assertFalse(p.accept("a","WEB",1));p.request("a","WEB",0);p.clear();assertFalse(p.accept("a","WEB",1));}
    @Test void remoteMediaCannotNameALocalFile(){var id="00000000-0000-0000-0000-000000000001";assertThrows(IllegalArgumentException.class,()->org.hurtorius.mirror.core.WorldStore.validateMedia(new org.hurtorius.mirror.core.WorldStore.Media(id,"C:/private.png","image.png","","image",100,id)));assertThrows(IllegalArgumentException.class,()->org.hurtorius.mirror.core.WorldStore.validateMedia(new org.hurtorius.mirror.core.WorldStore.Media(id,"../private.png","image.png","","image",100,id)));}
}

package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
final class MergedCoreTest {
    @TempDir Path dir;
    static final String A="00000000-0000-0000-0000-000000000001",B="00000000-0000-0000-0000-000000000002",C="00000000-0000-0000-0000-000000000003";
    @Test void inviteCannotBeAppliedToAnotherScreenOrConsumedByThatAttempt(){
        Consent c=new Consent();var r=c.request(A,B,C,"nearby",1000);
        assertThrows(IllegalArgumentException.class,()->c.accept(r.id(),B,C,"nearby",1001));
        assertEquals(A,c.accept(r.id(),A,C,"nearby",1002).screen());
    }
    @Test void newBlockStaysOffAndBranchesRequireDistinctSidesAndPlayableReferences(){
        ScreenSpec s=new ScreenSpec();assertFalse(s.live);assertFalse(s.triggersArmed);
        s.branches.add(new ScreenSpec.Branch("NORTH",A));assertThrows(IllegalArgumentException.class,s::validate);
        s.playlist.add(A);assertDoesNotThrow(s::validate);
        s.branches.add(new ScreenSpec.Branch("NORTH",A));assertThrows(IllegalArgumentException.class,s::validate);
        s.branches.clear();s.show.add(new ScreenSpec.Cue("pitch","",0,91));assertThrows(IllegalArgumentException.class,s::validate);
        s.show.set(0,new ScreenSpec.Cue("z","",2,-128));assertDoesNotThrow(s::validate);
    }
    @Test void drawingSurvivesWorldReloadAndCannotExceedQuota()throws Exception{
        var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"jpeg",bytes);
        MediaRepository repo=new MediaRepository(dir.resolve("media"));byte[] jpeg=bytes.toByteArray();
        assertThrows(java.io.IOException.class,()->repo.storeBoard("",A,"Drawing",jpeg,1));
        var board=repo.storeBoard("",A,"Drawing",jpeg,100000);var store=new WorldStore();Anchor anchor=new Anchor();anchor.owner=A;anchor.boardMedia=board.id();anchor.spec.source=ScreenSpec.Source.WHITEBOARD;anchor.spec.live=true;store.anchors.put(anchor.id,anchor);store.media.put(board.id(),board);store.save(dir.resolve("world.json"));
        var restored=WorldStore.load(dir.resolve("world.json"));assertEquals(board.id(),restored.anchors.get(anchor.id).boardMedia);assertTrue(restored.anchors.get(anchor.id).spec.live);assertArrayEquals(jpeg,repo.read(board.hash(),0,28000));
        var updated=repo.storeBoard(board.id(),A,"Drawing",jpeg,100000);assertEquals(board.id(),updated.id());assertEquals(board.hash(),updated.hash());
    }
    @Test void seekingClearsCompletionOnlyForAValidSeek(){Anchor a=new Anchor();a.finished=true;assertThrows(IllegalArgumentException.class,()->a.seek(-1,1000));assertTrue(a.finished);a.seek(0,1000);assertFalse(a.finished);}
}

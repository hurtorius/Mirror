package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FramePlayoutTest {
    @Test void tickBatchesArePlayedAtTheirOriginalCadence(){
        var queue=new FramePlayout();long seq=0;byte[][] pictures={{1},{2},{3}};
        for(int i=0;i<3;i++){seq=FrameSequence.next(seq,i*16_666_667L);queue.offer(seq,pictures[i],50_000_000,60);}
        assertNull(queue.poll(99_000_000));assertArrayEquals(pictures[0],queue.poll(100_000_000));assertNull(queue.poll(110_000_000));
        assertArrayEquals(pictures[1],queue.poll(116_666_667));assertArrayEquals(pictures[2],queue.poll(133_333_334));assertNull(queue.poll(150_000_000));
    }
    @Test void thirtyFpsVideoDoesNotBecomeSixtyFpsBursts(){
        var queue=new FramePlayout();long seq=FrameSequence.next(0,1_000_000_000);queue.offer(seq,new byte[]{1},0,60);
        queue.offer(FrameSequence.next(seq,1_033_333_333),new byte[]{2},0,60);
        assertNotNull(queue.poll(50_000_000));assertNull(queue.poll(67_000_000));assertNotNull(queue.poll(84_000_000));
    }
    @Test void overloadAndForgedFutureTimesStayBounded(){
        var queue=new FramePlayout();long seq=0;
        for(int i=0;i<100;i++){seq=FrameSequence.next(seq,i*16_666_667L);queue.offer(seq,new byte[]{(byte)i},i*16_666_667L,60);assertTrue(queue.size()<=6);}
        assertNotNull(queue.poll(2_000_000_000));
        queue.offer(FrameSequence.next(seq,2_000_000_000_000L),new byte[]{100},2_000_000_000,60);
        assertArrayEquals(new byte[]{100},queue.poll(2_050_000_000));
    }
    @Test void sequenceStaysPositiveAndIncreasing(){long a=FrameSequence.next(0,100);long b=FrameSequence.next(a,50);assertTrue(FrameSequence.timed(a));assertTrue(b>a);assertTrue(b>0);}
    @Test void sixtyFpsSurvivesTwentyHzServerTicks(){var gate=new FrameRateGate();int accepted=0;for(int i=0;i<600;i++)if(gate.allow(Math.round((i*1000.0/60)/50)*50,60))accepted++;assertTrue(accepted>=598,"Accepted "+accepted);}
}

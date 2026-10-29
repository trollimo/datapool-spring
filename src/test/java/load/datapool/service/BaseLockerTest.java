package load.datapool.service;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class BaseLockerTest {

    @Test
    public void firstBiggerUnlockedIdIsStrictlyBigger() {
        BaseLocker l = new BaseLocker("T", 5);
        assertEquals(3, l.firstBiggerUnlockedId(2));
        l.lock(3);
        assertEquals(4, l.firstBiggerUnlockedId(2));
        assertEquals(0, l.firstBiggerUnlockedId(5));
    }

    @Test
    public void firstUnlockIdSkipsLocked() {
        BaseLocker l = new BaseLocker("T", 3);
        l.lock(1);
        l.lock(2);
        assertEquals(3, l.firstUnlockId());
        l.lock(3);
        assertEquals(0, l.firstUnlockId());
    }

    @Test
    public void ensureSizeGrowsButNeverShrinks() {
        BaseLocker l = new BaseLocker("T");
        l.ensureSize(5000);
        assertEquals(5000, l.getSize());
        l.ensureSize(10);
        assertEquals(5000, l.getSize());
        l.lock(5000);
        assertEquals(1, l.lockedCount());
    }

    @Test
    public void resetRebuildsSizeAndLocks() {
        BaseLocker l = new BaseLocker("T", 100);
        l.lock(50);
        long epoch = l.cursorEpoch();
        l.reset(10, Arrays.asList(2, 3));
        assertEquals(10, l.getSize());
        assertEquals(Arrays.asList(2, 3), l.lockedIds());
        assertEquals(1, l.firstUnlockId());
        assertEquals(epoch + 1, l.cursorEpoch());
    }
}

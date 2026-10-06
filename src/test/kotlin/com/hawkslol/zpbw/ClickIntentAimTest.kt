package com.hawkslol.zpbw

import net.minecraft.world.phys.Vec3
import kotlin.test.*

class ClickIntentAimTest {
    @Test fun changedOriginStillAddressesTheExactOriginalWorldPoint() {
        val point=Vec3(0.0,101.0,14.0)
        for(origin in listOf(Vec3(.5,101.32,12.5),Vec3(1.5,101.32,12.5))) {
            val aim=assertNotNull(ClickIntentAim.toward(origin,point))
            // Minecraft's lookup-table sine/cosine can produce a slightly non-unit vector.
            // Compare angular direction independently from that native length approximation.
            val look=Vec3.directionFromRotation(aim.second,aim.first).normalize()
            val expected=point.subtract(origin).normalize()
            assertTrue(look.dot(expected)>.9999999,"origin=$origin aim=$aim look=$look expected=$expected dot=${look.dot(expected)}")
        }
        assertNotEquals(ClickIntentAim.toward(Vec3(.5,101.32,12.5),point),
            ClickIntentAim.toward(Vec3(1.5,101.32,12.5),point))
    }
    @Test fun verticalAndSideClicksRemainFinite() {
        for(point in listOf(Vec3(0.0,5.0,0.0),Vec3(0.0,-5.0,0.0),Vec3(-5.0,0.0,0.0))) {
            val aim=assertNotNull(ClickIntentAim.toward(Vec3.ZERO,point))
            assertTrue(aim.first.isFinite() && aim.second.isFinite())
            assertTrue(Vec3.directionFromRotation(aim.second,aim.first).normalize().dot(point.normalize())>.9999999)
        }
    }
    @Test fun invalidOrDegeneratePointDoesNotCreateAnAim() {
        assertNull(ClickIntentAim.toward(Vec3.ZERO,Vec3.ZERO))
        assertNull(ClickIntentAim.toward(Vec3.ZERO,Vec3(Double.NaN,1.0,0.0)))
    }
}

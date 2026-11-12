package com.carstage.app.vehicle

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_1_LEFT
import ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_1_RIGHT
import ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_2_LEFT
import ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_2_RIGHT
import com.carstage.app.vehicle.SeatOccupancyReader.Companion.allTaken
import com.carstage.app.vehicle.SeatOccupancyReader.Companion.frontSeatAreas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeatOccupancyReaderTest {
    private val driver = SeatArea(ROW_1_LEFT)
    private val passenger = SeatArea(ROW_1_RIGHT)

    @Test
    fun theFrontSeatsAreTheAreasHoldingOneFrontSeatEach() {
        // What the emulator reports (docs/05): one area per seat, two rows.
        val emulator = listOf(driver, passenger, SeatArea(ROW_2_LEFT), SeatArea(ROW_2_RIGHT))
        assertEquals(listOf("ROW_1_LEFT", "ROW_1_RIGHT"), frontSeatAreas(emulator)?.map { it.label() })
    }

    @Test
    fun withoutAnAreaForEachFrontSeatThereAreNoFrontSeats() {
        assertNull("only the driver's seat", frontSeatAreas(listOf(driver, SeatArea(ROW_2_LEFT))))
        assertNull("one area for the whole front row", frontSeatAreas(listOf(SeatArea(ROW_1_LEFT, ROW_1_RIGHT))))
        assertNull("columns, not seats", frontSeatAreas(listOf(SeatArea(ROW_1_LEFT, ROW_2_LEFT), SeatArea(ROW_1_RIGHT, ROW_2_RIGHT))))
        assertNull("nothing reported", frontSeatAreas(emptyList()))
    }

    @Test
    fun twoAreForTheSameSeatAreNotBothFrontSeats() {
        assertNull(frontSeatAreas(listOf(driver, SeatArea(ROW_1_LEFT))))
    }

    @Test
    fun bothSeatsMustBeReadAsTakenToCountAsTwo() {
        assertEquals(true, allTaken(listOf(true, true)))
        assertEquals("a free seat is known even if the other cannot be read", false, allTaken(listOf(null, false)))
        assertEquals(false, allTaken(listOf(true, false)))
        assertNull("one unreadable seat is not two people", allTaken(listOf(true, null)))
        assertNull(allTaken(listOf(null, null)))
    }
}

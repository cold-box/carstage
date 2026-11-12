package com.carstage.app.stage

import com.carstage.app.vehicle.VehicleSession

/**
 * The car as the stage's output, in a build that drives the cabin: debug builds, and release
 * builds once `releaseDrivesCabin` is on (app/build.gradle.kts). Its twin in src/noCabinControl
 * gives none, so a build that leaves the cabin alone does not even carry the control calls.
 */
fun carCabin(session: VehicleSession, report: (String) -> Unit, journal: RestoreJournal, denials: ControlDenials): CarCabin? =
    VehicleCabin(session, report, journal, denials)

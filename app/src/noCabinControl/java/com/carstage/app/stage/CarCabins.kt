package com.carstage.app.stage

import com.carstage.app.vehicle.VehicleSession

/**
 * No car output: a release build that leaves the cabin alone while Pleos App Market has not agreed
 * its CONTROL_CAR_* permissions (`releaseDrivesCabin`, app/build.gradle.kts). Pleos' review fails
 * an app that calls those APIs, so this build carries no code that does (`VehicleCabin` is not in
 * it); its stage drives the on-screen cabin only.
 */
@Suppress("UNUSED_PARAMETER")
fun carCabin(session: VehicleSession, report: (String) -> Unit, journal: RestoreJournal, denials: ControlDenials): CarCabin? = null

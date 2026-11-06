package dev.mote.remote.hid

/** Needs a real phone with Bluetooth on and a bonded host. Opt-in: `./gradlew connectedDebugAndroidTest -PmoteHid`. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class PhysicalHid

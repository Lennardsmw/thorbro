package wtf.lennard.thorbro

import android.view.InputDevice

/** which controller a device is, from its USB/Bluetooth vendor and product id (name as a fallback) */
enum class PadModel(val label: String) {
    THOR("Thor"),
    DUALSENSE("DualSense"),
    DUALSENSE_EDGE("DualSense Edge"),
    DUALSHOCK4("DualShock 4"),
    DUALSHOCK3("DualShock 3"),
    XBOX("Xbox Controller"),
    SWITCH_PRO("Pro Controller"),
    JOYCON_L("Joy-Con (L)"),
    JOYCON_R("Joy-Con (R)"),
    EIGHTBITDO("8BitDo"),
    STEAM("Steam Controller"),
    GENERIC("Controller");

    companion object {
        fun of(d: InputDevice): PadModel = of(d.vendorId, d.productId, d.name ?: "", !d.isExternal)

        fun of(vendor: Int, product: Int, name: String, builtIn: Boolean = false): PadModel {
            if (builtIn || name.contains("Odin", true) || name.contains("Thor", true)) return THOR
            return when (vendor) {
                0x054C -> when (product) {
                    0x0CE6 -> DUALSENSE; 0x0DF2 -> DUALSENSE_EDGE
                    0x05C4, 0x09CC, 0x0BA0 -> DUALSHOCK4; 0x0268 -> DUALSHOCK3
                    else -> byName(name)
                }
                0x045E -> XBOX
                0x057E -> when (product) { 0x2006 -> JOYCON_L; 0x2007 -> JOYCON_R; 0x2009 -> SWITCH_PRO; else -> SWITCH_PRO }
                0x2DC8 -> EIGHTBITDO
                0x28DE -> STEAM   // Valve
                else -> byName(name)
            }
        }

        /** for paired-but-not-connected devices we only know the Bluetooth name */
        fun byName(name: String): PadModel = when {
            name.contains("DualSense Edge", true) -> DUALSENSE_EDGE
            name.contains("DualSense", true) -> DUALSENSE
            name.equals("Wireless Controller", true) -> DUALSHOCK4 // what a DualShock 4 calls itself
            name.contains("PLAYSTATION(R)3", true) -> DUALSHOCK3
            name.contains("Xbox", true) -> XBOX
            name.contains("Joy-Con (L)", true) -> JOYCON_L
            name.contains("Joy-Con (R)", true) -> JOYCON_R
            name.contains("Pro Controller", true) -> SWITCH_PRO
            name.contains("8BitDo", true) -> EIGHTBITDO
            name.contains("Steam", true) -> STEAM
            name.contains("Odin", true) || name.contains("Thor", true) -> THOR
            else -> GENERIC
        }
    }
}

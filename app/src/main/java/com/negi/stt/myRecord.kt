// myRecord.kt
package com.negi.stt

import kotlinx.serialization.Serializable

@Serializable
data class MyRecord(
    val logs: String = "",
    val absolutePath: String = ""
)

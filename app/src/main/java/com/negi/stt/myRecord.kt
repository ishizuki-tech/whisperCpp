package com.negi.stt
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
data class myRecord(
    var logs: String,
    val absolutePath: String
)
